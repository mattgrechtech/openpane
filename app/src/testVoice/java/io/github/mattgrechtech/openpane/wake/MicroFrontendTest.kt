package io.github.mattgrechtech.openpane.wake

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MicroFrontendTest {

    /**
     * The features must match TensorFlow Lite Micro's own frontend exactly: the reference is
     * pymicro-features 2.0.2 (the C code microWakeWord trains with) on the same 3 s of audio
     * (noise, a chirp, bursts, silence). See wake/features.txt.
     */
    @Test
    fun matchesTheReferenceFrontend() {
        val pcm = javaClass.getResourceAsStream("/wake/input.pcm")!!.readBytes()
        val expected = javaClass.getResourceAsStream("/wake/features.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }
        val samples = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

        val frontend = MicroFrontend()
        val features = IntArray(MicroFrontend.CHANNELS)
        val rows = mutableListOf<String>()
        var at = 0
        while (at + MicroFrontend.STEP_SAMPLES <= samples.size) {
            val (used, ready) = frontend.process(samples, at, MicroFrontend.STEP_SAMPLES, features)
            at += used
            if (ready) rows += features.joinToString(" ")
        }
        assertEquals(expected.size, rows.size)
        for (i in expected.indices) assertEquals("frame $i", expected[i], rows[i])
    }
}
