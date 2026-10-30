package io.github.mattgrechtech.tvkiosk.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import io.github.mattgrechtech.tvkiosk.diag.AppLog

/** Loads pop-up images from a URL, base64 or a data: URI, off the main thread. */
object ImageLoader {

    private const val TAG = "ImageLoader"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val executor = Executors.newFixedThreadPool(2)

    fun load(source: String, maxSidePx: Int, callback: (Bitmap?) -> Unit) {
        executor.execute { callback(loadNow(source, maxSidePx)) }
    }

    private val recent = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 4
    }

    fun loadNow(source: String, maxSidePx: Int): Bitmap? {
        val key = "$maxSidePx $source"
        val web = source.startsWith("http://") || source.startsWith("https://")
        if (web) synchronized(recent) { recent[key] }?.let { return it }
        return fetch(source, maxSidePx)?.also { bitmap -> if (web) synchronized(recent) { recent[key] = bitmap } }
    }

    private fun fetch(source: String, maxSidePx: Int): Bitmap? = try {
        val bytes = when {
            source.startsWith("http://") || source.startsWith("https://") -> download(source)
            source.startsWith("data:") -> Base64.decode(source.substringAfter(","), Base64.DEFAULT)
            else -> Base64.decode(source, Base64.DEFAULT)
        }
        bytes?.let { decode(it, maxSidePx) }
    } catch (e: Exception) {
        AppLog.w(TAG, "Can't load image: ${e.message}")
        null
    }

    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 8000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode !in 200..299) {
                AppLog.w(TAG, "Image request returned ${connection.responseCode}")
                return null
            }
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_BYTES) {
                        AppLog.w(TAG, "Image larger than ${MAX_BYTES / 1024 / 1024} MB, skipped")
                        return null
                    }
                }
                return out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    fun decode(bytes: ByteArray, maxSidePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Never decode more than needed: a huge picture must not run a small TV out of memory.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSidePx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            // Photos (camera snapshots) have no transparency: half the memory.
            if (bounds.outMimeType == "image/jpeg") inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        })
    }
}
