package io.github.mattgrechtech.tvkiosk.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Date

/**
 * A full-screen message with optional buttons, for states the user must act on or wait through:
 * "Sign in", "Can't reach Home Assistant", "Signed in". The first button takes focus, so OK on
 * the remote does the obvious thing.
 */
class MessagePanel(context: Context) : FrameLayout(context) {

    class Action(val label: String, val primary: Boolean = false, val run: () -> Unit)

    private val icon = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
        gravity = Gravity.CENTER
    }
    private val spinner = ProgressBar(context).apply { isIndeterminate = true }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val message = TextView(context).apply {
        setTextColor(TvUi.TEXT_SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.2f)
    }
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val snapshot = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = View.GONE
    }
    private val shade = View(context)
    private val clock = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        visibility = View.GONE
    }
    private val main = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = DateFormat.getTimeFormat(context).format(Date())
            main.postDelayed(this, 15_000L)
        }
    }

    init {
        addView(snapshot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shade.setBackgroundColor(SOLID)
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            val edge = TvUi.dp(context, 36)
            setMargins(edge, edge, edge, edge)
        })
        isClickable = true // swallow touches meant for the page underneath
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val gap = TvUi.dp(context, 18)
            addView(icon)
            addView(spinner, LinearLayout.LayoutParams(TvUi.dp(context, 56), TvUi.dp(context, 56)))
            addView(title, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
            addView(message, LinearLayout.LayoutParams(TvUi.dp(context, 760), LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
            addView(buttons, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap * 2 })
        }
        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        visibility = View.GONE
    }

    /**
     * Shows the panel. [iconText] is an emoji or symbol shown above the title; with [busy] a
     * spinner is shown instead. With [background] (the last good dashboard, for example) the
     * picture shows dimmed behind the message, with a clock, so the screen stays useful.
     */
    fun show(
        titleText: String,
        messageText: String = "",
        actions: List<Action> = emptyList(),
        iconText: String? = null,
        busy: Boolean = false,
        background: Bitmap? = null,
    ) {
        snapshot.setImageBitmap(background)
        snapshot.visibility = if (background != null) View.VISIBLE else View.GONE
        shade.setBackgroundColor(if (background != null) OVER_PICTURE else SOLID)
        main.removeCallbacks(clockTick)
        clock.visibility = if (background != null) View.VISIBLE else View.GONE
        if (background != null) clockTick.run()
        icon.text = iconText ?: ""
        icon.visibility = if (iconText != null && !busy) View.VISIBLE else View.GONE
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        title.text = titleText
        message.text = messageText
        message.visibility = if (messageText.isEmpty()) View.GONE else View.VISIBLE

        buttons.removeAllViews()
        for (action in actions) {
            val button = TvUi.button(context, action.label, action.primary) { action.run() }
            buttons.addView(button, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = TvUi.dp(context, 10)
                marginEnd = TvUi.dp(context, 10)
            })
        }
        buttons.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        visibility = View.VISIBLE
        buttons.getChildAt(0)?.requestFocus()
    }

    fun hide() {
        visibility = View.GONE
        buttons.removeAllViews()
        main.removeCallbacks(clockTick)
        snapshot.setImageBitmap(null)
    }

    val isShowing get() = visibility == View.VISIBLE

    companion object {
        const val ICON_TV = "📺"
        const val ICON_WARNING = "⚠️"
        const val ICON_KEY = "🔑"
        const val ICON_DONE = "✅"
        private const val SOLID = 0xF2101114.toInt()
        private const val OVER_PICTURE = 0xB3101114.toInt()
    }
}
