package io.github.mattgrechtech.openpane.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChimesTest {

    @Test fun everyNamedSoundHasSamples() {
        for (name in Chimes.NAMES) {
            val pcm = Chimes.samples(name)!!
            assertTrue("$name is at least half a second", pcm.size >= 22_050 / 2)
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("$name is audible but not clipped", peak in 20_000..Short.MAX_VALUE.toInt())
        }
        assertEquals(setOf("chime", "doorbell", "alert"), Chimes.NAMES)
    }

    @Test fun unknownSoundIsNull() {
        assertNull(Chimes.samples("trumpet"))
    }
}
