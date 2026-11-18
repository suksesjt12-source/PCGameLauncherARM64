package com.suksesjt12.pcgamelauncher

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Imports the x86_64 Wine/DXVK userspace files.
 * The native ARM64 Box64 engine is packaged inside the APK, not extracted here.
 */
object RuntimePackageManager {
    data class RuntimeInfo(
        val id: String,
        val name: String,
        val version: String,
        val hostAbi: String,
        val supportsX86: Boolean,
        val root: File,
        val wine64: File,
        val wineserver: File,
        val winebootExe: File,
        val dxvkD3d11: File,
        val dxvkDxgi: File,
        val libraryPaths: List<File>
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

            val destination = File(runtimesDir, "${info.id}-${info.version}")
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
            ?.filter {
                it.isDirectory &&
                    !it.name.startsWith(".staging-") &&
                    File(it, INSTALLED_MARKER).isFile
            }
            ?.sortedByDescending { it.lastModified() }
            ?: return null

        for (candidate in candidates) {
            try {
                return readAndValidate(candidate, verifyHashes = false)
            } catch (_: Exception) {
                // Ignore incomplete or damaged packages.
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
                if (!output.path.startsWith(root.path + File.separator)) {
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
        if (parts.any { part -> part.any { character -> character.code == 0 } }) error("Path mengandung karakter tidak valid.")
        return parts.joinToString("/")
    }

    private fun readAndValidate(root: File, verifyHashes: Boolean): RuntimeInfo {
        val manifestFile = File(root, MANIFEST)
        if (!manifestFile.isFile || manifestFile.length() > MAX_MANIFEST_BYTES) {
            error("Manifest runtime tidak valid.")
        }

        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        if (manifest.optInt("schemaVersion", -1) != 2) {
            error("Format runtime harus schemaVersion 2.")
        }

        val id = manifest.optString("id", "")
        val name = manifest.optString("name", "")
        val version = manifest.optString("version", "")
        val hostAbi = manifest.optString("hostAbi", "")
        if (!id.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) error("ID runtime tidak valid.")
        if (name.isBlank() || name.length > 100) error("Nama runtime tidak valid.")
        if (!version.matches(Regex("^[A-Za-z0-9._-]{1,32}$"))) error("Versi runtime tidak valid.")
        if (hostAbi != "arm64-v8a") error("Paket ini bukan untuk host Android ARM64.")

        val components = manifest.optJSONObject("components")
            ?: error("Bagian components tidak ditemukan.")
        val hashes = manifest.optJSONObject("sha256")
            ?: error("Bagian sha256 tidak ditemukan.")

        fun component(key: String, required: Boolean = true): File? {
            val relative = components.optString(key, "")
            if (relative.isBlank()) {
                if (required) error("Komponen $key tidak ditemukan dalam manifest.")
                return null
            }
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

        val wine64 = component("wine64")!!
        val wineserver = component("wineserver")!!
        val winebootExe = component("winebootExe")!!
        val dxvkD3d11 = component("dxvkD3d11", required = false)
        val dxvkDxgi = component("dxvkDxgi", required = false)
        if ((dxvkD3d11 == null) != (dxvkDxgi == null)) {
            error("Komponen DXVK harus menyertakan d3d11.dll dan dxgi.dll sekaligus.")
        }

        if (!isElfMachine(wine64, 62)) {
            error("wine64 harus berupa ELF x86_64 (EM_X86_64).")
        }
        if (!isElfMachine(wineserver, 62)) {
            error("wineserver harus berupa ELF x86_64 (EM_X86_64).")
        }
        if (!isPe64X64(winebootExe)) {
            error("wineboot.exe harus berupa PE32+ x86_64.")
        }
        if (dxvkD3d11 != null && dxvkDxgi != null &&
            (!isPe64X64(dxvkD3d11) || !isPe64X64(dxvkDxgi))
        ) {
            error("DXVK d3d11.dll dan dxgi.dll harus berupa PE32+ x86_64.")
        }

        // Box64 reads these as guest binaries; Android does not exec them directly.
        listOf(wine64, wineserver).forEach {
            if (!it.setExecutable(true, false) && !it.canExecute()) {
                error("Tidak dapat mengatur permission guest binary: ${it.name}")
            }
        }

        val libraryPaths = mutableListOf<File>()
        val declaredPaths = manifest.optJSONArray("libraryPaths")
        if (declaredPaths != null) {
            for (index in 0 until declaredPaths.length()) {
                val relative = normalizeEntryPath(declaredPaths.optString(index, ""))
                val directory = File(root, relative).canonicalFile
                if (!directory.path.startsWith(root.canonicalPath + File.separator) ||
                    !directory.isDirectory
                ) {
                    error("Direktori library tidak valid: $relative")
                }
                libraryPaths.add(directory)
            }
        }

        return RuntimeInfo(
            id = id,
            name = name,
            version = version,
            hostAbi = hostAbi,
            supportsX86 = manifest.optBoolean("supportsX86", false),
            root = root,
            wine64 = wine64,
            wineserver = wineserver,
            winebootExe = winebootExe,
            dxvkD3d11 = dxvkD3d11,
            dxvkDxgi = dxvkDxgi,
            libraryPaths = libraryPaths
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

    private fun isElfMachine(file: File, expectedMachine: Int): Boolean {
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
        val elfType = (header[16].toInt() and 0xff) or
            ((header[17].toInt() and 0xff) shl 8)
        val machine = (header[18].toInt() and 0xff) or
            ((header[19].toInt() and 0xff) shl 8)
        return isElf && is64BitLittleEndian && elfType in 2..3 && machine == expectedMachine
    }

    fun inspectWindowsExecutable(file: File): String? {
        val pe = readPeArchitecture(file) ?: return null
        return when {
            pe.first == 0x8664 && pe.second == 0x20b -> "x86_64"
            pe.first == 0x014c && pe.second == 0x010b -> "x86"
            else -> null
        }
    }

    private fun isPe64X64(file: File): Boolean =
        inspectWindowsExecutable(file) == "x86_64"

    private fun readPeArchitecture(file: File): Pair<Int, Int>? {
        return try {
            RandomAccessFile(file, "r").use { input ->
                if (input.length() < 0x40) return null
                if (input.readUnsignedByte() != 'M'.code ||
                    input.readUnsignedByte() != 'Z'.code
                ) return null

                input.seek(0x3c)
                val offset = readIntLe(input).toLong()
                if (offset < 0x40 || offset + 26 > input.length()) return null

                input.seek(offset)
                if (input.readUnsignedByte() != 'P'.code ||
                    input.readUnsignedByte() != 'E'.code ||
                    input.readUnsignedByte() != 0 ||
                    input.readUnsignedByte() != 0
                ) return null

                val machine = readUShortLe(input)
                input.seek(offset + 24)
                val optionalMagic = readUShortLe(input)
                machine to optionalMagic
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readUShortLe(input: RandomAccessFile): Int {
        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()
        return low or (high shl 8)
    }

    private fun readIntLe(input: RandomAccessFile): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val b3 = input.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
