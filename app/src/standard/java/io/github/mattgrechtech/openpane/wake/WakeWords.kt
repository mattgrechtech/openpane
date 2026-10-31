package io.github.mattgrechtech.openpane.wake

import android.content.Context

/** The standard build: no wake word. The voice build has the real one. */
object WakeWords {
    const val available = false
    val WORDS = emptyList<String>()

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, onWake: () -> Unit): WakeWordListener? = null
}
