package io.github.mattgrechtech.openpane.control

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteKeysTest {

    @Test fun colourKeysAndDigitsOnly() {
        assertEquals("red", RemoteKeys.name(KeyEvent.KEYCODE_PROG_RED))
        assertEquals("blue", RemoteKeys.name(KeyEvent.KEYCODE_PROG_BLUE))
        assertEquals("0", RemoteKeys.name(KeyEvent.KEYCODE_0))
        assertEquals("9", RemoteKeys.name(KeyEvent.KEYCODE_9))
        for (kept in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            assertNull(RemoteKeys.name(kept))
        }
    }
}
