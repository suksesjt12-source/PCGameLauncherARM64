package com.suksesjt12.pcgamelauncher

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Installs the validated x64 DXVK DLL pair into Wine system32.
 * Native DLL overrides are scoped to the selected game's launch process.
 */
object DxvkInstaller {
    fun install(
        dxvkD3d11: File,
        dxvkDxgi: File,
        version: String,
        profile: GameContainerManager.Profile
    ): RuntimeExecutionManager.Result {
        if (!GameContainerManager.isPrefixInitialized(profile)) {
            return RuntimeExecutionManager.Result(false, "DXVK install", null, "Wine prefix belum diinisialisasi.")
        }
        if (RuntimePackageManager.inspectWindowsExecutable(dxvkD3d11) != "x86_64" ||
            RuntimePackageManager.inspectWindowsExecutable(dxvkDxgi) != "x86_64"
        ) {
            return RuntimeExecutionManager.Result(false, "DXVK validation", null, "DXVK harus berupa DLL PE32+ x86-64.")
        }

        val prefixRoot = profile.directory.canonicalFile
        val windowsRoot = File(prefixRoot, "drive_c/windows").canonicalFile
        val system32 = File(windowsRoot, "system32").canonicalFile
        if (!system32.path.startsWith(windowsRoot.path + File.separator) || !system32.isDirectory) {
            return RuntimeExecutionManager.Result(
                false, "DXVK destination", null,
                "Folder drive_c/windows/system32 tidak ditemukan. Pastikan Wine prefix berhasil dibuat."
            )
        }

        val backupDirectory = File(prefixRoot, "pcgl-dxvk-backup")
        return try {
            if (!backupDirectory.exists() && !backupDirectory.mkdirs()) error("Tidak dapat membuat direktori backup DXVK.")
            installOne(dxvkD3d11, File(system32, "d3d11.dll"), backupDirectory)
            installOne(dxvkDxgi, File(system32, "dxgi.dll"), backupDirectory)
            GameContainerManager.setDxvkState(profile, true, version)
            RuntimeExecutionManager.Result(
                true, "DXVK install", 0,
                "d3d11.dll dan dxgi.dll DXVK telah dipasang ke Wine prefix. " +
                    "Native DLL override aktif saat game ini diluncurkan. Kompatibilitas Vulkan tetap perlu diuji di perangkat."
            )
        } catch (error: Exception) {
            RuntimeExecutionManager.Result(false, "DXVK install", null, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun installOne(source: File, destination: File, backupDirectory: File) {
        val parent = destination.parentFile?.canonicalFile ?: error("Folder tujuan DLL tidak valid.")
        // Validate the parent without canonicalizing the leaf: Wine prefixes may use DLL symlinks.
        if (destination.parentFile?.canonicalFile != parent) error("Path DLL tujuan tidak valid.")
        val backup = File(backupDirectory, destination.name)
        if (destination.isFile && !backup.exists()) copyFile(destination, backup)

        val temporary = File(parent, ".${destination.name}.pcgl-importing")
        if (temporary.exists() && !temporary.delete()) error("Tidak dapat membersihkan file sementara DXVK.")
        copyFile(source, temporary)
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            error("Tidak dapat mengganti ${destination.name} di Wine prefix.")
        }
        if (!temporary.renameTo(destination)) {
            if (backup.isFile && !destination.exists()) copyFile(backup, destination)
            temporary.delete()
            error("Tidak dapat memasang ${destination.name}.")
        }
    }

    private fun copyFile(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
    }
}
