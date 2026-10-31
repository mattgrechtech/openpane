package io.github.mattgrechtech.openpane.signage

import io.github.mattgrechtech.openpane.settings.Keys
import java.time.DayOfWeek
import java.util.Locale

/**
 * The signage playlist (`kiosk:signage`): what to show, for how long, and when. One item a line:
 *
 * ```
 * 20 /local/signage/menu.jpg
 * 0 /local/signage/promo.mp4 sound=on
 * 60 /lovelace/specials hours=11:00-14:00
 * 30 https://example.com/news days=mon-fri hours=07:00-18:00
 * ```
 *
 * Seconds first (5-3600; 0 plays a video to its end), then a picture, a video or a page: an
 * http(s) URL, a path on the Home Assistant server, or a built-in page. Pictures and videos are
 * kept on the device ([MediaCache]) so they play without the network. `days` and `hours` limit
 * when an item plays; an hours window may cross midnight. No Android dependencies.
 */
object Playlist {

    enum class Kind { IMAGE, VIDEO, PAGE }

    data class Item(
        val seconds: Int,
        /** As written: an http(s) URL, a path on the Home Assistant server, or a built-in page. */
        val source: String,
        val kind: Kind,
        /** Days it plays; empty means every day. */
        val days: Set<DayOfWeek> = emptySet(),
        /** Minutes after midnight it starts and stops playing; null means all day. */
        val from: Int? = null,
        val to: Int? = null,
        /** A video's own sound; off by default (signage is usually silent). */
        val sound: Boolean = false,
    ) {
        val media: Boolean get() = kind != Kind.PAGE

        fun playsAt(day: DayOfWeek, minuteOfDay: Int): Boolean {
            if (from == null || to == null) return days.isEmpty() || day in days
            return if (from <= to) (days.isEmpty() || day in days) && minuteOfDay in from until to
            // Past midnight: the late part belongs to the day it started on.
            else if (minuteOfDay >= from) days.isEmpty() || day in days
            else (minuteOfDay < to) && (days.isEmpty() || day.minus(1) in days)
        }
    }

    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val VIDEOS = setOf("mp4", "webm", "mkv", "m4v", "3gp")
    private val TIME = Regex("([01]?\\d|2[0-3]):([0-5]\\d)")
    private val DAY_NAMES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    const val MAX_ITEMS = 100

    /** What a source is, from its file extension: a picture, a video, or (anything else) a page. */
    fun kindOf(source: String): Kind {
        if (source.startsWith("kiosk:")) return Kind.PAGE
        val ext = source.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return when (ext) {
            in IMAGES -> Kind.IMAGE
            in VIDEOS -> Kind.VIDEO
            else -> Kind.PAGE
        }
    }

    fun parse(text: String): List<Item> = text.lines().mapNotNull { parseLine(it).getOrNull() }.take(MAX_ITEMS)

    /** What's wrong with [text], or null when every line is an item. */
    fun problem(text: String): String? {
        var count = 0
        for ((index, line) in text.lines().withIndex()) {
            if (line.isBlank()) continue
            parseLine(line).onFailure { return "line ${index + 1}: ${it.message}" }
            if (++count > MAX_ITEMS) return "at most $MAX_ITEMS items"
        }
        return null
    }

    private fun parseLine(line: String): Result<Item> {
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) return fail("needs seconds and a picture, video or page, like: 20 /local/menu.jpg")
        val seconds = parts[0].toIntOrNull() ?: return fail("must start with seconds, like 20")
        val source = parts[1]
        val pageLike = source.startsWith("http://") || source.startsWith("https://") || source.startsWith("/")
        if (!pageLike && source != Keys.PLANNER_PAGE) {
            return fail("\"$source\" must start with http://, https:// or / (or be ${Keys.PLANNER_PAGE})")
        }
        val kind = kindOf(source)
        if (seconds == 0 && kind != Kind.VIDEO) return fail("only a video can have 0 seconds (to play to its end)")
        if (seconds != 0 && seconds !in 5..3600) return fail("seconds must be 5-3600")
        var item = Item(seconds, source, kind)
        for (part in parts.drop(2)) {
            val name = part.substringBefore('=', "")
            val value = part.substringAfter('=', "").lowercase(Locale.ROOT)
            item = when (name) {
                "days" -> days(value)?.let { item.copy(days = it) } ?: return fail("days must be like mon-fri or sat,sun")
                "hours" -> {
                    val range = value.split('-')
                    val from = range.getOrNull(0)?.let { minutes(it) }
                    val to = range.getOrNull(1)?.let { minutes(it) }
                    if (range.size != 2 || from == null || to == null || from == to) return fail("hours must be like 07:00-18:00")
                    item.copy(from = from, to = to)
                }
                "sound" -> when (value) {
                    "on" -> item.copy(sound = true)
                    "off" -> item.copy(sound = false)
                    else -> return fail("sound must be on or off")
                }
                else -> return fail("doesn't know \"$part\"")
            }
        }
        return Result.success(item)
    }

    private fun minutes(text: String): Int? =
        TIME.matchEntire(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    /** "mon-fri", "sat,sun", "mon,wed-fri"; null if any part isn't a day. */
    private fun days(text: String): Set<DayOfWeek>? {
        val days = mutableSetOf<DayOfWeek>()
        for (part in text.split(',')) {
            val ends = part.split('-')
            val first = DAY_NAMES.indexOf(ends[0])
            val last = DAY_NAMES.indexOf(ends.getOrElse(1) { ends[0] })
            if (ends.size > 2 || first < 0 || last < 0) return null
            var i = first
            while (true) {
                days += DayOfWeek.of(i + 1)
                if (i == last) break
                i = (i + 1) % 7
            }
        }
        return days
    }

    private fun fail(message: String) = Result.failure<Item>(IllegalArgumentException(message))

    /**
     * The index of the next item to show after [after] (-1 to start), among those playing now and
     * [ready] (a picture or video not yet on the device isn't). Null when nothing can play.
     */
    fun next(items: List<Item>, after: Int, day: DayOfWeek, minuteOfDay: Int, ready: (Item) -> Boolean = { true }): Int? {
        if (items.isEmpty()) return null
        for (step in 1..items.size) {
            val index = Math.floorMod(after + step, items.size)
            val item = items[index]
            if (item.playsAt(day, minuteOfDay) && ready(item)) return index
        }
        return null
    }
}
