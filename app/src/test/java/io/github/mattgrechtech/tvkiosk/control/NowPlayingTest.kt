package io.github.mattgrechtech.tvkiosk.control

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class NowPlayingTest {

    private fun player(state: String, position: Double = 60.0, updatedAt: Long = 0) = JSONObject()
        .put("state", state)
        .put("attributes", JSONObject()
            .put("friendly_name", "Kitchen speaker")
            .put("media_title", "Song")
            .put("media_artist", "Band")
            .put("media_album_name", "Album")
            .put("app_name", "Music Assistant")
            .put("entity_picture", "/api/media_player_proxy/media_player.kitchen?token=abc")
            .put("media_duration", 240.0)
            .put("media_position", position)
            .put("media_position_updated_at", Instant.ofEpochMilli(updatedAt).toString()))

    @Test fun playingCardHasArtworkTextAndProgress() {
        val card = NowPlaying.card(player("playing", position = 60.0, updatedAt = 1_000_000), "http://ha.test:8123", 1_060_000, JSONObject().put("corner", "bottom_end"))!!
        assertEquals("now_playing", card.getString("id"))
        assertEquals("Song", card.getString("title"))
        assertEquals("Band · Album", card.getString("message"))
        assertEquals("Music Assistant", card.getString("source"))
        assertEquals("http://ha.test:8123/api/media_player_proxy/media_player.kitchen?token=abc", card.getString("image"))
        assertEquals(50, card.getInt("progress")) // 60 s + 60 s since the update, of 240 s
        assertEquals(0, card.getInt("duration"))
        assertEquals("bottom_end", card.getString("corner"))
    }

    @Test fun pausedDoesNotCountOn() {
        val card = NowPlaying.card(player("paused", position = 120.0, updatedAt = 0), null, 999_999_999)!!
        assertEquals(50, card.getInt("progress"))
        assertEquals("Paused · Band · Album", card.getString("message"))
    }

    @Test fun stoppedHasNoCard() {
        assertNull(NowPlaying.card(player("idle"), null, 0))
        assertNull(NowPlaying.card(player("off"), null, 0))
    }
}
