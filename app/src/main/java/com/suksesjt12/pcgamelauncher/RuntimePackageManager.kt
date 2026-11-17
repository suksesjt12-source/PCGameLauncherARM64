package com.suksesjt12.pcgamelauncher

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Imports a user-provided runtime archive into private app storage.
 *
 * Archive format is documented in docs/runtime-package-format.md.
 * Importing files does not guarantee that Android can execute the binaries.
 */
object RuntimePackageManager {
    data class RuntimeInfo(
        val id: String,
        val name: String,
        val version: String,
        val abi: String,
        val root: File,
        val box64: File,
        val wine: File,
        val wineserver: File,
        val dxvkD3d11: File,
        val dxvkDxgi: File
    )

    private const val MANIFEST = "runtime.json"
    private const val INSTALLED_MARKER = ".installed"
    private const val MAX_ENTRIES = 100_000
    private const val MAX_EXPANDED_BYTES = 10L * 1024L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 512L * 1024L

    fun install(
        resolver: ContentResolver,
        archiveUri: Uri,
        filesDir: File
    ): RuntimeInfo {
        val runtimesDir = File(File(filesDir, "pcgl"), "runtimes")
        if (!runtimesDir.exists() && !runtimesDir.mkdirs()) {
            error("Tidak dapat membuat direktori runtime.")
        }

        val staging = File(runtimesDir, ".staging-${System.nanoTime()}")
        if (!staging.mkdirs()) error("Tidak dapat membuat direktori sementara.")

        try {
            extractSafely(resolver, archiveUri, staging)
            val info = readAndValidate(staging, verifyHashes = true)

            val destinationName = "${info.id}-${info.version}"
            val destination = File(runtimesDir, destinationName)
            if (destination.exists()) {
                error("Runtime ${info.name} versi ${info.version} sudah ada.")
            }

            File(staging, INSTALLED_MARKER).writeText(
                JSONObject()
                    .put("installedAt", System.currentTimeMillis())
                    .put("runtimeId", info.id)
                    .put("version", info.version)
                    .toString()
            )

            if (!staging.renameTo(destination)) {
                error("Tidak dapat memindahkan paket runtime ke direktori final.")
            }

            return readAndValidate(destination, verifyHashes = false)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun findInstalled(filesDir: File): RuntimeInfo? {
        val runtimesDir = File(File(filesDir, "pcgl"), "runtimes")
        val candidates = runtimesDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".staging-") && File(it, INSTALLED_MARKER).isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return null

        for (candidate in candidates) {
            try {
                return readAndValidate(candidate, verifyHashes = false)
            } catch (_: Exception) {
                // Ignore an incomplete or damaged installation and inspect the next one.
            }
        }
        return null
    }

    private fun extractSafely(resolver: ContentResolver, uri: Uri, destination: File) {
        val root = destination.canonicalFile
        val seen = HashSet<String>()
        var entryCount = 0
        var expandedBytes = 0L

        val input = resolver.openInputStream(uri) ?: error("Tidak dapat membuka file ZIP.")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) error("Arsip memiliki terlalu banyak entri.")

                val relativePath = normalizeEntryPath(entry.name)
                if (!seen.add(relativePath)) error("Arsip memiliki path duplikat: $relativePath")

                val output = File(root, relativePath).canonicalFile
                val rootPrefix = root.path + File.separator
                if (!output.path.startsWith(rootPrefix)) {
                    error("Arsip berisi path di luar direktori tujuan.")
                }

                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) {
                        error("Gagal membuat direktori paket.")
                    }
                } else {
                    val parent = output.parentFile ?: error("Path file tidak valid.")
                    if (!parent.exists() && !parent.mkdirs()) error("Gagal membuat direktori paket.")
                    if (output.exists()) error("File paket bertabrakan: $relativePath")

                    FileOutputStream(output).buffered().use { fileOut ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expandedBytes += count
                            if (expandedBytes > MAX_EXPANDED_BYTES) {
                                error("Ukuran hasil ekstraksi melewati batas 10 GiB.")
                            }
                            fileOut.write(buffer, 0, count)
                        }
                    }

                    if (relativePath == MANIFEST && output.length() > MAX_MANIFEST_BYTES) {
                        error("runtime.json terlalu besar.")
                    }
                }
                zip.closeEntry()
            }
        }

        if (!File(destination, MANIFEST).isFile) {
            error("runtime.json tidak ditemukan di direktori utama ZIP.")
        }
    }

    private fun normalizeEntryPath(rawPath: String): String {
        val path = rawPath.replace('\\', '/')
        if (path.isBlank() || path.startsWith("/") || path.startsWith("//")) {
            error("Arsip memiliki path yang tidak valid.")
        }
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) {
            error("Path absolut Windows tidak diizinkan.")
        }

        val trimmed = path.removeSuffix("/")
        val parts = trimmed.split('/')
        if (parts.isEmpty() || parts.any { it.isBlank() || it == "." || it == ".." }) {
            error("Arsip memiliki path traversal atau segmen kosong.")
        }
        if (parts.any { it.contains('\u0000') }) error("Path mengandung karakter tidak valid.")
        return parts.joinToString("/")
    }

    private fun readAndValidate(root: File, verifyHashes: Boolean): RuntimeInfo {
        val manifestFile = File(root, MANIFEST)
        if (!manifestFile.isFile || manifestFile.length() > MAX_MANIFEST_BYTES) {
            error("Manifest runtime tidak valid.")
        }

        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        if (manifest.optInt("schemaVersion", -1) != 1) {
            error("Versi format runtime.json tidak didukung.")
        }

        val id = manifest.optString("id", "")
        val name = manifest.optString("name", "")
        val version = manifest.optString("version", "")
        val abi = manifest.optString("abi", "")
        if (!id.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) error("ID runtime tidak valid.")
        if (name.isBlank() || name.length > 100) error("Nama runtime tidak valid.")
        if (!version.matches(Regex("^[A-Za-z0-9._-]{1,32}$"))) error("Versi runtime tidak valid.")
        if (abi != "arm64-v8a") error("Paket ini bukan runtime Android ARM64 (arm64-v8a).")

        val components = manifest.optJSONObject("components")
            ?: error("Bagian components tidak ditemukan.")
        val hashes = manifest.optJSONObject("sha256")
            ?: error("Bagian sha256 tidak ditemukan.")

        fun component(key: String): File {
            val relative = components.optString(key, "")
            val safe = normalizeEntryPath(relative)
            val file = File(root, safe).canonicalFile
            if (!file.path.startsWith(root.canonicalPath + File.separator)) {
                error("Path komponen $key tidak valid.")
            }
            if (!file.isFile || file.length() == 0L) {
                error("Komponen $key tidak ditemukan atau kosong.")
            }

            val expectedHash = hashes.optString(safe, "").lowercase()
            if (!expectedHash.matches(Regex("^[a-f0-9]{64}$"))) {
                error("SHA-256 untuk $safe tidak valid atau tidak tersedia.")
            }
            if (verifyHashes && sha256(file) != expectedHash) {
                error("SHA-256 tidak cocok untuk $safe. Paket mungkin rusak atau berubah.")
            }
            return file
        }

        val box64 = component("box64")
        val wine = component("wine")
        val wineserver = component("wineserver")
        val dxvkD3d11 = component("dxvkD3d11")
        val dxvkDxgi = component("dxvkDxgi")

        if (!isAarch64Elf(box64)) {
            error("Binary Box64 bukan ELF 64-bit little-endian AArch64.")
        }

        box64.setExecutable(true, false)
        wine.setExecutable(true, false)
        wineserver.setExecutable(true, false)

        return RuntimeInfo(
            id, name, version, abi, root,
            box64, wine, wineserver, dxvkD3d11, dxvkDxgi
        )
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

    private fun isAarch64Elf(file: File): Boolean {
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count <= 0) return false
                offset += count
            }
        }
        val isElf = header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()
        val is64BitLittleEndian = header[4] == 2.toByte() && header[5] == 1.toByte()
        val machine = (header[18].toInt() and 0xff) or
            ((header[19].toInt() and 0xff) shl 8)
        return isElf && is64BitLittleEndian && machine == 183
    }
}
