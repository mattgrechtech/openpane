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

    val ACCENT = 0xFF0288D1.toInt()
    val SUCCESS = 0xFF43A047.toInt()
    val WARNING = 0xFFFFB300.toInt()
    val SURFACE = 0xFF2A2D33.toInt()
    val BACKGROUND = 0xFF121316.toInt()
    val TEXT_SECONDARY = 0xFFBDBDBD.toInt()

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
