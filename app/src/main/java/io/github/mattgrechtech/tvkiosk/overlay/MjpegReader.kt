package io.github.mattgrechtech.tvkiosk.overlay

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pulls JPEG frames out of an MJPEG stream (`multipart/x-mixed-replace`, as served by Home
 * Assistant's camera_proxy_stream, go2rtc, Frigate and most IP cameras). It looks for the JPEG
 * start and end markers rather than trusting part headers, which cameras get wrong.
 * No Android dependencies.
 */
class MjpegReader(private val input: InputStream, private val maxFrameBytes: Int = MAX_FRAME_BYTES) {

    private val buffer = ByteArray(16 * 1024)
    private var start = 0
    private var end = 0

    /** The next whole JPEG frame, or null at the end of the stream. Skips frames that are too big. */
    fun next(): ByteArray? {
        while (true) {
            if (!seekStartMarker()) return null
            val frame = ByteArrayOutputStream()
            frame.write(0xFF)
            frame.write(0xD8)
            var previous = 0
            var tooBig = false
            while (true) {
                val b = read()
                if (b < 0) return null
                if (!tooBig) {
                    frame.write(b)
                    if (frame.size() > maxFrameBytes) tooBig = true
                }
                if (previous == 0xFF && b == 0xD9) break
                previous = b
            }
            if (!tooBig) return frame.toByteArray()
        }
    }

    /** Reads until just after the next FF D8. */
    private fun seekStartMarker(): Boolean {
        var previous = 0
        while (true) {
            val b = read()
            if (b < 0) return false
            if (previous == 0xFF && b == 0xD8) return true
            previous = b
        }
    }

    private fun read(): Int {
        if (start == end) {
            val n = input.read(buffer)
            if (n <= 0) return -1
            start = 0
            end = n
        }
        return buffer[start++].toInt() and 0xFF
    }

    companion object {
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
