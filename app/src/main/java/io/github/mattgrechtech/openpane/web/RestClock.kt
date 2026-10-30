package io.github.mattgrechtech.openpane.web

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import kotlin.random.Random

/**
 * The "clock" rest style: the time and date, soft grey on black, moving to a new spot every
 * minute so nothing burns in. Shown over the dashboard's black layer while resting.
 */
class RestClock(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val time = TextView(context).apply {
        setTextColor(Color.argb(200, 230, 230, 230))
        textSize = 96f
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    }
    private val date = TextView(context).apply {
        setTextColor(Color.argb(150, 230, 230, 230))
        textSize = 26f
        gravity = Gravity.CENTER
    }
    private val block = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(time)
        addView(date)
    }

    val view: FrameLayout = FrameLayout(context).apply {
        visibility = View.GONE
        isFocusable = false
        addView(block, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private var lastMinute = -1L
    private val tick = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, TICK_MILLIS)
        }
    }

    fun show(visible: Boolean) {
        main.removeCallbacks(tick)
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            lastMinute = -1
            tick.run()
        }
    }

    private fun update() {
        val now = Date()
        time.text = DateFormat.getTimeFormat(context).format(now)
        date.text = DateFormat.format("EEEE d MMMM", now)
        val minute = now.time / 60_000
        if (minute != lastMinute) {
            lastMinute = minute
            // Somewhere new within the middle part of the screen.
            val spanX = view.width * 0.25f
            val spanY = view.height * 0.25f
            block.translationX = (Random.nextFloat() * 2 - 1) * spanX
            block.translationY = (Random.nextFloat() * 2 - 1) * spanY
        }
    }

    private companion object {
        const val TICK_MILLIS = 10_000L
    }
}
