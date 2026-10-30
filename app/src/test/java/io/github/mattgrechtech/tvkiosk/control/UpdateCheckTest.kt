package io.github.mattgrechtech.tvkiosk.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    @Test fun comparesVersionsByNumber() {
        assertTrue(UpdateCheck.isNewer("0.2.0", "0.1.0"))
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.3"))
        assertTrue(UpdateCheck.isNewer("1.0", "0.9.9"))
        assertFalse(UpdateCheck.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateCheck.isNewer("0.1.0", "0.1.0-debug"))
        assertFalse(UpdateCheck.isNewer("0.1.0", "0.2.0"))
        assertFalse(UpdateCheck.isNewer("garbage", "0.1.0"))
    }
}
