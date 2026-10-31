package io.github.mattgrechtech.openpane.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button

/** Shared look for a remote-driven UI: the focused control must be obvious from the sofa. */
object TvUi {

    /** High contrast: pure black, white text, a brighter accent. Set from the settings. */
    @Volatile var highContrast = false

    val ACCENT get() = if (highContrast) 0xFF4FC3F7.toInt() else 0xFF0288D1.toInt()
    val SUCCESS get() = if (highContrast) 0xFF69F0AE.toInt() else 0xFF43A047.toInt()
    val WARNING get() = if (highContrast) 0xFFFFD740.toInt() else 0xFFFFB300.toInt()
    val SURFACE get() = if (highContrast) 0xFF000000.toInt() else 0xFF2A2D33.toInt()
    val BACKGROUND get() = if (highContrast) 0xFF000000.toInt() else 0xFF121316.toInt()
    val TEXT_SECONDARY get() = if (highContrast) 0xFFFFFFFF.toInt() else 0xFFBDBDBD.toInt()

    /**
     * [base] with the app's text size applied (on top of the system's own), for the app's own
     * screens. The dashboard keeps its own size.
     */
    fun withTextSize(base: Context): Context {
        val app = base.applicationContext as? io.github.mattgrechtech.openpane.KioskApp ?: return base
        val factor = when (app.settings[io.github.mattgrechtech.openpane.settings.Keys.UI_TEXT_SIZE]) {
            "large" -> 1.2f
            "larger" -> 1.4f
            else -> return base
        }
        val config = android.content.res.Configuration(base.resources.configuration)
        config.fontScale = config.fontScale * factor
        return base.createConfigurationContext(config)
    }

    /** A button that fills with the accent colour and grows a white outline when focused. */
    fun button(context: Context, text: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            minHeight = dp(context, 52)
            minimumHeight = dp(context, 52)
            stateListAnimator = null
            setPadding(dp(context, 24), dp(context, 10), dp(context, 24), dp(context, 10))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(context, ACCENT, stroke = Color.WHITE))
                addState(intArrayOf(android.R.attr.state_pressed), shape(context, ACCENT, stroke = Color.WHITE))
                addState(intArrayOf(), shape(context, if (primary) 0xFF1565C0.toInt() else SURFACE, stroke = null))
            }
            setTextColor(ColorStateList.valueOf(Color.WHITE))
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun shape(context: Context, fill: Int, stroke: Int?) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, 10).toFloat()
        if (stroke != null) setStroke(dp(context, 3), stroke)
    }

    fun dp(context: Context, value: Int) = Math.round(value * context.resources.displayMetrics.density)
}
