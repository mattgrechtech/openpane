package io.github.mattgrechtech.openpane.control

import io.github.mattgrechtech.openpane.diag.AppLog
import org.json.JSONObject
import java.time.Instant

/**
 * A "now playing" pop-up that follows a Home Assistant media player: artwork, title, artist and
 * progress, refreshed every couple of seconds, closed once the player has stopped for a while.
 *
 * [fetch] reads an entity's state from Home Assistant (blocking, off the main thread), [base] is
 * Home Assistant's address, and [show]/[clear] put the pop-up on screen. [card] is pure, for tests.
 */
class NowPlaying(
    private val fetch: (String) -> JSONObject?,
    private val base: () -> String?,
    private val show: (JSONObject) -> Unit,
    private val clear: () -> Unit,
) {
    @Volatile private var entity: String? = null
    private var thread: Thread? = null

    @Synchronized
    fun start(entity: String, extra: JSONObject) {
        stop()
        this.entity = entity
        thread = Thread({ follow(entity, extra) }, "now-playing").also { it.start() }
    }

    @Synchronized
    fun stop() {
        entity = null
        thread?.interrupt()
        thread = null
    }

    private fun follow(entity: String, extra: JSONObject) {
        var stoppedSince = 0L
        AppLog.i(TAG, "Following $entity")
        try {
            while (this.entity == entity) {
                val state = fetch(entity)
                val card = state?.let { card(it, base(), System.currentTimeMillis(), extra) }
                if (card != null) {
                    stoppedSince = 0
                    show(card)
                } else {
                    if (stoppedSince == 0L) stoppedSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - stoppedSince >= GONE_AFTER_MILLIS) break
                }
                Thread.sleep(REFRESH_MILLIS)
            }
        } catch (e: InterruptedException) {
            return
        }
        if (this.entity == entity) {
            AppLog.i(TAG, "$entity stopped playing")
            clear()
            this.entity = null
        }
    }

    companion object {
        const val POPUP_ID = "now_playing"
        private const val TAG = "NowPlaying"
        private const val REFRESH_MILLIS = 2_000L
        private const val GONE_AFTER_MILLIS = 30_000L

        /**
         * The pop-up for a media player's [state] at [now], or null when it isn't playing or
         * paused. [extra] (corner, size and so on) is copied in.
         */
        fun card(state: JSONObject, base: String?, now: Long, extra: JSONObject = JSONObject()): JSONObject? {
            val playing = state.optString("state")
            if (playing != "playing" && playing != "paused") return null
            val a = state.optJSONObject("attributes") ?: JSONObject()
            val title = a.optString("media_title").ifEmpty { a.optString("friendly_name") }
            val artist = listOf(a.optString("media_artist"), a.optString("media_album_name")).filter { it.isNotEmpty() }.joinToString(" · ")
            val card = JSONObject(extra.toString())
                .put("id", POPUP_ID)
                .put("title", title.ifEmpty { "Now playing" })
                .put("message", if (playing == "paused") listOf("Paused", artist).filter { it.isNotEmpty() }.joinToString(" · ") else artist)
                .put("source", a.optString("app_name").ifEmpty { a.optString("friendly_name") })
                .put("icon", if (playing == "paused") "mdi:pause" else "mdi:music")
                .put("duration", 0)
            val picture = a.optString("entity_picture")
            if (picture.startsWith("http")) card.put("image", picture)
            else if (picture.startsWith("/") && base != null) card.put("image", base + picture)
            progress(a, playing == "playing", now)?.let { card.put("progress", it) }
            return card
        }

        /** Percent through the track, counting on from when Home Assistant last saw the position. */
        private fun progress(a: JSONObject, running: Boolean, now: Long): Int? {
            val duration = a.optDouble("media_duration", 0.0)
            if (duration <= 0 || !a.has("media_position")) return null
            var position = a.optDouble("media_position", 0.0)
            if (running) {
                val updated = runCatching { Instant.parse(a.optString("media_position_updated_at")).toEpochMilli() }.getOrNull()
                if (updated != null) position += (now - updated) / 1000.0
            }
            return (position / duration * 100).toInt().coerceIn(0, 100)
        }
    }
}
