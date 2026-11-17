package com.suksesjt12.pcgamelauncher

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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

    data class LaunchResult(
        val success: Boolean,
        val sessionId: String?,
        val logFile: File?,
        val message: String
    )

    private val gameProcessLock = Any()
    @Volatile private var activeGameProcess: Process? = null
    @Volatile private var activeGameLog: File? = null
    @Volatile private var lastGameExitCode: Int? = null

    fun isGameRunning(): Boolean = activeGameProcess?.isAlive == true

    fun lastGameLog(): File? = activeGameLog

    fun lastGameExitCode(): Int? = lastGameExitCode

    fun launchWindowsGame(
        context: Context,
        runtime: RuntimePackageManager.RuntimeInfo,
        profile: GameContainerManager.Profile,
        executable: File,
        arguments: List<String>
    ): LaunchResult {
        val engine = bundledBox64(context)
        if (!engine.isFile || !engine.canExecute()) {
            return LaunchResult(false, null, null, engineStatus(context))
        }

        val expectedNativeDir = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        if (engine.canonicalFile.parentFile != expectedNativeDir) {
            return LaunchResult(false, null, null, "Box64 tidak berasal dari nativeLibraryDir APK.")
        }

        if (!GameContainerManager.isPrefixInitialized(profile)) {
            return LaunchResult(false, null, null, "Wine prefix belum diinisialisasi.")
        }

        val prefixRoot = profile.directory.canonicalFile
        val driveC = File(prefixRoot, "drive_c").canonicalFile
        val targetExe = executable.canonicalFile
        if (!targetExe.isFile ||
            !targetExe.path.startsWith(driveC.path + File.separator)
        ) {
            return LaunchResult(false, null, null, "EXE harus berada di dalam drive_c container game.")
        }

        if (arguments.size > 128 || arguments.any { it.length > 8192 } ||
            arguments.sumOf { it.length } > 32768
        ) {
            return LaunchResult(false, null, null, "Argumen peluncuran terlalu panjang.")
        }

        synchronized(gameProcessLock) {
            if (activeGameProcess?.isAlive == true) {
                return LaunchResult(false, null, activeGameLog, "Game lain sedang berjalan. Hentikan game aktif terlebih dahulu.")
            }
        }

        val home = File(prefixRoot, "home")
        val temp = File(prefixRoot, "temp")
        val logs = File(prefixRoot, "logs")
        listOf(home, temp, logs).forEach {
            if (!it.exists() && !it.mkdirs()) {
                return LaunchResult(false, null, null, "Tidak dapat membuat direktori ${it.absolutePath}")
            }
        }

        val logFile = File(logs, "game-last-run.log")
        val command = ArrayList<String>()
        command.add(engine.absolutePath)
        command.add(runtime.wine64.absolutePath)
        command.add(targetExe.absolutePath)
        command.addAll(arguments)

        return try {
            val builder = ProcessBuilder(command)
                .directory(targetExe.parentFile)
                .redirectErrorStream(true)
                .redirectOutput(logFile)

            builder.environment().apply {
                put("HOME", home.absolutePath)
                put("TMPDIR", temp.absolutePath)
                put("WINEPREFIX", prefixRoot.absolutePath)
                put("WINEDEBUG", "-all")
                put("BOX64_NOBANNER", "1")
                put("PATH", runtime.wine64.parentFile.absolutePath + File.pathSeparator + (get("PATH") ?: ""))
                if (runtime.libraryPaths.isNotEmpty()) {
                    put(
                        "BOX64_LD_LIBRARY_PATH",
                        runtime.libraryPaths.joinToString(File.pathSeparator) { it.absolutePath }
                    )
                }
            }

            val process = builder.start()
            synchronized(gameProcessLock) {
                activeGameProcess = process
                activeGameLog = logFile
                lastGameExitCode = null
            }

            Thread {
                try {
                    val exitCode = process.waitFor()
                    synchronized(gameProcessLock) {
                        lastGameExitCode = exitCode
                        if (activeGameProcess === process) activeGameProcess = null
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.apply {
                name = "PCGL-Game-Watcher"
                isDaemon = true
                start()
            }

            LaunchResult(
                true,
                System.currentTimeMillis().toString(),
                logFile,
                "Box64 process berhasil dimulai. Ini belum menjamin game berhasil masuk ke menu."
            )
        } catch (error: IOException) {
            LaunchResult(
                false,
                null,
                logFile,
                "Gagal memulai proses Box64/Wine: ${error.message ?: "I/O error"}"
            )
        } catch (error: Exception) {
            LaunchResult(false, null, logFile, error.message ?: error.javaClass.simpleName)
        }
    }

    fun stopWindowsGame(
        context: Context,
        runtime: RuntimePackageManager.RuntimeInfo
    ): Result {
        val process = activeGameProcess
            ?: return Result(false, "Stop game", null, "Tidak ada proses game aktif.")

        val prefix = synchronized(gameProcessLock) {
            activeGameLog?.parentFile?.parentFile
        } ?: return Result(false, "Stop game", null, "Direktori log game tidak tersedia.")

        val engine = bundledBox64(context)
        val stopLog = File(File(prefix, "logs"), "wineserver-stop.log")
        try {
            if (engine.isFile && engine.canExecute()) {
                val killer = ProcessBuilder(
                    engine.absolutePath,
                    runtime.wineserver.absolutePath,
                    "-k"
                ).directory(runtime.root)
                    .redirectErrorStream(true)
                    .redirectOutput(stopLog)

                killer.environment().apply {
                    put("HOME", File(prefix, "home").absolutePath)
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
                val serverStop = killer.start()
                serverStop.waitFor(8, TimeUnit.SECONDS)
                if (serverStop.isAlive) serverStop.destroyForcibly()
            }
        } catch (_: Exception) {
            // Fall back to terminating the launcher process below.
        }

        return try {
            if (process.isAlive) process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS) && process.isAlive) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            val stopped = !process.isAlive
            if (stopped) {
                synchronized(gameProcessLock) {
                    if (activeGameProcess === process) activeGameProcess = null
                }
            }
            Result(stopped, "Stop game", null, if (stopped) "Permintaan penghentian dikirim." else "Proses belum berhenti.")
        } catch (error: Exception) {
            Result(false, "Stop game", null, error.message ?: error.javaClass.simpleName)
        }
    }

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
            RandomAccessFile(file, "r").use { input ->
                val start = (input.length() - MAX_LOG_BYTES).coerceAtLeast(0L)
                val count = (input.length() - start).toInt()
                val bytes = ByteArray(count)
                input.seek(start)
                input.readFully(bytes)
                String(bytes, Charsets.UTF_8).filter { it.code != 0 }
            }
        } catch (_: Exception) {
            "Tidak dapat membaca log proses."
        }
    }

    private const val MAX_LOG_BYTES = 16 * 1024
}
