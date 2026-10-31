package io.github.mattgrechtech.openpane.signage

import io.github.mattgrechtech.openpane.signage.Playlist.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class PlaylistTest {

    private val text = """
        20 /local/signage/menu.jpg
        0 /local/signage/promo.mp4 sound=on

        60 /lovelace/specials hours=11:00-14:00
        30 https://example.com/news days=mon-fri hours=22:00-02:00
        15 kiosk:planner days=sat,sun
    """.trimIndent()

    @Test
    fun parsesItems() {
        val items = Playlist.parse(text)
        assertNull(Playlist.problem(text))
        assertEquals(listOf(Kind.IMAGE, Kind.VIDEO, Kind.PAGE, Kind.PAGE, Kind.PAGE), items.map { it.kind })
        assertTrue(items[1].sound)
        assertEquals(11 * 60, items[2].from)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), items[4].days)
        assertEquals(5, items[3].days.size)
    }

    @Test
    fun kindsFromTheAddress() {
        assertEquals(Kind.IMAGE, Playlist.kindOf("https://cdn.example.com/a/b.JPG?v=2"))
        assertEquals(Kind.VIDEO, Playlist.kindOf("/local/x.webm"))
        assertEquals(Kind.PAGE, Playlist.kindOf("/lovelace/0"))
        assertEquals(Kind.PAGE, Playlist.kindOf("https://example.com/menu.html"))
    }

    @Test
    fun explainsProblems() {
        assertNotNull(Playlist.problem("menu.jpg"))
        assertNotNull(Playlist.problem("0 /local/menu.jpg"))
        assertNotNull(Playlist.problem("2 /local/menu.jpg"))
        assertNotNull(Playlist.problem("20 ftp://x/menu.jpg"))
        assertNotNull(Playlist.problem("20 /local/menu.jpg days=someday"))
        assertNotNull(Playlist.problem("20 /local/menu.jpg hours=9-5"))
        assertNotNull(Playlist.problem("20 /local/menu.jpg colour=red"))
        assertNull(Playlist.problem("20 /local/menu.jpg days=fri-mon"))
        assertEquals(4, Playlist.parse("20 /local/menu.jpg days=fri-mon").single().days.size)
    }

    @Test
    fun windowsAcrossMidnightBelongToTheStartDay() {
        val late = Playlist.parse("30 https://example.com/news days=fri hours=22:00-02:00").single()
        assertTrue(late.playsAt(DayOfWeek.FRIDAY, 23 * 60))
        assertTrue(late.playsAt(DayOfWeek.SATURDAY, 60))
        assertFalse(late.playsAt(DayOfWeek.FRIDAY, 60))
        assertFalse(late.playsAt(DayOfWeek.SATURDAY, 23 * 60))
        assertFalse(late.playsAt(DayOfWeek.FRIDAY, 12 * 60))
    }

    @Test
    fun nextSkipsWhatCantPlay() {
        val items = Playlist.parse(text)
        // Monday noon: menu, promo, specials and the news window (not now), planner (weekends).
        assertEquals(0, Playlist.next(items, -1, DayOfWeek.MONDAY, 12 * 60))
        assertEquals(2, Playlist.next(items, 1, DayOfWeek.MONDAY, 12 * 60))
        assertEquals(0, Playlist.next(items, 2, DayOfWeek.MONDAY, 12 * 60))
        // The video isn't on the device yet.
        assertEquals(2, Playlist.next(items, 0, DayOfWeek.MONDAY, 12 * 60) { it.kind != Kind.VIDEO })
        // Nothing playable.
        assertNull(Playlist.next(items, -1, DayOfWeek.MONDAY, 12 * 60) { false })
        assertNull(Playlist.next(emptyList(), -1, DayOfWeek.MONDAY, 0))
    }
}
