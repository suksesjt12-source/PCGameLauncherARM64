package com.suksesjt12.pcgamelauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.suksesjt12.pcgamelauncher.x11.PcglX11ProtocolSelfTest
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
    private lateinit var uploadTestButton: Button
    private lateinit var clearFrameButton: Button
    private val displayBackend: PcglDisplayBackend = PcglEglSurfaceBackend()

    @Volatile private var displayReady = false
    @Volatile private var activityResumed = false
    @Volatile private var surfaceAvailable = false
    @Volatile private var x11TestRunning = false
    @Volatile private var frameTestRunning = false
    private var frameIndex = 0L

    private val frameTask = object : Runnable {
        override fun run() {
            if (!displayReady || isFinishing || isDestroyed) return
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

        uploadTestButton = Button(this).apply {
            text = "TEST RGBA WINDOW FRAME UPLOAD"
            setOnClickListener { runWindowFrameUploadTest() }
        }
        footer.addView(uploadTestButton)

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
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width < 1 || height < 1) return
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
        renderHandler.post { NativeBridge.destroyDisplaySurface() }
        renderThread.quitSafely()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
