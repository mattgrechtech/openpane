package io.github.mattgrechtech.openpane.web

import android.os.Handler
import android.os.Looper
import io.github.mattgrechtech.openpane.diag.AppLog
import io.github.mattgrechtech.openpane.settings.Keys
import io.github.mattgrechtech.openpane.settings.Settings
import io.github.mattgrechtech.openpane.signage.MediaLibrary
import io.github.mattgrechtech.openpane.signage.Playlist
import java.time.LocalDateTime

/**
 * Plays the signage playlist (`kiosk:signage`): pictures and videos on the [stage] from the
 * device's copies, pages in the WebView ([openPage]). Items outside their days and hours are
 * skipped; with nothing to play the screen stays black and it looks again shortly.
 */
class SignageRunner(
    private val stage: MediaStage,
    private val library: () -> MediaLibrary,
    private val settings: Settings,
    /** Shows a page item in the WebView (a URL, already made absolute). */
    private val openPage: (String) -> Unit,
    /** Empties the WebView behind pictures and videos, so a page isn't kept running unseen. */
    private val blankPage: () -> Unit,
    /** Whether pages can load now; offline, only pictures and videos play. */
    private val online: () -> Boolean,
    private val nothingText: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private var index = -1
    private var onPage = false
    var running = false
        private set
    /** What's on screen, for the state ("menu.jpg 2/5"). */
    var nowShowing: String? = null
        private set

    private val advance = Runnable { step() }

    fun start() {
        if (running) return
        running = true
        index = -1
        AppLog.i(TAG, "Signage started")
        library().refresh()
        step()
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(advance)
        stage.hide()
        nowShowing = null
        AppLog.i(TAG, "Signage stopped")
    }

    /** The next item now (the remote's right arrow). */
    fun skip() {
        if (running) step()
    }

    /** The page item couldn't load: on to the next item, no error screen. */
    fun pageFailed() {
        if (running && onPage) {
            AppLog.w(TAG, "Signage page didn't load; skipping it")
            main.removeCallbacks(advance)
            main.postDelayed(advance, FAILED_PAGE_MILLIS)
        }
    }

    private fun step() {
        main.removeCallbacks(advance)
        if (!running) return
        val items = Playlist.parse(settings[Keys.SIGNAGE_PLAYLIST])
        val now = LocalDateTime.now()
        val isOnline = online()
        val next = Playlist.next(items, index, now.dayOfWeek, now.hour * 60 + now.minute) { item ->
            if (item.media) library().signageFile(item) != null else isOnline
        }
        if (next == null) {
            index = -1
            onPage = false
            nowShowing = null
            blankPage()
            stage.showNote(if (items.isEmpty()) nothingText else "")
            main.postDelayed(advance, IDLE_RETRY_MILLIS)
            return
        }
        val item = items[next]
        val alone = Playlist.next(items, next, now.dayOfWeek, now.hour * 60 + now.minute) { true } == next
        index = next
        nowShowing = "${MediaStageNames.of(item.source)} ${next + 1}/${items.size}"
        when (item.kind) {
            Playlist.Kind.IMAGE -> {
                showMedia()
                stage.showImage(library().signageFile(item)!!, photo = false) { main.postDelayed(advance, FAILED_PAGE_MILLIS) }
            }
            Playlist.Kind.VIDEO -> {
                showMedia()
                stage.showVideo(library().signageFile(item)!!, item.sound, loop = alone && item.seconds == 0) {
                    main.post { if (running && index == next) step() }
                }
            }
            Playlist.Kind.PAGE -> {
                onPage = true
                stage.hide()
                val url = if (item.source.startsWith("/")) settings.haBaseUrl()?.let { it + item.source } else item.source
                if (url == null) {
                    main.postDelayed(advance, FAILED_PAGE_MILLIS)
                    return
                }
                openPage(url)
            }
        }
        if (item.seconds > 0) main.postDelayed(advance, item.seconds * 1000L)
    }

    private fun showMedia() {
        if (onPage) blankPage()
        onPage = false
    }

    private companion object {
        const val TAG = "Signage"
        const val IDLE_RETRY_MILLIS = 30_000L
        const val FAILED_PAGE_MILLIS = 3_000L
    }
}

/**
 * The photo frame (`kiosk:photos`, or the "photos" rest): the device's copies of the pictures from
 * [Keys.PHOTO_SOURCE], one after another, crossfading and drifting slowly, with the time.
 */
class PhotoFrame(
    private val stage: MediaStage,
    private val library: () -> MediaLibrary,
    private val settings: Settings,
    private val noPhotosText: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private var queue = ArrayDeque<java.io.File>()
    var running = false
        private set

    private val advance = Runnable { step() }

    /** Starts the frame. False (and nothing shown) when there are no pictures yet and [quiet]. */
    fun start(quiet: Boolean = false): Boolean {
        if (running) return true
        library().refresh()
        if (quiet && library().photos().isEmpty()) return false
        running = true
        queue.clear()
        stage.showClock(settings[Keys.PHOTO_CLOCK])
        AppLog.i(TAG, "Photo frame started")
        step()
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(advance)
        stage.hide()
        AppLog.i(TAG, "Photo frame stopped")
    }

    fun skip() {
        if (running) step()
    }

    private fun step() {
        main.removeCallbacks(advance)
        if (!running) return
        if (queue.isEmpty()) {
            val all = library().photos()
            queue = ArrayDeque(if (settings[Keys.PHOTO_SHUFFLE]) all.shuffled() else all)
        }
        val file = queue.removeFirstOrNull()
        if (file == null) {
            stage.showNote(noPhotosText)
            stage.showClock(settings[Keys.PHOTO_CLOCK])
            main.postDelayed(advance, EMPTY_RETRY_MILLIS)
            return
        }
        val seconds = settings[Keys.PHOTO_SECONDS]
        if (!file.isFile) {
            main.post(advance)
            return
        }
        stage.showImage(file, photo = true, driftMillis = seconds * 1000L) {
            main.removeCallbacks(advance)
            main.postDelayed(advance, FAILED_MILLIS)
        }
        main.postDelayed(advance, seconds * 1000L)
    }

    private companion object {
        const val TAG = "PhotoFrame"
        const val EMPTY_RETRY_MILLIS = 20_000L
        const val FAILED_MILLIS = 2_000L
    }
}

/** Short names for logs and the state: the file or page name, never a host or a signed query. */
internal object MediaStageNames {
    fun of(source: String): String =
        source.substringBefore('?').trimEnd('/').substringAfterLast('/').take(60).ifEmpty { source.take(60) }
}
