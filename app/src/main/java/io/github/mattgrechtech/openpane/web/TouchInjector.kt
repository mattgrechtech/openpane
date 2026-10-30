package io.github.mattgrechtech.openpane.web

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/** Synthetic finger taps and swipes, so remote presses reach pages that only listen for touch. */
object TouchInjector {

    fun tap(target: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(target, down, down, MotionEvent.ACTION_DOWN, x, y)
        send(target, down, down + 60, MotionEvent.ACTION_UP, x, y)
    }

    /** A short swipe starting at (x, y) and moving by (dx, dy). */
    fun swipe(target: View, x: Float, y: Float, dx: Float, dy: Float) {
        val start = SystemClock.uptimeMillis()
        send(target, start, start, MotionEvent.ACTION_DOWN, x, y)
        val steps = 6
        for (i in 1..steps) {
            send(target, start, start + i * 10L, MotionEvent.ACTION_MOVE, x + dx * i / steps, y + dy * i / steps)
        }
        send(target, start, start + (steps + 1) * 10L, MotionEvent.ACTION_UP, x + dx, y + dy)
    }

    private fun send(target: View, downTime: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(event)
        event.recycle()
    }
}
