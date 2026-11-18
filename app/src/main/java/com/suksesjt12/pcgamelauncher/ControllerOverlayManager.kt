package com.suksesjt12.pcgamelauncher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Floating touch controls mapped to keyboard virtual-key codes, not SDL/XInput. */
object ControllerOverlayManager {
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private val pressedKeys = linkedSetOf<Int>()

    fun isShowing(): Boolean = rootView != null

    fun show(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (rootView != null) return true

        val app = context.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(app, 8), dp(app, 6), dp(app, 8), dp(app, 8))
            background = rounded(Color.argb(184, 10, 15, 26), dp(app, 12))
        }
        val header = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(app).apply {
            text = "PCGL INPUT • KEYBOARD MAP"
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(dp(app, 4), dp(app, 3), dp(app, 12), dp(app, 5))
            layoutParams = LinearLayout.LayoutParams(0, dp(app, 24), 1f)
        }
        val close = TextView(app).apply {
            text = "HIDE"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(120, 45, 48), dp(app, 6))
            setPadding(dp(app, 8), 0, dp(app, 8), 0)
            setOnClickListener { hide() }
        }
        header.addView(title)
        header.addView(close)
        root.addView(header)

        val body = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dpad = GridLayout(app).apply { rowCount = 3; columnCount = 3 }
        addKey(dpad, app, "W", 0x57, 0, 1)
        addKey(dpad, app, "A", 0x41, 1, 0)
        addKey(dpad, app, "S", 0x53, 1, 1)
        addKey(dpad, app, "D", 0x44, 1, 2)
        val face = GridLayout(app).apply { rowCount = 3; columnCount = 3 }
        addKey(face, app, "X", 0x55, 0, 1)
        addKey(face, app, "Y", 0x49, 1, 0)
        addKey(face, app, "B", 0x4B, 1, 2)
        addKey(face, app, "A", 0x4A, 2, 1)

        val leftColumn = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        leftColumn.addView(dpad)
        leftColumn.addView(systemKey(app, "START", 0x0D))
        val rightColumn = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        rightColumn.addView(face)
        rightColumn.addView(systemKey(app, "BACK", 0x1B))
        body.addView(leftColumn, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(rightColumn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(body)

        val params = WindowManager.LayoutParams(
            dp(app, 316),
            dp(app, 174),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(app, 10)
            y = dp(app, 18)
        }
        return try {
            manager.addView(root, params)
            windowManager = manager
            rootView = root
            true
        } catch (_: Exception) {
            rootView = null
            windowManager = null
            false
        }
    }

    private fun addKey(grid: GridLayout, context: Context, label: String, virtualKey: Int, row: Int, column: Int) {
        val button = TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            background = rounded(Color.argb(224, 45, 91, 150), dp(context, 24))
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        synchronized(pressedKeys) {
                            if (pressedKeys.add(virtualKey)) WineInputBridge.sendKey(virtualKey, true)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        synchronized(pressedKeys) {
                            if (pressedKeys.remove(virtualKey)) WineInputBridge.sendKey(virtualKey, false)
                        }
                        view.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        val params = GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(column)).apply {
            width = dp(context, 42)
            height = dp(context, 42)
            setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
        }
        grid.addView(button, params)
    }

    private fun systemKey(context: Context, label: String, virtualKey: Int): TextView =
        TextView(context).apply {
            text = label
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(210, 70, 76, 93), dp(context, 6))
            setPadding(dp(context, 14), dp(context, 5), dp(context, 14), dp(context, 5))
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        synchronized(pressedKeys) {
                            if (pressedKeys.add(virtualKey)) WineInputBridge.sendKey(virtualKey, true)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        synchronized(pressedKeys) {
                            if (pressedKeys.remove(virtualKey)) WineInputBridge.sendKey(virtualKey, false)
                        }
                        view.performClick()
                        true
                    }
                    else -> true
                }
            }
        }

    fun hide() {
        synchronized(pressedKeys) {
            pressedKeys.forEach { WineInputBridge.sendKey(it, false) }
            pressedKeys.clear()
        }
        val view = rootView ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) {}
        rootView = null
        windowManager = null
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = radius.toFloat() }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
