package com.suksesjt12.pcgamelauncher

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import com.github.luben.zstd.ZstdInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HashSet

/**
 * One-tap runtime bootstrap based on the published Winlator Mali Bionic rootfs
 * and its x86_64 Proton package. Both source archives are pinned by size and
 * SHA-256; no user-supplied runtime ZIP is required.
 *
 * This installs the guest filesystem and Wine files. The app's Android-side
 * XServer/SDL display lifecycle is a separate integration task.
 */
object RuntimeBootstrapManager {
    private data class Artifact(
        val id: String,
        val fileName: String,
        val url: String,
        val size: Long,
        val sha256: String
    )

    private const val ROOTFS_VERSION = "winlator-mali-bionic-2026-07"
    private const val WINE_VERSION = "proton-9.0-x86_64"
    private const val MAX_EXPANDED_BYTES = 12L * 1024L * 1024L * 1024L
    private const val MAX_TAR_ENTRIES = 300_000
    private const val MAX_PATH_LENGTH = 4096

    // Published release assets from GunaCharanTeja/Winlator-Extras, release "Sd".
    private val rootfsArtifact = Artifact(
        id = "imagefs",
        fileName = "imagefs.tzst",
        url = "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/Sd/imagefs.tzst",
        size = 199_788_880L,
        sha256 = "0c8c6c1c7653766323378f3294be6618392075bd354d50b2416a534a6e231620"
    )

    private val wineArtifact = Artifact(
        id = "proton-x64",
        fileName = "proton-9.0-x86_64.tzst",
        url = "https://github.com/GunaCharanTeja/Winlator-Extras/releases/download/Sd/proton-9.0-x86_64.tzst",
        size = 52_247_947L,
        sha256 = "9c60d605b68865a6c9861bdda54c2e4d845183b0b4ee3e04a8677eb1a74207a6"
    )

    data class InstallProgress(
        val stage: String,
        val completedBytes: Long = 0L,
        val totalBytes: Long = 0L
    )

    fun isManaged(runtime: RuntimePackageManager.RuntimeInfo): Boolean =
        runtime.id == "winlator-mali-bionic-proton-x64"

    fun findInstalled(filesDir: File): RuntimePackageManager.RuntimeInfo? {
        val imageFs = File(filesDir, "imagefs")
        val marker = File(imageFs, ".pcgl-bootstrap.json")
        if (!marker.isFile || marker.length() > 16 * 1024L) return null

        return try {
            val metadata = JSONObject(marker.readText(Charsets.UTF_8))
            if (metadata.optString("rootfsSha256") != rootfsArtifact.sha256 ||
                metadata.optString("wineSha256") != wineArtifact.sha256 ||
                metadata.optString("rootfsVersion") != ROOTFS_VERSION ||
                metadata.optString("wineVersion") != WINE_VERSION
            ) return null

            createRuntimeInfo(imageFs)
        } catch (_: Exception) {
            null
        }
    }

    fun install(
        filesDir: File,
        onProgress: (InstallProgress) -> Unit = {}
    ): RuntimePackageManager.RuntimeInfo {
        findInstalled(filesDir)?.let { return it }

        val finalImageFs = File(filesDir, "imagefs")
        if (finalImageFs.exists() && (finalImageFs.list()?.isNotEmpty() == true)) {
            error("Folder imagefs sudah ada tetapi bukan instalasi otomatis yang valid. Data lama tidak akan ditimpa.")
        }

        val bootstrapDir = File(File(filesDir, "pcgl"), "bootstrap")
        if (!bootstrapDir.exists() && !bootstrapDir.mkdirs()) {
            error("Tidak dapat membuat folder pemasangan runtime.")
        }
        val staging = File(bootstrapDir, ".staging-${System.nanoTime()}")
        val stagedImageFs = File(staging, "imagefs")
        val downloads = File(staging, "downloads")
        if (!downloads.mkdirs() || !stagedImageFs.mkdirs()) {
            error("Tidak dapat membuat folder sementara pemasangan runtime.")
        }

        try {
            val rootfsArchive = File(downloads, rootfsArtifact.fileName)
            downloadVerified(rootfsArtifact, rootfsArchive, onProgress)
            onProgress(InstallProgress("Mengekstrak sistem dasar Winlator Mali..."))
            extractZstdTar(rootfsArchive, stagedImageFs, onProgress, "Sistem dasar")
            if (!rootfsArchive.delete()) error("Tidak dapat menghapus arsip sistem dasar sementara.")

            val wineArchive = File(downloads, wineArtifact.fileName)
            downloadVerified(wineArtifact, wineArchive, onProgress)
            val wineRoot = File(stagedImageFs, "opt/$WINE_VERSION")
            if (!wineRoot.mkdirs() && !wineRoot.isDirectory) {
                error("Tidak dapat membuat direktori Proton.")
            }
            onProgress(InstallProgress("Mengekstrak Proton x86_64..."))
            extractZstdTar(wineArchive, wineRoot, onProgress, "Proton x86_64")
            if (!wineArchive.delete()) error("Tidak dapat menghapus arsip Proton sementara.")

            val versionFile = File(stagedImageFs, ".winlator/.img_version")
            if (!versionFile.parentFile!!.exists() && !versionFile.parentFile!!.mkdirs()) {
                error("Tidak dapat membuat metadata versi sistem.")
            }
            versionFile.writeText("21", Charsets.UTF_8)

            val metadataFile = File(stagedImageFs, ".pcgl-bootstrap.json")
            metadataFile.writeText(
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("rootfsVersion", ROOTFS_VERSION)
                    .put("rootfsSha256", rootfsArtifact.sha256)
                    .put("wineVersion", WINE_VERSION)
                    .put("wineSha256", wineArtifact.sha256)
                    .put("installedAt", System.currentTimeMillis())
                    .toString(),
                Charsets.UTF_8
            )

            // Validate the extracted guest binaries before making the installation visible.
            createRuntimeInfo(stagedImageFs)

            if (finalImageFs.exists() && !finalImageFs.deleteRecursively()) {
                error("Tidak dapat membersihkan folder imagefs kosong.")
            }
            if (!stagedImageFs.renameTo(finalImageFs)) {
                error("Gagal mengaktifkan sistem runtime yang sudah diekstrak.")
            }

            val installed = findInstalled(filesDir)
                ?: error("Runtime terpasang, tetapi pemeriksaan akhir gagal.")
            staging.deleteRecursively()
            onProgress(InstallProgress("Runtime dasar berhasil dipasang.", 1L, 1L))
            return installed
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun createRuntimeInfo(imageFs: File): RuntimePackageManager.RuntimeInfo {
        val requiredRootfsDirectories = listOf("usr/bin", "usr/lib", "usr/share", "usr/etc", "home")
        val missingRootfsDirectories = requiredRootfsDirectories
            .filter { !File(imageFs, it).isDirectory }
        if (missingRootfsDirectories.isNotEmpty()) {
            error("Root filesystem Winlator Mali tidak lengkap: ${missingRootfsDirectories.joinToString()}")
        }

        val wineRoot = File(imageFs, "opt/$WINE_VERSION")
        if (!wineRoot.isDirectory) error("Direktori Proton tidak ditemukan.")

        val wine64 = firstValidElf(
            wineRoot,
            listOf("bin/wine64", "bin/wine64-stable", "bin/wine")
        ) ?: error("Executable Wine x86_64 tidak ditemukan atau arsitekturnya salah.")

        val wineserver = firstValidElf(
            wineRoot,
            listOf("bin/wineserver", "bin/wineserver64", "bin/wineserver-stable")
        ) ?: error("Executable wineserver x86_64 tidak ditemukan atau arsitekturnya salah.")

        val wineboot = listOf(
            "lib/wine/x86_64-windows/wineboot.exe",
            "lib64/wine/x86_64-windows/wineboot.exe",
            "lib/wine/wineboot.exe"
        ).map { File(wineRoot, it) }
            .firstOrNull { RuntimePackageManager.inspectWindowsExecutable(it) == "x86_64" }
            ?: error("wineboot.exe PE32+ x86_64 tidak ditemukan.")

        val libraryPaths = listOf(
            File(wineRoot, "lib"),
            File(wineRoot, "lib64"),
            File(wineRoot, "lib/x86_64-linux-gnu"),
            File(wineRoot, "lib64/wine")
        ).filter { it.isDirectory }.distinct()

        if (libraryPaths.isEmpty()) error("Direktori library Proton tidak ditemukan.")

        return RuntimePackageManager.RuntimeInfo(
            id = "winlator-mali-bionic-proton-x64",
            name = "Winlator Mali Bionic / Proton x86_64",
            version = "9.0",
            hostAbi = "arm64-v8a",
            supportsX86 = false,
            root = wineRoot,
            wine64 = wine64,
            wineserver = wineserver,
            winebootExe = wineboot,
            dxvkD3d11 = null,
            dxvkDxgi = null,
            libraryPaths = libraryPaths
        )
    }

    private fun firstValidElf(root: File, candidates: List<String>): File? =
        candidates.asSequence()
            .map { File(root, it) }
            .firstOrNull { isX86_64Elf(it) }

    private fun isX86_64Elf(file: File): Boolean {
        if (!file.isFile || file.length() < 20L) return false
        val header = ByteArray(20)
        return try {
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read <= 0) return false
                    offset += read
                }
            }
            header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte() &&
                header[4] == 2.toByte() &&
                header[5] == 1.toByte() &&
                (header[18].toInt() and 0xff) == 62 &&
                (header[19].toInt() and 0xff) == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadVerified(
        artifact: Artifact,
        destination: File,
        onProgress: (InstallProgress) -> Unit
    ) {
        val connection = URL(artifact.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "PCGameLauncherARM64")
        connection.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("Unduhan ${artifact.fileName} gagal: HTTP ${connection.responseCode}.")
            }
            val finalHost = URL(connection.url.toString()).host.lowercase()
            if (finalHost !in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                error("Server pengalihan unduhan tidak diizinkan: $finalHost")
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > 0L && declaredLength != artifact.size) {
                error("Ukuran server untuk ${artifact.fileName} tidak cocok dengan metadata rilis.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            FileOutputStream(destination).buffered().use { output ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > artifact.size) {
                            error("Ukuran ${artifact.fileName} melebihi batas metadata rilis.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (downloaded % (2L * 1024L * 1024L) < buffer.size) {
                            onProgress(InstallProgress("Mengunduh ${artifact.fileName}...", downloaded, artifact.size))
                        }
                    }
                }
            }

            if (downloaded != artifact.size) {
                error("Unduhan ${artifact.fileName} tidak lengkap: $downloaded dari ${artifact.size} byte.")
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHash != artifact.sha256) {
                error("SHA-256 ${artifact.fileName} tidak cocok. Arsip ditolak.")
            }
            onProgress(InstallProgress("Verifikasi ${artifact.fileName} selesai.", downloaded, artifact.size))
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun extractZstdTar(
        archive: File,
        destination: File,
        onProgress: (InstallProgress) -> Unit,
        label: String
    ) {
        if (!destination.exists() && !destination.mkdirs()) {
            error("Tidak dapat membuat folder ekstraksi $label.")
        }
        val root = destination.canonicalFile.toPath()
        val seen = HashSet<String>()
        var entries = 0
        var expandedBytes = 0L
        var lastReportedBytes = 0L

        TarArchiveInputStream(
            ZstdInputStream(BufferedInputStream(FileInputStream(archive), 128 * 1024))
        ).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                entries++
                if (entries > MAX_TAR_ENTRIES) error("Arsip $label memiliki terlalu banyak file.")
                val relative = normalizeArchivePath(entry.name) ?: continue
                if (!seen.add(relative)) error("Arsip $label memiliki path duplikat: $relative")

                val output = resolveSafeDestination(root, relative)
                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) {
                        error("Gagal membuat direktori $relative.")
                    }
                    applyMode(output, entry.mode)
                } else if (entry.isSymbolicLink) {
                    val parent = output.parentFile ?: error("Path symlink tidak valid.")
                    if (!parent.exists() && !parent.mkdirs()) error("Gagal membuat direktori symlink.")
                    val target = resolveSymlinkTarget(root, parent.toPath(), entry.linkName)
                    if (output.exists() || Files.isSymbolicLink(output.toPath())) output.delete()
                    val relativeTarget = parent.toPath().toAbsolutePath().normalize().relativize(target)
                    Files.createSymbolicLink(output.toPath(), relativeTarget)
                } else if (entry.isLink) {
                    val parent = output.parentFile ?: error("Path hardlink tidak valid.")
                    if (!parent.exists() && !parent.mkdirs()) error("Gagal membuat direktori hardlink.")
                    val linkTargetName = normalizeArchivePath(entry.linkName)
                        ?: error("Target hardlink tidak valid.")
                    val linkTarget = resolveSafeDestination(root, linkTargetName)
                    if (!linkTarget.isFile) error("Target hardlink belum tersedia: ${entry.linkName}")
                    if (output.exists()) output.delete()
                    Files.createLink(output.toPath(), linkTarget.toPath())
                } else if (entry.isFile) {
                    val parent = output.parentFile ?: error("Path file tidak valid.")
                    if (!parent.exists() && !parent.mkdirs()) error("Gagal membuat direktori file.")
                    if (output.exists() && !output.delete()) error("Tidak dapat mengganti file $relative.")
                    FileOutputStream(output).buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = tar.read(buffer)
                            if (count < 0) break
                            expandedBytes += count
                            if (expandedBytes > MAX_EXPANDED_BYTES) {
                                error("Hasil ekstraksi $label melebihi 12 GiB.")
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                    applyMode(output, entry.mode)
                    if (expandedBytes - lastReportedBytes >= 32L * 1024L * 1024L) {
                        lastReportedBytes = expandedBytes
                        onProgress(InstallProgress("Mengekstrak $label...", expandedBytes, 0L))
                    }
                }
                // Device nodes, sockets and FIFOs are intentionally not created by an Android app.
            }
        }
        if (entries == 0) error("Arsip $label kosong atau tidak valid.")
    }

    private fun normalizeArchivePath(raw: String): String? {
        if (raw.length > MAX_PATH_LENGTH || raw.indexOf('\u0000') >= 0) {
            error("Arsip memiliki path terlalu panjang atau tidak valid.")
        }
        var path = raw.replace('\\', '/')
        while (path.startsWith("./")) path = path.substring(2)
        path = path.trimEnd('/')
        if (path.isEmpty() || path == ".") return null
        if (path.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(path)) {
            error("Arsip mencoba menulis ke path absolut: $raw")
        }
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == ".." }) {
            error("Arsip memiliki path traversal: $raw")
        }
        return parts.filter { it != "." }.joinToString("/")
    }

    private fun resolveSafeDestination(root: Path, relative: String): File {
        val candidate = root.resolve(relative).normalize()
        if (!candidate.startsWith(root)) error("Path arsip keluar dari direktori pemasangan.")
        val parent = candidate.parent ?: error("Path arsip tidak memiliki direktori induk.")
        val canonicalParent = parent.toFile().canonicalFile.toPath()
        if (!canonicalParent.startsWith(root)) error("Symlink arsip keluar dari direktori pemasangan.")
        return canonicalParent.resolve(candidate.fileName).toFile()
    }

    private fun resolveSymlinkTarget(root: Path, parent: Path, rawTarget: String): Path {
        if (rawTarget.isBlank() || rawTarget.indexOf('\u0000') >= 0) {
            error("Target symlink kosong atau tidak valid.")
        }
        val target = if (rawTarget.startsWith("/")) {
            root.resolve(rawTarget.removePrefix("/")).normalize()
        } else {
            parent.resolve(rawTarget).normalize()
        }
        if (!target.startsWith(root)) error("Symlink arsip keluar dari direktori pemasangan.")
        return target
    }

    private fun applyMode(file: File, rawMode: Int) {
        val mode = rawMode and 0x1ff
        try {
            Os.chmod(file.absolutePath, if (mode == 0) 493 else mode)
        } catch (_: Exception) {
            // Android filesystems may not support every permission bit; runtime validation checks executables.
        }
    }
}
