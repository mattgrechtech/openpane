package io.github.mattgrechtech.openpane.signage

import android.content.Context
import android.os.SystemClock
import io.github.mattgrechtech.openpane.diag.AppLog
import io.github.mattgrechtech.openpane.settings.Keys
import io.github.mattgrechtech.openpane.settings.Settings
import io.github.mattgrechtech.openpane.web.HaAuth
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps signage's pictures and videos and the photo frame's pictures on the device ([MediaCache]),
 * from the settings: when they change, when a player starts, and every few hours.
 */
class MediaLibrary(context: Context, private val settings: Settings, private val auth: HaAuth) {

    private val metrics = context.resources.displayMetrics
    val cache = MediaCache(
        File(context.filesDir, "media"),
        maxBytes = { settings[Keys.MEDIA_CACHE_MB] * 1_048_576L },
        maxSide = maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(1280, 3840),
    ).also { cache -> cache.onChanged = { listeners.forEach { runCatching { it() } } } }

    /** Told (on the cache's thread) when a file arrives or goes. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var lastRefresh = 0L

    /** Brings the cache in line with the playlist and the photo source. Returns at once. */
    fun refresh() {
        lastRefresh = SystemClock.elapsedRealtime()
        cache.sync(SIGNAGE, Playlist.parse(settings[Keys.SIGNAGE_PLAYLIST]).filter { it.media }.distinctBy { it.source }.map { item ->
            MediaCache.Wanted(item.source, item.kind == Playlist.Kind.VIDEO) { source(item.source) }
        })
        val photos = settings[Keys.PHOTO_SOURCE]
        Thread({ syncPhotos(photos) }, "photo-browse").start()
    }

    /** Called about every half minute by the dashboard: a refresh every few hours. */
    fun tick() {
        if (SystemClock.elapsedRealtime() - lastRefresh >= REFRESH_MILLIS) refresh()
    }

    private fun syncPhotos(text: String) {
        val (folders, single) = Photos.parse(text)
        val pictures = if (folders.isEmpty()) single else {
            val base = settings.haBaseUrl() ?: return
            val token = token() ?: return
            try {
                single + Photos.browse(base, token, folders) { key -> cache.file(key) != null }
            } catch (e: Exception) {
                // Offline or signed out: the pictures already here keep showing.
                AppLog.w(TAG, "Can't list the photo folders: ${e.message ?: e.javaClass.simpleName}")
                return
            }
        }
        AppLog.i(TAG, "Photo frame: ${pictures.size} pictures")
        cache.sync(PHOTOS, pictures.map { picture ->
            MediaCache.Wanted(picture.key, video = false) { if (picture.needsResolve) null else source(picture.path) }
        })
    }

    /** Where to download [path] from: Home Assistant's own files with its token, anything else as is. */
    private fun source(path: String): MediaCache.Source? {
        val base = settings.haBaseUrl()
        return when {
            path.startsWith("/") -> base?.let { MediaCache.Source(it + path, token() ?: return null) }
            base != null && Settings.originOf(path) == base -> MediaCache.Source(path, token())
            else -> MediaCache.Source(path, null)
        }
    }

    private fun token(): String? = runCatching { auth.accessToken(force = false).accessToken }.getOrNull()

    fun signageFile(item: Playlist.Item): File? = cache.file(item.source)

    fun photos(): List<File> = cache.files(PHOTOS)

    companion object {
        private const val TAG = "MediaLibrary"
        private const val SIGNAGE = "signage"
        private const val PHOTOS = "photos"
        private const val REFRESH_MILLIS = 6 * 3_600_000L

        /** Whether anything needs the library: otherwise it's never made. */
        fun wanted(settings: Settings) = settings[Keys.SIGNAGE_PLAYLIST].isNotBlank() || settings[Keys.PHOTO_SOURCE].isNotBlank()
    }
}
