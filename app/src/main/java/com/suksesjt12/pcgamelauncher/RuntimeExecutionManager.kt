package com.suksesjt12.pcgamelauncher

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Launches the APK-bundled ARM64 Box64 engine.
 *
 * Box64 must be installed under ApplicationInfo.nativeLibraryDir by Android's package
 * installer. Guest Wine binaries remain data files and are loaded by Box64, not execve'd
 * directly by Android.
 */
object RuntimeExecutionManager {
    data class Result(
        val success: Boolean,
        val stage: String,
        val exitCode: Int?,
        val output: String
    )

    fun engineStatus(context: Context): String {
        val engine = bundledBox64(context)
        return when {
            !engine.exists() -> "BOX64 ENGINE MISSING: libbox64.so was not bundled in this APK."
            !engine.isFile -> "BOX64 ENGINE INVALID: packaged engine path is not a regular file."
            !engine.canExecute() -> "BOX64 ENGINE NOT EXECUTABLE: Android did not extract executable native libraries."
            else -> "BOX64 ENGINE FOUND: ${engine.absolutePath}"
        }
    }

    fun probeWine(context: Context, runtime: RuntimePackageManager.RuntimeInfo): Result {
        val prefix = File(File(context.filesDir, "pcgl/diagnostics"), "wine-probe")
        return runGuestCommand(
            context = context,
            runtime = runtime,
            prefix = prefix,
            guestArguments = listOf("--version"),
            logName = "wine-version.log",
            timeoutSeconds = 20
        )
    }

    fun initializeWinePrefix(
        context: Context,
        runtime: RuntimePackageManager.RuntimeInfo,
        prefix: File
    ): Result {
        val containerRoot = File(context.filesDir, "pcgl/containers").canonicalFile
        val target = prefix.canonicalFile
        if (!target.path.startsWith(containerRoot.path + File.separator)) {
            return Result(
                false,
                "Prefix validation",
                null,
                "Direktori prefix harus berada di direktori container aplikasi."
            )
        }

        return runGuestCommand(
            context = context,
            runtime = runtime,
            prefix = target,
            guestArguments = listOf(runtime.winebootExe.absolutePath, "-u"),
            logName = "wineboot.log",
            timeoutSeconds = 180
        )
    }

    private fun runGuestCommand(
        context: Context,
        runtime: RuntimePackageManager.RuntimeInfo,
        prefix: File,
        guestArguments: List<String>,
        logName: String,
        timeoutSeconds: Long
    ): Result {
        val engine = bundledBox64(context)
        if (!engine.isFile || !engine.canExecute()) {
            return Result(false, "Box64 availability", null, engineStatus(context))
        }

        val expectedParent = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        if (engine.canonicalFile.parentFile != expectedParent) {
            return Result(
                false,
                "Box64 location",
                null,
                "Box64 ditolak: engine harus berasal dari nativeLibraryDir APK."
            )
        }

        val home = File(prefix, "home")
        val temp = File(prefix, "temp")
        val logs = File(prefix, "logs")
        listOf(home, temp, logs).forEach {
            if (!it.exists() && !it.mkdirs()) {
                return Result(false, "Workspace", null, "Tidak dapat membuat ${it.absolutePath}")
            }
        }

        val logFile = File(logs, logName)
        val command = ArrayList<String>()
        command.add(engine.absolutePath)
        command.add(runtime.wine64.absolutePath)
        command.addAll(guestArguments)

        return try {
            val builder = ProcessBuilder(command)
                .directory(runtime.root)
                .redirectErrorStream(true)
                .redirectOutput(logFile)

            builder.environment().apply {
                put("HOME", home.absolutePath)
                put("TMPDIR", temp.absolutePath)
                put("WINEPREFIX", prefix.absolutePath)
                put("WINEDEBUG", "-all")
                put("BOX64_NOBANNER", "1")
                if (runtime.libraryPaths.isNotEmpty()) {
                    put(
                        "BOX64_LD_LIBRARY_PATH",
                        runtime.libraryPaths.joinToString(File.pathSeparator) { it.absolutePath }
                    )
                }
            }

            val process = builder.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
                return Result(
                    false,
                    "Process timeout",
                    null,
                    "Proses melewati batas waktu ${timeoutSeconds} detik. Log: ${logFile.absolutePath}"
                )
            }

            val exitCode = process.exitValue()
            val output = readLogTail(logFile)
            Result(
                success = exitCode == 0,
                stage = if (guestArguments.firstOrNull() == "--version") "Wine version probe" else "Wine prefix initialization",
                exitCode = exitCode,
                output = output.ifBlank {
                    if (exitCode == 0) "Proses selesai tanpa output." else "Proses keluar tanpa pesan. Periksa paket Wine dan library x86_64."
                }
            )
        } catch (error: IOException) {
            Result(
                false,
                "Process launch",
                null,
                "Android gagal menjalankan Box64: ${error.message ?: "I/O error"}. " +
                    "Pastikan libbox64.so dikemas di APK dan diekstrak ke nativeLibraryDir."
            )
        } catch (error: Exception) {
            Result(
                false,
                "Runtime",
                null,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun bundledBox64(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libbox64.so")

    private fun readLogTail(file: File): String {
        if (!file.isFile) return ""
        return try {
            val bytes = file.readBytes()
            val start = (bytes.size - MAX_LOG_BYTES).coerceAtLeast(0)
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
                .replace("\u0000", "")
        } catch (_: Exception) {
            "Tidak dapat membaca log proses."
        }
    }

    private const val MAX_LOG_BYTES = 16 * 1024
}
