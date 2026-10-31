package io.github.mattgrechtech.openpane.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import io.github.mattgrechtech.openpane.diag.AppLog
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/**
 * Pictures and videos over the dashboard, drawn natively: signage's media and the photo frame.
 * Pictures crossfade (the photo frame's drift slowly, so nothing burns in); a picture is decoded off
 * the main thread, at most two are in memory, and the WebView isn't involved at all.
 */
class MediaStage(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "media-stage").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val images = Array(2) {
        ImageView(context).apply {
            alpha = 0f
            isFocusable = false
        }
    }
    private var front = 0
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private val video = VideoView(context).apply {
        visibility = View.GONE
        isFocusable = false
    }
    private val clock = TextView(context).apply {
        setTextColor(Color.argb(220, 255, 255, 255))
        textSize = 30f
        setShadowLayer(8f, 0f, 2f, Color.argb(180, 0, 0, 0))
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        visibility = View.GONE
    }
    private val note = TextView(context).apply {
        setTextColor(Color.argb(170, 230, 230, 230))
        textSize = 22f
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    /** Which picture or video was asked for last: a slower earlier decode doesn't win. */
    private var generation = 0

    val view: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isFocusable = false
        val match = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        images.forEach { addView(it, FrameLayout.LayoutParams(match)) }
        addView(video, FrameLayout.LayoutParams(match.width, match.height, Gravity.CENTER))
        addView(note, FrameLayout.LayoutParams(match))
        val margin = (32 * context.resources.displayMetrics.density).toInt()
        addView(clock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(margin, margin, margin, margin) })
    }

    val showing: Boolean get() = view.visibility == View.VISIBLE

    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = DateFormat.getTimeFormat(context).format(Date())
            main.postDelayed(this, 15_000)
        }
    }

    fun showClock(visible: Boolean) {
        main.removeCallbacks(clockTick)
        clock.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) clockTick.run()
    }

    /**
     * Shows [file]. [photo]: the photo frame's way (fills the screen when the picture's shape is
     * close to it, else fits it; drifts for [driftMillis]); otherwise fitted whole, still.
     */
    fun showImage(file: File, photo: Boolean, driftMillis: Long = 0, onFailed: () -> Unit = {}) {
        val mine = ++generation
        val width = view.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val height = view.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        decoder.execute {
            val bitmap = runCatching { decode(file, maxOf(width, height)) }.getOrNull()
            main.post {
                if (mine != generation) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    AppLog.w(TAG, "Can't show ${file.name}")
                    onFailed()
                    return@post
                }
                present(bitmap, photo, width, height, driftMillis)
            }
        }
    }

    private fun present(bitmap: Bitmap, photo: Boolean, width: Int, height: Int, driftMillis: Long) {
        stopVideo()
        note.visibility = View.GONE
        view.visibility = View.VISIBLE
        val back = 1 - front
        val incoming = images[back]
        val outgoing = images[front]
        val screenWide = width >= height
        val pictureWide = bitmap.width >= bitmap.height
        incoming.scaleType = if (photo && screenWide == pictureWide) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        incoming.setImageBitmap(bitmap)
        incoming.animate().cancel()
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.translationX = 0f
        incoming.bringToFront()
        video.bringToFront()
        note.bringToFront()
        clock.bringToFront()
        val old = bitmaps[back]
        bitmaps[back] = bitmap
        incoming.animate().alpha(1f).setDuration(FADE_MILLIS).withEndAction {
            outgoing.alpha = 0f
            outgoing.setImageDrawable(null)
            bitmaps[front].takeIf { it !== bitmap }?.recycle()
            bitmaps[front] = null
            front = back
            if (photo && driftMillis > 0) {
                // A slow push in, alternately left and right.
                val direction = if (System.nanoTime() % 2 == 0L) 1 else -1
                incoming.animate().scaleX(DRIFT_SCALE).scaleY(DRIFT_SCALE)
                    .translationX(direction * width * (DRIFT_SCALE - 1) / 2).setDuration(driftMillis).start()
            }
        }.start()
        if (old != null && old !== bitmap) old.recycle()
    }

    /** Plays [file] on black; [onEnd] when it ends or can't play. */
    fun showVideo(file: File, sound: Boolean, loop: Boolean, onEnd: () -> Unit) {
        ++generation
        note.visibility = View.GONE
        view.visibility = View.VISIBLE
        images.forEach { it.animate().cancel(); it.alpha = 0f; it.setImageDrawable(null) }
        bitmaps.forEachIndexed { i, b -> b?.recycle(); bitmaps[i] = null }
        video.visibility = View.VISIBLE
        video.setOnPreparedListener { player ->
            player.isLooping = loop
            if (!sound) player.setVolume(0f, 0f)
            video.start()
        }
        video.setOnCompletionListener { onEnd() }
        video.setOnErrorListener { _, what, extra ->
            AppLog.w(TAG, "Video ${file.name} failed ($what/$extra)")
            stopVideo()
            onEnd()
            true
        }
        video.setVideoPath(file.path)
    }

    /** Black with a short message: nothing to show yet. */
    fun showNote(text: String) {
        ++generation
        stopVideo()
        images.forEach { it.animate().cancel(); it.alpha = 0f; it.setImageDrawable(null) }
        bitmaps.forEachIndexed { i, b -> b?.recycle(); bitmaps[i] = null }
        note.text = text
        note.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        view.visibility = View.VISIBLE
    }

    fun hide() {
        ++generation
        stopVideo()
        showClock(false)
        images.forEach { it.animate().cancel(); it.alpha = 0f; it.setImageDrawable(null) }
        bitmaps.forEachIndexed { i, b -> b?.recycle(); bitmaps[i] = null }
        note.visibility = View.GONE
        view.visibility = View.GONE
    }

    private fun stopVideo() {
        if (video.visibility != View.VISIBLE) return
        video.setOnCompletionListener(null)
        video.setOnErrorListener(null)
        video.stopPlayback()
        video.visibility = View.GONE
    }

    fun release() {
        hide()
        decoder.shutdownNow()
    }

    private companion object {
        const val TAG = "MediaStage"
        const val FADE_MILLIS = 900L
        const val DRIFT_SCALE = 1.06f

        /** Decodes [file] small enough for a screen whose longer side is [side], in 16-bit colour. */
        fun decode(file: File, side: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        }
    }
}
