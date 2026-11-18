package com.suksesjt12.pcgamelauncher

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads only the official x64 DXVK DLL pair from doitsujin/dxvk.
 * Verifies the archive against GitHub's published SHA-256 digest and extracts
 * only the two required DLLs without writing arbitrary archive paths.
 */
object DxvkComponentManager {
    data class ComponentInfo(
        val version: String,
        val root: File,
        val dxvkD3d11: File,
        val dxvkDxgi: File
    )

    private const val RELEASE_API = "https://api.github.com/repos/doitsujin/dxvk/releases/latest"
    private const val OFFICIAL_RELEASE_PREFIX = "https://github.com/doitsujin/dxvk/releases/download/"
    private const val MAX_API_BYTES = 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 1024L * 1024L * 1024L
    private const val MAX_DLL_BYTES = 64L * 1024L * 1024L
    private const val MAX_TAR_ENTRIES = 100_000

    fun findInstalled(filesDir: File): ComponentInfo? {
        val root = File(File(filesDir, "pcgl"), "components/dxvk")
        val candidates = root.listFiles()
            ?.filter { it.isDirectory && File(it, ".installed.json").isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return null
        for (candidate in candidates) {
            try {
                return readInstalled(candidate)
            } catch (_: Exception) {
                // Ignore incomplete or locally damaged component folders.
            }
        }
        return null
    }

    fun downloadLatest(
        filesDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ComponentInfo {
        val release = readLatestRelease()
        if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) {
            error("GitHub tidak mengembalikan rilis DXVK stabil.")
        }
        val tag = release.optString("tag_name", "")
        if (!tag.matches(Regex("^v[0-9A-Za-z._-]{1,32}$"))) {
            error("Tag rilis DXVK dari GitHub tidak valid.")
        }

        val assets = release.optJSONArray("assets") ?: error("Daftar aset DXVK tidak tersedia.")
        var selected: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") == "dxvk-$tag.tar.gz") {
                selected = asset
                break
            }
        }
        val asset = selected ?: error("Arsip resmi dxvk-$tag.tar.gz tidak ditemukan.")

        val digestField = asset.optString("digest", "")
        val expectedSha256 = digestField.removePrefix("sha256:").lowercase()
        if (!expectedSha256.matches(Regex("^[a-f0-9]{64}$"))) {
            error("GitHub tidak menyediakan SHA-256 resmi untuk arsip ini. Unduhan otomatis dibatalkan.")
        }
        val expectedSize = asset.optLong("size", -1L)
        if (expectedSize <= 0L || expectedSize > MAX_ARCHIVE_BYTES) {
            error("Ukuran arsip DXVK tidak valid atau melewati batas 100 MiB.")
        }

        val downloadUrl = asset.optString("browser_download_url", "")
        if (!downloadUrl.startsWith(OFFICIAL_RELEASE_PREFIX) ||
            !downloadUrl.endsWith("/dxvk-$tag.tar.gz")
        ) {
            error("URL aset DXVK bukan alamat rilis resmi yang diharapkan.")
        }

        val componentsRoot = File(File(filesDir, "pcgl"), "components/dxvk")
        if (!componentsRoot.exists() && !componentsRoot.mkdirs()) {
            error("Tidak dapat membuat folder komponen DXVK.")
        }
        val destination = File(componentsRoot, tag)
        if (destination.isDirectory) {
            try {
                return readInstalled(destination)
            } catch (_: Exception) {
                error("Folder DXVK $tag sudah ada tetapi rusak. Hapus folder komponen tersebut terlebih dahulu.")
            }
        }

        val staging = File(componentsRoot, ".staging-$tag-${System.nanoTime()}")
        if (!staging.mkdirs()) error("Tidak dapat membuat folder sementara DXVK.")
        try {
            val archive = File(staging, "download.tar.gz")
            downloadAsset(downloadUrl, archive, expectedSize, expectedSha256, onProgress)
            extractRequiredDlls(archive, staging)

            if (RuntimePackageManager.inspectWindowsExecutable(File(staging, "d3d11.dll")) != "x86_64" ||
                RuntimePackageManager.inspectWindowsExecutable(File(staging, "dxgi.dll")) != "x86_64"
            ) {
                error("DLL dari arsip DXVK bukan PE32+ x86_64 yang valid.")
            }
            if (!archive.delete()) error("Tidak dapat membersihkan arsip DXVK sementara.")

            File(staging, ".installed.json").writeText(
                JSONObject()
                    .put("version", tag)
                    .put("source", "https://github.com/doitsujin/dxvk/releases/tag/$tag")
                    .put("asset", "dxvk-$tag.tar.gz")
                    .put("archiveSha256", expectedSha256)
                    .put("d3d11Sha256", sha256(File(staging, "d3d11.dll")))
                    .put("dxgiSha256", sha256(File(staging, "dxgi.dll")))
                    .put("installedAt", System.currentTimeMillis())
                    .toString()
            )
            if (!staging.renameTo(destination)) {
                error("Tidak dapat mengaktifkan komponen DXVK setelah ekstraksi.")
            }
            return readInstalled(destination)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun readInstalled(directory: File): ComponentInfo {
        val marker = File(directory, ".installed.json")
        if (!marker.isFile || marker.length() > 16 * 1024L) error("Metadata DXVK tidak valid.")
        val json = JSONObject(marker.readText(Charsets.UTF_8))
        val version = json.optString("version", "")
        if (!version.matches(Regex("^v[0-9A-Za-z._-]{1,32}$"))) error("Versi DXVK lokal tidak valid.")
        if (json.optString("source", "") != "https://github.com/doitsujin/dxvk/releases/tag/$version") {
            error("Asal komponen DXVK tidak valid.")
        }
        val d3d11 = File(directory, "d3d11.dll")
        val dxgi = File(directory, "dxgi.dll")
        if (RuntimePackageManager.inspectWindowsExecutable(d3d11) != "x86_64" ||
            RuntimePackageManager.inspectWindowsExecutable(dxgi) != "x86_64"
        ) error("DLL DXVK lokal tidak valid.")
        if (sha256(d3d11) != json.optString("d3d11Sha256", "") ||
            sha256(dxgi) != json.optString("dxgiSha256", "")
        ) error("Checksum DLL DXVK lokal tidak cocok.")
        return ComponentInfo(version, directory, d3d11, dxgi)
    }

    private fun readLatestRelease(): JSONObject {
        val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "PCGameLauncherARM64")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("GitHub API gagal: HTTP ${connection.responseCode}. Coba lagi nanti.")
            }
            val bytes = connection.inputStream.use { readBounded(it, MAX_API_BYTES) }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAsset(
        rawUrl: String,
        destination: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Long, Long) -> Unit
    ) {
        val url = URL(rawUrl)
        if (url.protocol != "https" || url.host != "github.com") {
            error("Server unduhan DXVK tidak tepercaya.")
        }
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "PCGameLauncherARM64")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("Unduhan DXVK gagal: HTTP ${connection.responseCode}.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            FileOutputStream(destination).buffered().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_ARCHIVE_BYTES || downloaded > expectedSize) {
                            error("Arsip DXVK melebihi ukuran yang diumumkan GitHub.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(downloaded, expectedSize)
                    }
                }
                output.flush()
            }
            if (downloaded != expectedSize) error("Ukuran unduhan DXVK tidak sesuai metadata GitHub.")
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != expectedSha256) {
                error("Checksum arsip DXVK tidak cocok dengan SHA-256 resmi GitHub.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRequiredDlls(archive: File, destination: File) {
        var entries = 0
        var expandedBytes = 0L
        val found = mutableSetOf<String>()
        GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
            val header = ByteArray(512)
            while (readTarBlock(input, header)) {
                if (header.all { it == 0.toByte() }) break
                entries++
                if (entries > MAX_TAR_ENTRIES) error("Arsip DXVK memiliki terlalu banyak entri.")

                val name = tarString(header, 0, 100)
                val prefix = tarString(header, 345, 155)
                val path = if (prefix.isBlank()) name else "$prefix/$name"
                val segments = path.replace('\\', '/').split('/')
                if (path.startsWith("/") || segments.any { it == ".." || it.isBlank() }) {
                    error("Arsip DXVK berisi nama path yang tidak valid.")
                }

                val size = tarOctal(header, 124, 12)
                if (size < 0L || size > MAX_EXPANDED_BYTES) error("Ukuran entri tar DXVK tidak valid.")
                expandedBytes += size
                if (expandedBytes > MAX_EXPANDED_BYTES) error("Hasil ekstraksi DXVK melewati batas 1 GiB.")

                val type = header[156].toInt().toChar()
                val target = when {
                    type in listOf('\u0000', '0') && (path == "x64/d3d11.dll" || path.endsWith("/x64/d3d11.dll")) -> "d3d11.dll"
                    type in listOf('\u0000', '0') && (path == "x64/dxgi.dll" || path.endsWith("/x64/dxgi.dll")) -> "dxgi.dll"
                    else -> null
                }
                if (target != null) {
                    if (!found.add(target)) error("Arsip DXVK memiliki entri ganda: $target")
                    if (size <= 0L || size > MAX_DLL_BYTES) error("Ukuran $target tidak valid.")
                    FileOutputStream(File(destination, target)).buffered().use { output ->
                        copyExactly(input, output, size)
                    }
                } else {
                    skipExactly(input, size)
                }
                val padding = (512L - (size % 512L)) % 512L
                skipExactly(input, padding)
            }
        }
        if (!found.contains("d3d11.dll") || !found.contains("dxgi.dll")) {
            error("Arsip DXVK tidak memiliki pasangan x64/d3d11.dll dan x64/dxgi.dll.")
        }
    }

    private fun readTarBlock(input: InputStream, block: ByteArray): Boolean {
        var offset = 0
        while (offset < block.size) {
            val count = input.read(block, offset, block.size - offset)
            if (count < 0) {
                if (offset == 0) return false
                error("Arsip tar DXVK terpotong.")
            }
            if (count == 0) continue
            offset += count
        }
        return true
    }

    private fun tarString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun tarOctal(header: ByteArray, offset: Int, length: Int): Long {
        val value = String(header, offset, length, Charsets.US_ASCII).trim('\u0000', ' ', '\n', '\r')
        if (value.isEmpty()) return 0L
        if (!value.all { it in '0'..'7' }) error("Header ukuran tar DXVK tidak valid.")
        return value.toLong(8)
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) error("Data DLL terpotong dalam arsip DXVK.")
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipExactly(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) error("Data arsip DXVK terpotong.")
                if (count > 0) remaining -= count
            }
        }
    }

    private fun readBounded(input: InputStream, maximumBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) error("Respons metadata GitHub terlalu besar.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
