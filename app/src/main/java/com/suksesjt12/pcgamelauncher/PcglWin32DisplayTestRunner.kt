package com.suksesjt12.pcgamelauncher

import android.content.Context
import com.suksesjt12.pcgamelauncher.x11.PcglX11DisplaySession
import java.io.File
import java.io.FileOutputStream

/** Installs and launches the APK-bundled Win32 GDI display smoke test. */
object PcglWin32DisplayTestRunner {
    data class Result(
        val success: Boolean,
        val message: String,
        val logFile: File?
    )

    fun run(
        context: Context,
        width: Int,
        height: Int,
        onStage: (String) -> Unit = {},
        frameSink: (PcglWindowFrame) -> Unit
    ): Result {
        var displayStarted = false
        var expectedLog: File? = null
        return try {
            val runtime = RuntimeBootstrapManager.findInstalled(context.filesDir)
                ?: RuntimePackageManager.findInstalled(context.filesDir)
                ?: return Result(
                    false,
                    "Wine runtime belum terpasang. Jalankan INSTALL RUNTIME COMPONENTS AUTOMATICALLY terlebih dahulu.",
                    null
                )

            onStage("Creating the dedicated diagnostic Wine workspace...")
            val profile = GameContainerManager.create(
                context,
                "PCGL Win32 Display Test",
                "pcgl://diagnostics/win32-display-test"
            )

            if (!GameContainerManager.isPrefixInitialized(profile)) {
                onStage("Initializing the diagnostic Wine prefix...")
                val prefixResult = RuntimeExecutionManager.initializeWinePrefix(
                    context = context,
                    runtime = runtime,
                    prefix = profile.directory
                )
                GameContainerManager.setPrefixState(
                    profile,
                    if (prefixResult.success) {
                        "wine_prefix_initialized"
                    } else {
                        "wine_prefix_initialization_failed"
                    }
                )
                if (!prefixResult.success) {
                    val log = File(profile.directory, "logs/wineboot.log")
                    return Result(
                        false,
                        "Wine prefix initialization failed at ${prefixResult.stage}. ${prefixResult.output}",
                        log.takeIf { it.isFile }
                    )
                }
            }

            onStage("Installing and validating the bundled Win32 GUI test...")
            val driveC = File(profile.directory, "drive_c").canonicalFile
            val destination = File(driveC, "PCGL/pcgl-display-test.exe").canonicalFile
            require(destination.path.startsWith(driveC.path + File.separator)) {
                "The diagnostic executable path escaped the game drive."
            }
            val parent = destination.parentFile ?: error("Invalid diagnostic executable path.")
            if (!parent.exists() && !parent.mkdirs()) {
                error("Unable to create the diagnostic executable directory.")
            }

            val temporary = File(parent, "pcgl-display-test.exe.tmp")
            context.assets.open("pcgl-display-test.exe").use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (RuntimePackageManager.inspectWindowsExecutable(temporary) != "x86_64") {
                temporary.delete()
                error("The bundled display test is not a valid PE32+ x86_64 executable.")
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete()
                error("Unable to replace the previous diagnostic executable.")
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                error("Unable to stage the diagnostic executable into the Wine prefix.")
            }
            GameContainerManager.recordExecutable(profile, destination)

            onStage("Starting the authenticated PCGL X11 display session...")
            val session = PcglX11DisplaySession.start(
                context = context,
                prefix = profile.directory,
                width = width,
                height = height,
                frameSink = frameSink
            )
            displayStarted = true

            onStage("Launching the Win32 GDI window through Box64/Wine...")
            val launch = RuntimeExecutionManager.launchWindowsGame(
                context = context,
                runtime = runtime,
                profile = profile,
                executable = destination,
                arguments = emptyList()
            )
            expectedLog = launch.logFile
            if (!launch.success) {
                PcglX11DisplaySession.stop()
                displayStarted = false
                Result(false, launch.message, launch.logFile)
            } else {
                Result(
                    true,
                    "Box64/Wine process started with DISPLAY=${session.displayEndpoint}. This is not yet proof that the window was rendered.",
                    launch.logFile
                )
            }
        } catch (error: Exception) {
            if (displayStarted) PcglX11DisplaySession.stop()
            Result(
                false,
                error.message ?: error.javaClass.simpleName,
                expectedLog?.takeIf { it.isFile }
            )
        }
    }
}
