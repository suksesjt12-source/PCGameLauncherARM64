package com.suksesjt12.pcgamelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Left analog-style stick emits eight-way keyboard states.
 * Right look pad emits relative mouse movement; this is not SDL/XInput.
 */
object ControllerOverlayManager {
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private val pressedKeys = linkedSetOf<Int>()

    fun isShowing(): Boolean = rootView != null

    fun show(context: Context, settings: GameProfileSettings.Settings = GameProfileSettings.Settings()): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (rootView != null) hide()
        val app = context.applicationContext
        val scale = (settings.controllerScale / 100f).coerceIn(0.75f, 1.25f)
        val compact = settings.controllerLayout == "compact"
        val stickDp = ((if (compact) 90f else 108f) * scale).roundToInt()
        val buttonDp = ((if (compact) 36f else 42f) * scale).roundToInt()
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(app, 6), dp(app, 4), dp(app, 6), dp(app, 6))
            alpha = settings.controllerOpacity / 100f
            background = rounded(Color.argb(198, 10, 15, 26), dp(app, 12))
        }
        val header = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(app).apply {
            text = "PCGL • MOVE / LOOK"
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(dp(app, 4), dp(app, 3), dp(app, 8), dp(app, 5))
            layoutParams = LinearLayout.LayoutParams(0, dp(app, 24), 1f)
        })
        header.addView(TextView(app).apply {
            text = if (WineInputBridge.isConnected()) "CONNECTED" else "CONNECTING"
            textSize = 8f
            setTextColor(if (WineInputBridge.isConnected()) Color.rgb(120, 230, 160) else Color.rgb(255, 205, 120))
            setPadding(0, 0, dp(app, 8), 0)
        })
        header.addView(TextView(app).apply {
            text = "HIDE"
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(120, 45, 48), dp(app, 6))
            setPadding(dp(app, 8), 0, dp(app, 8), 0)
            setOnClickListener { hide() }
        })
        root.addView(header)

        val body = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        body.addView(AnalogStickView(app, settings), LinearLayout.LayoutParams(dp(app, stickDp), dp(app, stickDp)))

        val actions = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(app, 3), 0, dp(app, 3), 0)
        }
        val keys = GameProfileSettings.actionKeys(settings)
        val row1 = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val row2 = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val row3 = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addButton(row1, app, "Y", keys[3], buttonDp)
        addButton(row1, app, "X", keys[2], buttonDp)
        addButton(row2, app, "B", keys[1], buttonDp)
        addButton(row2, app, "A", keys[0], buttonDp)
        addButton(row3, app, "START", 0x0D, (buttonDp * 0.82f).roundToInt())
        addButton(row3, app, "ESC", 0x1B, (buttonDp * 0.82f).roundToInt())
        actions.addView(row1)
        actions.addView(row2)
        actions.addView(row3)
        body.addView(actions, LinearLayout.LayoutParams(dp(app, buttonDp * 2 + 14), -2))

        body.addView(MouseLookPad(app, settings.mouseSensitivity),
            LinearLayout.LayoutParams(dp(app, stickDp), dp(app, stickDp)))
        root.addView(body)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(app, 8)
            y = dp(app, 16)
        }
        return try {
            manager.addView(root, params)
            windowManager = manager
            rootView = root
            true
        } catch (_: Exception) {
            windowManager = null
            rootView = null
            false
        }
    }

    private fun addButton(parent: LinearLayout, context: Context, label: String, key: Int, sizeDp: Int) {
        val view = TextView(context).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            background = rounded(Color.argb(225, 45, 91, 150), dp(context, sizeDp / 2))
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { pressKey(key); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        releaseKey(key)
                        v.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        val size = dp(context, sizeDp)
        parent.addView(view, LinearLayout.LayoutParams(size, size).apply {
            setMargins(dp(context, 1), dp(context, 1), dp(context, 1), dp(context, 1))
        })
    }

    private fun pressKey(key: Int) {
        synchronized(pressedKeys) {
            if (pressedKeys.add(key)) WineInputBridge.sendKey(key, true)
        }
    }

    private fun releaseKey(key: Int) {
        synchronized(pressedKeys) {
            if (pressedKeys.remove(key)) WineInputBridge.sendKey(key, false)
        }
    }

    fun hide() {
        synchronized(pressedKeys) {
            pressedKeys.forEach { WineInputBridge.sendKey(it, false) }
            pressedKeys.clear()
        }
        val old = rootView ?: return
        try { windowManager?.removeView(old) } catch (_: Exception) {}
        rootView = null
        windowManager = null
    }

    private class AnalogStickView(context: Context, mapping: GameProfileSettings.Settings) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val keys = GameProfileSettings.movementKeys(mapping)
        private var cx = 0f
        private var cy = 0f
        private var radius = 0f
        private var knobX = 0f
        private var knobY = 0f
        private var active = emptySet<Int>()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            cx = w / 2f; cy = h / 2f
            radius = min(w, h) * 0.40f
            knobX = cx; knobY = cy
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(120, 120, 140, 165)
            canvas.drawCircle(cx, cy, radius * 1.12f, paint)
            paint.color = Color.argb(195, 24, 34, 51)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.color = Color.argb(235, 73, 137, 205)
            canvas.drawCircle(knobX, knobY, radius * 0.38f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(context, 1).toFloat()
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { updateStick(event.x, event.y); return true }
                MotionEvent.ACTION_MOVE -> { updateStick(event.x, event.y); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { resetStick(); return true }
            }
            return true
        }

        private fun updateStick(x: Float, y: Float) {
            var dx = x - cx
            var dy = y - cy
            val distance = sqrt(dx * dx + dy * dy)
            if (distance > radius && distance > 0f) {
                val factor = radius / distance
                dx *= factor; dy *= factor
            }
            knobX = cx + dx; knobY = cy + dy
            val nx = if (radius > 0f) dx / radius else 0f
            val ny = if (radius > 0f) dy / radius else 0f
            val deadzone = 0.30f
            val next = linkedSetOf<Int>()
            if (ny < -deadzone) next.add(keys[0])
            if (ny > deadzone) next.add(keys[1])
            if (nx < -deadzone) next.add(keys[2])
            if (nx > deadzone) next.add(keys[3])
            (active - next).forEach { ControllerOverlayManager.releaseKey(it) }
            (next - active).forEach { ControllerOverlayManager.pressKey(it) }
            active = next
            invalidate()
        }

        private fun resetStick() {
            active.forEach { ControllerOverlayManager.releaseKey(it) }
            active = emptySet()
            knobX = cx; knobY = cy
            invalidate()
        }

        override fun onDetachedFromWindow() {
            resetStick()
            super.onDetachedFromWindow()
        }
    }

    private class MouseLookPad(context: Context, private val sensitivity: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastX = 0f
        private var lastY = 0f
        private var tracking = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = min(width, height) * 0.42f
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(120, 120, 140, 165)
            canvas.drawCircle(width / 2f, height / 2f, r * 1.12f, paint)
            paint.color = Color.argb(195, 24, 34, 51)
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(context, 11).toFloat()
            canvas.drawText("LOOK", width / 2f, height / 2f + paint.textSize / 3f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; tracking = true; return true }
                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        val factor = (sensitivity / 100f).coerceIn(0.5f, 2.5f)
                        val dx = ((event.x - lastX) * factor).roundToInt()
                        val dy = ((event.y - lastY) * factor).roundToInt()
                        if (dx != 0 || dy != 0) WineInputBridge.sendMouseMove(dx, dy)
                        lastX = event.x; lastY = event.y
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracking = false; return true }
            }
            return true
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = radius.toFloat() }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
