package com.suksesjt12.pcgamelauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.suksesjt12.pcgamelauncher.x11.PcglX11ProtocolSelfTest
import com.suksesjt12.pcgamelauncher.x11.PcglX11TcpProtocolSelfTest
import com.suksesjt12.pcgamelauncher.x11.PcglX11DisplaySession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** PCGL-owned Android Surface/EGL diagnostic screen with a local X11 protocol test. */
class DisplayTestActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler
    private lateinit var statusView: TextView
    private lateinit var frameView: TextView
    private lateinit var x11TestButton: Button
    private lateinit var tcpX11TestButton: Button
    private lateinit var startX11SessionButton: Button
    private lateinit var stopX11SessionButton: Button
    private lateinit var uploadTestButton: Button
    private lateinit var clearFrameButton: Button
    private lateinit var win32TestButton: Button
    private val displayBackend: PcglDisplayBackend = PcglEglSurfaceBackend()

    @Volatile private var displayReady = false
    @Volatile private var activityResumed = false
    @Volatile private var surfaceAvailable = false
    @Volatile private var x11TestRunning = false
    @Volatile private var frameTestRunning = false
    @Volatile private var win32TestRunning = false
    private var frameIndex = 0L
    @Volatile private var surfaceWidth = 1280
    @Volatile private var surfaceHeight = 720
    @Volatile private var pendingGameLaunchStarted = false
    private val pendingX11Frame = java.util.concurrent.atomic.AtomicReference<PcglWindowFrame?>(null)
    private val uiHandler = Handler(Looper.getMainLooper())

    private val frameTask = object : Runnable {
        override fun run() {
            if (!displayReady || isFinishing || isDestroyed) return
            pendingX11Frame.getAndSet(null)?.let { frame ->
                if (!displayBackend.submitWindowFrame(frame)) {
                    runOnUiThread {
                        if (!isDestroyed) statusView.text = "X11 FRAME UPLOAD FAILED\\n" + displayBackend.details()
                    }
                }
            }
            val ok = try { displayBackend.renderFrame(frameIndex) } catch (_: Throwable) { false }
            if (!ok) {
                displayReady = false
                val details = runCatching { displayBackend.details() }
                    .getOrDefault("Native display bridge unavailable.")
                runOnUiThread {
                    statusView.text = "RENDER STOPPED\n" + details
                    frameView.text = "Frame submission stopped."
                }
                return
            }
            frameIndex += 1L
            if (frameIndex % 30L == 0L) {
                runOnUiThread {
                    if (!isDestroyed) {
                        frameView.text = "Frames submitted: " + frameIndex + "\n" + NativeBridge.displayInfo()
                    }
                }
            }
            renderHandler.postDelayed(this, 33L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        renderThread = HandlerThread("PCGL-EGL-Renderer").also { it.start() }
        renderHandler = Handler(renderThread.looper)
        val root = FrameLayout(this)
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))

        statusView = TextView(this).apply {
            text = "PCGL NATIVE DISPLAY\nWaiting for Android Surface..."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xCC101827.toInt())
        }
        root.addView(statusView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xD9101725.toInt())
        }
        frameView = TextView(this).apply {
            text = "OpenGL ES diagnostic renderer is starting."
            textSize = 11f
            setTextColor(Color.rgb(205, 218, 237))
        }
        footer.addView(frameView)
        x11TestButton = Button(this).apply {
            text = "TEST X11 WINDOW + GAME FRAME PIPELINE"
            setOnClickListener { runX11ProtocolSelfTest() }
        }
        footer.addView(x11TestButton)

        tcpX11TestButton = Button(this).apply {
            text = "TEST AUTHENTICATED X11 TCP :0"
            setOnClickListener { runX11TcpProtocolSelfTest() }
        }
        footer.addView(tcpX11TestButton)

        startX11SessionButton = Button(this).apply {
            text = "START X11 DISPLAY FOR WINE"
            setOnClickListener { startDiagnosticX11Session() }
        }
        footer.addView(startX11SessionButton)

        stopX11SessionButton = Button(this).apply {
            text = "STOP X11 DISPLAY SESSION"
            setOnClickListener { stopX11Session() }
        }
        footer.addView(stopX11SessionButton)

        uploadTestButton = Button(this).apply {
            text = "TEST RGBA WINDOW FRAME UPLOAD"
            setOnClickListener { runWindowFrameUploadTest() }
        }
        footer.addView(uploadTestButton)

        win32TestButton = Button(this).apply {
            text = "RUN BUNDLED WIN32 GUI TEST"
            setOnClickListener { runBundledWin32GuiTest() }
        }
        footer.addView(win32TestButton)

        clearFrameButton = Button(this).apply {
            text = "CLEAR FRAME / SHOW DIAGNOSTIC"
            setOnClickListener {
                renderHandler.post {
                    val cleared = displayBackend.clearWindowFrame()
                    runOnUiThread {
                        if (!isDestroyed) {
                            statusView.text = if (cleared) {
                                "PCGL FRAME BRIDGE\\nFrame texture cleared."
                            } else {
                                "PCGL FRAME BRIDGE\\nUnable to clear frame texture."
                            }
                        }
                    }
                }
            }
        }
        footer.addView(clearFrameButton)

        footer.addView(Button(this).apply {
            text = "CLOSE DISPLAY TEST"
            setOnClickListener { finish() }
        })
        root.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        setContentView(root)
    }

    private val gameStatusPoll = object : Runnable {
        override fun run() {
            if (isDestroyed || !pendingGameLaunchStarted) return
            if (RuntimeExecutionManager.isGameRunning()) {
                uiHandler.postDelayed(this, 1000L)
            } else {
                pendingGameLaunchStarted = false
                ControllerOverlayManager.hide()
                win32TestButton.isEnabled = true
                statusView.text = "GAME PROCESS EXITED\\nExit code: " +
                    (RuntimeExecutionManager.lastGameExitCode()?.toString() ?: "unknown") +
                    "\\nCheck the per-game log for the Wine/X11 result."
                frameView.text = "X11 display session stopped after the game process exited. The exit code does not prove that a visible window was created."
            }
        }
    }

    private fun startDiagnosticX11Session() {
        if (!displayReady) {
            statusView.text = "X11 DISPLAY\\nAndroid Surface/EGL is not ready."
            return
        }
        if (PcglX11DisplaySession.isRunning()) {
            statusView.text = "X11 DISPLAY\\nAn authenticated PCGL X11 session is already active."
            return
        }
        try {
            val workspace = File(filesDir, "pcgl/containers/.x11-diagnostic")
            val session = PcglX11DisplaySession.start(
                context = this,
                prefix = workspace,
                width = surfaceWidth,
                height = surfaceHeight
            ) { frame -> pendingX11Frame.set(frame) }
            statusView.text = "AUTHENTICATED X11 SERVER ACTIVE\\nDISPLAY=${session.displayEndpoint}"
            frameView.text = "XAUTHORITY: ${session.xAuthorityFile.absolutePath}\\nLoopback only; this test server is not a complete accelerated game renderer."
        } catch (error: Exception) {
            PcglX11DisplaySession.stop()
            statusView.text = "X11 START FAILED\\n" + (error.message ?: error.javaClass.simpleName)
        }
    }

    private fun stopX11Session() {
        if (RuntimeExecutionManager.isGameRunning()) {
            statusView.text = "STOP THE GAME PROCESS BEFORE STOPPING ITS DISPLAY."
            return
        }
        PcglX11DisplaySession.stop()
        pendingX11Frame.set(null)
        statusView.text = "PCGL X11 DISPLAY STOPPED"
        frameView.text = NativeBridge.displayInfo()
    }

    private fun startPendingGameIfRequested() {
        if (pendingGameLaunchStarted) return
        val request = PcglPendingGameLaunch.take() ?: return
        pendingGameLaunchStarted = true
        statusView.text = "PREPARING GAME DISPLAY\\nStarting authenticated X11 session..."
        Thread {
            var session: PcglX11DisplaySession.Session? = null
            val launch = try {
                session = PcglX11DisplaySession.start(
                    context = applicationContext,
                    prefix = request.profile.directory,
                    width = surfaceWidth,
                    height = surfaceHeight
                ) { frame -> pendingX11Frame.set(frame) }
                RuntimeExecutionManager.launchWindowsGame(
                    context = applicationContext,
                    runtime = request.runtime,
                    profile = request.profile,
                    executable = request.executable,
                    arguments = request.arguments
                )
            } catch (error: Exception) {
                RuntimeExecutionManager.LaunchResult(
                    false, null, null, error.message ?: error.javaClass.simpleName
                )
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!launch.success) {
                    PcglX11DisplaySession.stop()
                    pendingGameLaunchStarted = false
                    statusView.text = "GAME LAUNCH FAILED\\n" + launch.message
                    frameView.text = launch.logFile?.absolutePath ?: "No game log was created."
                    return@runOnUiThread
                }

                val activeDisplay = session
                statusView.text = "GAME PROCESS STARTED\\n${request.gameName}\\n" + launch.message
                frameView.text = "Executable: ${request.executable.name}\\nLog: ${launch.logFile?.absolutePath ?: "not available"}"
                if (activeDisplay != null) {
                    frameView.append("\\nDISPLAY=${activeDisplay.displayEndpoint}")
                }
                if (android.provider.Settings.canDrawOverlays(this)) {
                    ControllerOverlayManager.show(this, RuntimeExecutionManager.controllerSettings())
                } else {
                    frameView.append("\\nAllow display-over-other-apps to use the touch controls.")
                }
                uiHandler.removeCallbacks(gameStatusPoll)
                uiHandler.postDelayed(gameStatusPoll, 1000L)
            }
        }.apply {
            name = "PCGL-Authenticated-Game-Launch"
            isDaemon = true
            start()
        }
    }

    private fun runBundledWin32GuiTest() {
        if (!displayReady || isFinishing || isDestroyed) {
            statusView.text = "WIN32 GUI TEST\nWait until the Android EGL Surface is ready."
            return
        }
        if (win32TestRunning || pendingGameLaunchStarted ||
            RuntimeExecutionManager.isGameRunning() || x11TestRunning || frameTestRunning
        ) {
            statusView.text = "WIN32 GUI TEST\nAnother game or diagnostic test is already running."
            return
        }
        if (PcglX11DisplaySession.isRunning()) {
            statusView.text = "WIN32 GUI TEST\nStop the existing X11 session before starting this test."
            return
        }

        win32TestRunning = true
        win32TestButton.isEnabled = false
        statusView.text = "WIN32 GUI TEST\nPreparing Wine prefix and authenticated X11 display..."
        frameView.text = "This test launches the bundled GDI test window through Box64/Wine. Process start alone does not confirm visible rendering."

        Thread {
            val result = PcglWin32DisplayTestRunner.run(
                context = applicationContext,
                width = surfaceWidth,
                height = surfaceHeight,
                onStage = { stage ->
                    runOnUiThread {
                        if (!isDestroyed) statusView.text = "WIN32 GUI TEST\n$stage"
                    }
                },
                frameSink = { frame -> pendingX11Frame.set(frame) }
            )

            runOnUiThread {
                win32TestRunning = false
                if (isDestroyed) return@runOnUiThread
                if (!result.success) {
                    win32TestButton.isEnabled = true
                    PcglX11DisplaySession.stop()
                    pendingX11Frame.set(null)
                    statusView.text = "WIN32 GUI TEST FAILED\n${result.message}"
                    frameView.text = result.logFile?.absolutePath ?: "No test log was created."
                    return@runOnUiThread
                }

                pendingGameLaunchStarted = true
                statusView.text = "WIN32 GUI TEST PROCESS STARTED\n${result.message}"
                frameView.text = "Log: ${result.logFile?.absolutePath ?: "not available"}\nObserve the display area for the PCGL test window. A running process is not proof that X11 accepted or displayed the window."
                if (android.provider.Settings.canDrawOverlays(this)) {
                    ControllerOverlayManager.show(this, RuntimeExecutionManager.controllerSettings())
                } else {
                    frameView.append("\nAllow display-over-other-apps to use the touch controls.")
                }
                uiHandler.removeCallbacks(gameStatusPoll)
                uiHandler.postDelayed(gameStatusPoll, 1000L)
            }
        }.apply {
            name = "PCGL-Win32-GUI-Display-Test"
            isDaemon = true
            start()
        }
    }

    private fun runX11ProtocolSelfTest() {
        if (x11TestRunning) return
        x11TestRunning = true
        x11TestButton.isEnabled = false
        statusView.text = "PCGL X11 PROTOCOL\nStarting local AF_UNIX setup test..."
        Thread {
            val result = PcglX11ProtocolSelfTest.run(File(filesDir, "pcgl/x11-protocol-test")) { frame ->
                // EGL context ownership stays on the renderer thread; X11 callbacks only enqueue frames.
                renderHandler.post {
                    if (displayReady && !isDestroyed) {
                        val uploaded = displayBackend.submitWindowFrame(frame)
                        if (!uploaded) {
                            runOnUiThread {
                                if (!isDestroyed) statusView.text = "X11 FRAME UPLOAD FAILED\\n" + displayBackend.details()
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                x11TestRunning = false
                if (!isDestroyed) {
                    x11TestButton.isEnabled = true
                    statusView.text = "PCGL X11 PROTOCOL\n" + result.details
                }
            }
        }.apply {
            name = "PCGL-X11-SelfTest"
            isDaemon = true
            start()
        }
    }

    private fun runX11TcpProtocolSelfTest() {
        if (x11TestRunning) return
        if (PcglX11DisplaySession.isRunning()) {
            statusView.text = "PCGL X11 TCP\\nStop the active display session before running the temporary server test."
            return
        }
        x11TestRunning = true
        tcpX11TestButton.isEnabled = false
        statusView.text = "PCGL X11 TCP\\nStarting authenticated loopback display test..."
        Thread {
            val result = PcglX11TcpProtocolSelfTest.run(File(filesDir, "pcgl/x11-tcp-protocol-test")) { frame ->
                // The protocol thread never touches EGL; the renderer thread owns the context.
                renderHandler.post {
                    if (displayReady && !isDestroyed) {
                        val uploaded = displayBackend.submitWindowFrame(frame)
                        if (!uploaded) {
                            runOnUiThread {
                                if (!isDestroyed) statusView.text =
                                    "X11 TCP FRAME UPLOAD FAILED\\n" + displayBackend.details()
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                x11TestRunning = false
                if (!isDestroyed) {
                    tcpX11TestButton.isEnabled = true
                    statusView.text = "PCGL X11 TCP\\n" + result.details +
                        if (displayReady) "\\nFrame callback was routed to the Android EGL renderer." else
                            "\\nEGL Surface is not active; protocol output was not displayed."
                }
            }
        }.apply {
            name = "PCGL-X11-TCP-SelfTest"
            isDaemon = true
            start()
        }
    }

    private fun runWindowFrameUploadTest() {
        if (!displayReady || frameTestRunning) return
        frameTestRunning = true
        uploadTestButton.isEnabled = false
        renderHandler.post {
            val width = 320
            val height = 180
            val pixels = ByteArray(width * height * 4)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = (y * width + x) * 4
                    val bar = (x / 40) % 8
                    pixels[index] = when (bar) {
                        0, 1 -> 255.toByte()
                        2, 3 -> 32
                        4, 5 -> 32
                        else -> 220.toByte()
                    }
                    pixels[index + 1] = ((y * 255) / (height - 1)).toByte()
                    pixels[index + 2] = ((x * 255) / (width - 1)).toByte()
                    pixels[index + 3] = 255.toByte()
                }
            }
            val frame = runCatching { PcglWindowFrame(width, height, pixels) }.getOrNull()
            val accepted = frame != null && displayBackend.submitWindowFrame(frame)
            runOnUiThread {
                frameTestRunning = false
                if (!isDestroyed) {
                    uploadTestButton.isEnabled = true
                    statusView.text = if (accepted) {
                        "PCGL FRAME BRIDGE\\nPASS: 320x180 RGBA frame uploaded to the EGL texture."
                    } else {
                        "PCGL FRAME BRIDGE\\nFAIL: EGL texture rejected the RGBA frame."
                    }
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        val surface = holder.surface
        val width = holder.surfaceFrame.width().coerceAtLeast(1)
        val height = holder.surfaceFrame.height().coerceAtLeast(1)
        surfaceWidth = width
        surfaceHeight = height
        renderHandler.post {
            val result = try {
                displayBackend.attach(surface, width, height).details
            } catch (error: Throwable) {
                "ERROR: " + (error.message ?: error.javaClass.simpleName)
            }
            displayReady = result.startsWith("READY:")
            runOnUiThread {
                if (!isDestroyed) {
                    statusView.text = "PCGL NATIVE DISPLAY\n" + result
                    frameView.text = if (displayReady) {
                        "EGL context active. X11 core framebuffer test is available; this screen is still a protocol/renderer test, not proof of a Windows game."
                    } else {
                        "Initialization failed. Review the status above."
                    }
                }
            }
            if (displayReady) {
                frameIndex = 0L
                renderHandler.removeCallbacks(frameTask)
                startRenderingIfPossible()
                runOnUiThread { startPendingGameIfRequested() }
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width < 1 || height < 1) return
        surfaceWidth = width
        surfaceHeight = height
        renderHandler.post {
            if (displayReady && !displayBackend.resize(width, height)) {
                val details = NativeBridge.displayInfo()
                runOnUiThread {
                    if (!isDestroyed) statusView.text = "PCGL RESIZE FAILED\n" + details
                }
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        displayReady = false
        renderHandler.removeCallbacks(frameTask)
        val latch = CountDownLatch(1)
        renderHandler.post {
            try { displayBackend.detach() } finally { latch.countDown() }
        }
        try {
            latch.await(1500L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        startRenderingIfPossible()
    }

    override fun onPause() {
        activityResumed = false
        renderHandler.removeCallbacks(frameTask)
        super.onPause()
    }

    private fun startRenderingIfPossible() {
        if (!activityResumed || !surfaceAvailable || !displayReady) return
        renderHandler.removeCallbacks(frameTask)
        renderHandler.post(frameTask)
    }

    override fun onDestroy() {
        displayReady = false
        renderHandler.removeCallbacks(frameTask)
        uiHandler.removeCallbacks(gameStatusPoll)
        PcglPendingGameLaunch.clear()
        if (RuntimeExecutionManager.isGameRunning()) {
            val runtime = RuntimeBootstrapManager.findInstalled(filesDir)
                ?: RuntimePackageManager.findInstalled(filesDir)
            if (runtime != null) {
                Thread {
                    runCatching {
                        RuntimeExecutionManager.stopWindowsGame(applicationContext, runtime)
                    }
                }.apply {
                    name = "PCGL-Display-Activity-Cleanup"
                    isDaemon = true
                    start()
                }
            }
        }
        PcglX11DisplaySession.stop()
        ControllerOverlayManager.hide()
        renderHandler.post { NativeBridge.destroyDisplaySurface() }
        renderThread.quitSafely()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
