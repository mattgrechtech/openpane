package io.github.mattgrechtech.openpane.web

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * An on-screen pointer for dashboards whose cards can't be reached with D-pad focus.
 * The arrows move it (faster while held), OK taps under it, and pushing against an edge scrolls
 * whatever is under the pointer by sending a short swipe, which also scrolls HA's inner panels.
 */
class PointerController(context: Context, private val target: () -> View?) {

    val view = PointerView(context)
    var enabled = false
        set(value) {
            field = value
            view.visibility = if (value) View.VISIBLE else View.GONE
            if (value && view.x0 < 0) centre()
        }

    private val density = context.resources.displayMetrics.density

    /** Returns true if the key was used. */
    fun onKey(event: KeyEvent): Boolean {
        if (!enabled) return false
        val (dx, dy) = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // 10 dp a step, up to 5x faster the longer the key is held.
        val step = 10 * density * (1 + event.repeatCount / 4f).coerceAtMost(5f)
        move(dx * step, dy * step)
        return true
    }

    fun click() {
        val v = target() ?: return
        val down = SystemClock.uptimeMillis()
        send(v, down, down, MotionEvent.ACTION_DOWN, view.x0, view.y0)
        send(v, down, down + 60, MotionEvent.ACTION_UP, view.x0, view.y0)
    }

    private fun centre() {
        view.moveTo(view.width / 2f, view.height / 2f)
    }

    private fun move(dx: Float, dy: Float) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val nx = (view.x0 + dx).coerceIn(0f, w - 1)
        val ny = (view.y0 + dy).coerceIn(0f, h - 1)
        // Pushing past an edge scrolls instead.
        val overX = (view.x0 + dx) - nx
        val overY = (view.y0 + dy) - ny
        view.moveTo(nx, ny)
        if (overX != 0f || overY != 0f) swipe(-overX * 6, -overY * 6)
    }

    /** A synthetic finger swipe at the pointer, moving content by about (dx, dy). */
    private fun swipe(dx: Float, dy: Float) {
        val v = target() ?: return
        val x = view.x0.coerceIn(40 * density, view.width - 40 * density)
        val y = view.y0.coerceIn(40 * density, view.height - 40 * density)
        val start = SystemClock.uptimeMillis()
        send(v, start, start, MotionEvent.ACTION_DOWN, x, y)
        val steps = 6
        for (i in 1..steps) {
            send(v, start, start + i * 10L, MotionEvent.ACTION_MOVE, x + dx * i / steps, y + dy * i / steps)
        }
        send(v, start, start + (steps + 1) * 10L, MotionEvent.ACTION_UP, x + dx, y + dy)
    }

    private fun send(v: View, downTime: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        v.dispatchTouchEvent(event)
        event.recycle()
    }

    class PointerView(context: Context) : View(context) {
        var x0 = -1f
            private set
        var y0 = -1f
            private set

        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
        }
        private val arrow = Path()

        init {
            visibility = GONE
            isFocusable = false
        }

        fun moveTo(x: Float, y: Float) {
            x0 = x
            y0 = y
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // First layout (or the pointer would be off-screen): start in the middle.
            if (oldw == 0 || x0 < 0 || x0 >= w || y0 >= h) moveTo(w / 2f, h / 2f)
        }

        override fun onDraw(canvas: Canvas) {
            if (x0 < 0) return
            val s = density * 1.6f
            arrow.reset()
            arrow.moveTo(x0, y0)
            arrow.lineTo(x0, y0 + 20 * s)
            arrow.lineTo(x0 + 5 * s, y0 + 15 * s)
            arrow.lineTo(x0 + 9 * s, y0 + 23 * s)
            arrow.lineTo(x0 + 12 * s, y0 + 21.5f * s)
            arrow.lineTo(x0 + 8 * s, y0 + 14 * s)
            arrow.lineTo(x0 + 14 * s, y0 + 14 * s)
            arrow.close()
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
        }
    }
}
