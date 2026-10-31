package io.github.mattgrechtech.openpane.wake

import android.content.Context

/** The voice build: microWakeWord models (Apache-2.0) run on the device with TensorFlow Lite. */
object WakeWords {
    const val available = true
    /** Models in assets/wake/, from esphome/micro-wake-word-models (v2). */
    val WORDS = listOf("okay_nabu", "hey_jarvis", "hey_mycroft")

    fun create(context: Context, onWake: () -> Unit): WakeWordListener? = MicListener(context.applicationContext, onWake)
}
