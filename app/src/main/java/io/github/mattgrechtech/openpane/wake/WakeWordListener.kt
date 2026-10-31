package io.github.mattgrechtech.openpane.wake

/**
 * Listens for a wake word ("Okay Nabu") and calls back when it hears it. Only in the voice build
 * ([WakeWords.available]); the standard build has no wake word code, model or runtime.
 */
interface WakeWordListener {
    /** Opens the microphone and listens for [word] (one of [WakeWords.WORDS]). */
    fun start(word: String)

    /** Closes the microphone. */
    fun stop()

    val running: Boolean
}
