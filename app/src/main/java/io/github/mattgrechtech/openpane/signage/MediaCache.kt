package io.github.mattgrechtech.openpane.signage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import io.github.mattgrechtech.openpane.diag.AppLog
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Pictures and videos kept on the device for signage and the photo frame, so they play without the
 * network and never wait on a download. Pictures are stored at screen size (a phone photo of 4 MB
 * becomes a few hundred KB, quick to show); videos as they are. Each file is checked again every few
 * hours (cheaply: "has it changed?"), and files nothing wants any more are deleted. One background
 * thread does it all, and only when asked: nothing runs when signage and photos aren't used.
 */
class MediaCache(
    private val dir: File,
    /** The most the cache may hold, in bytes. */
    private val maxBytes: () -> Long,
    /** The longest side pictures are stored at, in pixels: the screen's. */
    private val maxSide: Int,
) {
    /** Something to keep. [resolve] gives the address and a Home Assistant token, or null if it can't now. */
    class Wanted(val key: String, val video: Boolean, val resolve: () -> Source?)

    data class Source(val url: String, val bearer: String?)

    /** Called on the cache's thread after a file arrives or goes. */
    @Volatile var onChanged: () -> Unit = {}

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "media-cache").apply { priority = Thread.MIN_PRIORITY } }
    private val pending = mutableMapOf<String, List<Wanted>>()
    private var running = false
    @Volatile private var lastFull = false

    /**
     * Keeps exactly [wanted] for [group] ("signage", "photos"): fetches what's missing or due a
     * check, deletes the group's files no longer wanted. Returns at once; repeated calls while one
     * runs are folded into one.
     */
    fun sync(group: String, wanted: List<Wanted>) {
        synchronized(pending) {
            pending[group] = wanted
            if (running) return
            running = true
        }
        worker.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val (group, wanted) = synchronized(pending) {
                val next = pending.entries.firstOrNull() ?: run { running = false; return }
                pending.remove(next.key)
                next.key to next.value
            }
            try {
                syncNow(group, wanted)
            } catch (e: Exception) {
                AppLog.w(TAG, "Media sync for $group failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The stored file for [key], or null if it isn't here (yet). */
    fun file(key: String): File? = File(dir, name(key)).takeIf { it.isFile }

    /** Every stored file of [group], oldest first: the photo frame's pictures, even offline. */
    fun files(group: String): List<File> = metas().filter { it.second.optString("group") == group }
        .mapNotNull { (meta, json) -> File(dir, meta.name.removeSuffix(META)).takeIf { it.isFile }?.let { it to json.optLong("added") } }
        .sortedBy { it.second }.map { it.first }

    fun toJson(): JSONObject {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(META) && !f.name.endsWith(PART) }.orEmpty()
        return JSONObject().put("files", files.size).put("mb", files.sumOf { it.length() } / 1_048_576.0)
            .put("full", lastFull)
    }

    private fun syncNow(group: String, wanted: List<Wanted>) {
        dir.mkdirs()
        val names = wanted.associateBy { name(it.key) }
        // Gone from the playlist or album: delete.
        var removed = 0
        for ((meta, json) in metas()) {
            val name = meta.name.removeSuffix(META)
            if (json.optString("group") == group && name !in names) {
                File(dir, name).delete()
                meta.delete()
                removed++
            }
        }
        dir.listFiles { f -> f.name.endsWith(PART) }?.forEach { it.delete() }
        if (removed > 0) onChanged()
        var full = false
        for ((name, item) in names) {
            // A newer list for this group came in: start over with it instead of finishing this one.
            if (synchronized(pending) { group in pending }) return
            val file = File(dir, name)
            val meta = File(dir, name + META)
            val json = runCatching { JSONObject(meta.readText()) }.getOrNull()
            val have = file.isFile && json != null
            if (have && System.currentTimeMillis() - json!!.optLong("checked") < RECHECK_MILLIS) continue
            if (!have && used() >= maxBytes()) {
                full = true
                continue
            }
            val source = item.resolve() ?: continue
            try {
                if (fetch(source, item.video, file, json)) onChanged()
                val updated = (json ?: JSONObject().put("added", System.currentTimeMillis())).put("group", group)
                    .put("checked", System.currentTimeMillis())
                lastValidators?.let { (etag, modified) -> updated.put("etag", etag).put("modified", modified) }
                meta.writeText(updated.toString())
            } catch (e: Exception) {
                // Kept as it was: an old copy plays until the server answers again.
                AppLog.w(TAG, "Can't fetch ${shortName(source.url)}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (full && !lastFull) AppLog.w(TAG, "Media cache is full (${maxBytes() / 1_048_576} MB): some items wait")
        lastFull = full
    }

    private var lastValidators: Pair<String, String>? = null

    /** Downloads [source] into [file] unless the server says it hasn't changed. True if [file] changed. */
    private fun fetch(source: Source, video: Boolean, file: File, json: JSONObject?): Boolean {
        lastValidators = null
        val connection = URL(source.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            source.bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (file.isFile && json != null) {
                json.optString("etag").takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("If-None-Match", it) }
                json.optString("modified").takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("If-Modified-Since", it) }
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                lastValidators = (json?.optString("etag").orEmpty()) to (json?.optString("modified").orEmpty())
                return false
            }
            if (status != 200) throw IOException("answered $status")
            val length = connection.contentLengthLong
            if (length > MAX_FILE_BYTES) throw IOException("larger than ${MAX_FILE_BYTES / 1_048_576} MB")
            val part = File(dir, file.name + PART)
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) throw IOException("larger than ${MAX_FILE_BYTES / 1_048_576} MB")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!video) shrink(part)
            if (!part.renameTo(file)) {
                file.delete()
                if (!part.renameTo(file)) throw IOException("can't store it")
            }
            lastValidators = connection.getHeaderField("ETag").orEmpty() to connection.getHeaderField("Last-Modified").orEmpty()
            return true
        } finally {
            connection.disconnect()
        }
    }

    /** Stores a picture at screen size, the right way up, as a JPEG. */
    private fun shrink(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) throw IOException("not a picture Android can show")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("not a picture Android can show")
        val rotation = runCatching {
            when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        val ready = if (scale < 1f || rotation != 0f) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        try {
            file.outputStream().use { ready.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            ready.recycle()
        }
    }

    private fun used(): Long = dir.listFiles { f -> f.isFile && !f.name.endsWith(META) }.orEmpty().sumOf { it.length() }

    private fun metas(): List<Pair<File, JSONObject>> = dir.listFiles { f -> f.name.endsWith(META) }.orEmpty()
        .mapNotNull { meta -> runCatching { meta to JSONObject(meta.readText()) }.getOrNull() }

    companion object {
        private const val TAG = "MediaCache"
        private const val META = ".meta"
        private const val PART = ".part"
        private const val RECHECK_MILLIS = 6 * 3_600_000L
        private const val MAX_FILE_BYTES = 300L * 1_048_576
        private const val JPEG_QUALITY = 88

        /** A file name for [key] that can't collide or reach outside the folder. */
        fun name(key: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
            return hash + if (Playlist.kindOf(key) == Playlist.Kind.VIDEO) ".video" else ".jpg"
        }

        /** The file name in a URL, for logs (no host, no query with signatures). */
        fun shortName(url: String) = url.substringBefore('?').substringAfterLast('/')
    }
}
