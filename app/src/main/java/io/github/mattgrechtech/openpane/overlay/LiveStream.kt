package io.github.mattgrechtech.openpane.overlay

import android.graphics.Bitmap
import android.os.SystemClock
import io.github.mattgrechtech.openpane.diag.AppLog
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plays an MJPEG stream into a pop-up: frames are decoded off the main thread, scaled down to
 * [maxSidePx] and limited to [MAX_FPS], so a camera can't overwhelm a small TV. Reconnects a few
 * times if the stream drops; [stop] ends it (always called when the pop-up goes).
 */
class LiveStream(private val url: String, private val maxSidePx: Int, private val onFrame: (Bitmap) -> Unit) {

    @Volatile private var stopped = false
    private var connection: HttpURLConnection? = null

    fun start() {
        Thread({ run() }, "live-stream").start()
    }

    fun stop() {
        stopped = true
        runCatching { connection?.disconnect() }
    }

    private fun run() {
        var failures = 0
        while (!stopped && failures < MAX_RECONNECTS) {
            try {
                play()
                failures = 0
            } catch (e: Exception) {
                if (stopped) return
                failures++
                AppLog.w(TAG, "Stream dropped (${e.message}); retry $failures of $MAX_RECONNECTS")
                SystemClock.sleep(1000L * failures)
            }
        }
    }

    private fun play() {
        val c = URL(url).openConnection() as HttpURLConnection
        connection = c
        c.connectTimeout = 5000
        c.readTimeout = 10_000
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("the camera answered ${c.responseCode}")
            AppLog.i(TAG, "Live stream started")
            val reader = MjpegReader(c.inputStream)
            var lastFrame = 0L
            while (!stopped) {
                val jpeg = reader.next() ?: throw IllegalStateException("stream ended")
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrame < 1000L / MAX_FPS) continue
                lastFrame = now
                ImageLoader.decode(jpeg, maxSidePx)?.let { if (!stopped) onFrame(it) }
            }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val TAG = "LiveStream"
        const val MAX_FPS = 10
        const val MAX_RECONNECTS = 5
    }
}
