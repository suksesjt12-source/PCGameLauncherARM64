package com.suksesjt12.pcgamelauncher

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Stages a selected Windows executable inside the game's Wine prefix.
 * The game executable remains a guest file; Box64/Wine launches it later.
 */
object GameLaunchManager {
    data class PreparedGame(
        val profile: GameContainerManager.Profile,
        val executable: File,
        val architecture: String
    )

    private const val MAX_EXECUTABLE_BYTES = 4L * 1024L * 1024L * 1024L

    fun prepareExecutable(
        resolver: ContentResolver,
        runtime: RuntimePackageManager.RuntimeInfo,
        profile: GameContainerManager.Profile,
        sourceUri: Uri,
        displayName: String
    ): PreparedGame {
        if (!GameContainerManager.isPrefixInitialized(profile)) {
            error("Wine prefix belum diinisialisasi. Jalankan Initialize Wine prefix dahulu.")
        }

        val safeName = sanitizeExeName(displayName)
        val gameDirectory = GameContainerManager.gameDataDirectory(profile)
        if (!gameDirectory.exists() && !gameDirectory.mkdirs()) {
            error("Tidak dapat membuat direktori game.")
        }

        val destination = File(gameDirectory, safeName).canonicalFile
        if (!destination.path.startsWith(gameDirectory.canonicalPath + File.separator)) {
            error("Path EXE tidak valid.")
        }

        val temporary = File(gameDirectory, ".$safeName.importing")
        if (temporary.exists() && !temporary.delete()) {
            error("Tidak dapat membersihkan impor EXE sebelumnya.")
        }

        var copied = 0L
        try {
            val input = resolver.openInputStream(sourceUri)
                ?: error("Tidak dapat membaca file EXE sumber.")

            input.use { source ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_EXECUTABLE_BYTES) {
                            error("Ukuran EXE melebihi batas impor 4 GiB.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }

            if (copied < 64L) error("File terlalu kecil untuk executable Windows yang valid.")
            val architecture = RuntimePackageManager.inspectWindowsExecutable(temporary)
                ?: error("File yang dipilih bukan Windows PE32/PE32+ x86 atau x86-64 yang didukung.")

            if (architecture == "x86" && !runtime.supportsX86) {
                error("EXE ini 32-bit x86, tetapi runtime yang diimpor tidak menyatakan dukungan WOW64/x86.")
            }

            if (destination.exists() && !destination.delete()) {
                error("Tidak dapat mengganti EXE yang sudah ada.")
            }
            if (!temporary.renameTo(destination)) {
                error("Tidak dapat menempatkan EXE ke workspace game.")
            }

            GameContainerManager.recordExecutable(profile, destination)
            return PreparedGame(profile, destination, architecture)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun sanitizeExeName(displayName: String): String {
        val baseName = displayName.substringAfterLast('/').substringAfterLast('\\')
        val sanitized = baseName
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .trim('.')
            .take(120)

        if (sanitized.isBlank() || !sanitized.endsWith(".exe", ignoreCase = true)) {
            error("Nama file harus berakhiran .exe.")
        }
        return sanitized
    }
}
