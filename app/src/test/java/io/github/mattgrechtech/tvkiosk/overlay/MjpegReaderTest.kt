package io.github.mattgrechtech.tvkiosk.overlay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MjpegReaderTest {

    private fun jpeg(vararg body: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        body.map { it.toByte() }.toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun multipart(vararg frames: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (frame in frames) {
            out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
            out.write(frame)
            out.write("\r\n".toByteArray())
        }
        return out.toByteArray()
    }

    @Test fun readsEachFrameInOrder() {
        val a = jpeg(1, 2, 3, 0xFF, 0x00, 4)
        val b = jpeg(9, 8)
        val reader = MjpegReader(ByteArrayInputStream(multipart(a, b)))
        assertArrayEquals(a, reader.next())
        assertArrayEquals(b, reader.next())
        assertNull(reader.next())
    }

    @Test fun skipsFramesThatAreTooBig() {
        val big = jpeg(*IntArray(100) { 7 })
        val small = jpeg(5)
        val reader = MjpegReader(ByteArrayInputStream(multipart(big, small)), maxFrameBytes = 50)
        assertArrayEquals(small, reader.next())
    }

    @Test fun cutOffStreamEndsCleanly() {
        val whole = multipart(jpeg(1, 2, 3))
        assertNull(MjpegReader(ByteArrayInputStream(whole.copyOf(whole.size - 5))).next())
    }
}
