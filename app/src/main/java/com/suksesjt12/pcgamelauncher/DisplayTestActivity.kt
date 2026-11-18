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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PCGL-owned Android Surface/EGL proof of concept.
 * It renders diagnostic frames only; it is not connected to Wine yet.
 */
class DisplayTestActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler
    private lateinit var statusView: TextView
    private lateinit var frameView: TextView

    @Volatile private var displayReady = false
    private var frameIndex = 0L

    private val frameTask = object : Runnable {
        override fun run() {
            if (!displayReady || isFinishing || isDestroyed) return
            val ok = try { NativeBridge.renderDisplayFrame(frameIndex) } catch (_: Throwable) { false }
            if (!ok) {
                displayReady = false
                val details = runCatching { NativeBridge.displayInfo() }.getOrDefault("Native display bridge unavailable.")
                runOnUiThread {
                    statusView.text = "RENDER STOPPED\n" + details
                    frameView.text = "Frame submission stopped."
                }
                return
            }
            frameIndex += 1L
            if (frameIndex % 30L == 0L) {
                runOnUiThread {
                    if (!isDestroyed) frameView.text = "Frames submitted: " + frameIndex + "\n" + NativeBridge.displayInfo()
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
        footer.addView(Button(this).apply {
            text = "CLOSE DISPLAY TEST"
            setOnClickListener { finish() }
        })
        root.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        val width = holder.surfaceFrame.width().coerceAtLeast(1)
        val height = holder.surfaceFrame.height().coerceAtLeast(1)
        renderHandler.post {
            val result = try {
                NativeBridge.createDisplaySurface(surface, width, height)
            } catch (error: Throwable) {
                "ERROR: " + (error.message ?: error.javaClass.simpleName)
            }
            displayReady = result.startsWith("READY:")
            runOnUiThread {
                if (!isDestroyed) {
                    statusView.text = "PCGL NATIVE DISPLAY\n" + result
                    frameView.text = if (displayReady) {
                        "EGL context active. Rendering PCGL diagnostic frames at approximately 30 FPS. This is not a Windows game image."
                    } else {
                        "Initialization failed. Review the status above."
                    }
                }
            }
            if (displayReady) {
                frameIndex = 0L
                renderHandler.removeCallbacks(frameTask)
                renderHandler.post(frameTask)
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width < 1 || height < 1) return
        renderHandler.post {
            if (displayReady && !NativeBridge.resizeDisplaySurface(width, height)) {
                val details = NativeBridge.displayInfo()
                runOnUiThread {
                    if (!isDestroyed) statusView.text = "PCGL RESIZE FAILED\n" + details
                }
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        displayReady = false
        renderHandler.removeCallbacks(frameTask)
        val latch = CountDownLatch(1)
        renderHandler.post {
            try { NativeBridge.destroyDisplaySurface() } finally { latch.countDown() }
        }
        try { latch.await(1500L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun onPause() {
        renderHandler.removeCallbacks(frameTask)
        super.onPause()
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
