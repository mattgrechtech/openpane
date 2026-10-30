package io.github.mattgrechtech.openpane.control

import android.view.KeyEvent

/**
 * Remote keys the dashboard doesn't need, which can tell Home Assistant they were pressed
 * (`key_events` setting): the four colour keys and the digits. Navigation, OK, Back, Home,
 * volume and media keys are never taken.
 */
object RemoteKeys {

    const val LONG_PRESS_MILLIS = 600L

    private val NAMES = mapOf(
        KeyEvent.KEYCODE_PROG_RED to "red",
        KeyEvent.KEYCODE_PROG_GREEN to "green",
        KeyEvent.KEYCODE_PROG_YELLOW to "yellow",
        KeyEvent.KEYCODE_PROG_BLUE to "blue",
    ) + (0..9).associate { (KeyEvent.KEYCODE_0 + it) to it.toString() }

    /** The name Home Assistant sees ("red", "5"), or null for a key that's left alone. */
    fun name(keyCode: Int): String? = NAMES[keyCode]
}
