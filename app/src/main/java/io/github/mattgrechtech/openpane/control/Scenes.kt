package io.github.mattgrechtech.openpane.control

import io.github.mattgrechtech.openpane.settings.Keys
import java.util.Locale

/**
 * Scenes by time of day: from a time, show a page, at a brightness, resting a certain way. One
 * line each, for example:
 *
 * ```
 * 06:30 /dashboard-family/morning brightness=90 rest=clock
 * 18:00 /dashboard-family/cameras rest_after=20
 * 21:30 brightness=30 rest=black rest_after=5
 * ```
 *
 * A scene lasts until the next one, wrapping past midnight. Anything a scene leaves out comes from
 * the normal settings. No Android dependencies.
 */
object Scenes {

    data class Scene(
        /** Minutes after midnight it starts. */
        val start: Int,
        /** An http(s) URL, or a path on the Home Assistant server ("/lovelace/0"). */
        val page: String? = null,
        val brightness: Int? = null,
        val restStyle: String? = null,
        val restAfterMinutes: Int? = null,
    ) {
        val label: String get() = String.format(Locale.ROOT, "%02d:%02d", start / 60, start % 60)
    }

    private val TIME = Regex("([01]?\\d|2[0-3]):([0-5]\\d)")

    /** The scenes in [text], sorted by start time. Lines with problems are left out (see [problem]). */
    fun parse(text: String): List<Scene> =
        text.lines().mapNotNull { line -> parseLine(line).getOrNull() }.sortedBy { it.start }

    /** What's wrong with [text], or null when every line is a scene. */
    fun problem(text: String): String? {
        val starts = mutableSetOf<Int>()
        for ((index, line) in text.lines().withIndex()) {
            if (line.isBlank()) continue
            val scene = parseLine(line).getOrElse { return "line ${index + 1}: ${it.message}" }
            if (!starts.add(scene.start)) return "line ${index + 1}: two scenes start at ${scene.label}"
        }
        return null
    }

    private fun parseLine(line: String): Result<Scene> {
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result.failure(IllegalArgumentException("empty"))
        val time = TIME.matchEntire(parts[0])
            ?: return Result.failure(IllegalArgumentException("must start with a time, like 07:00"))
        var scene = Scene(time.groupValues[1].toInt() * 60 + time.groupValues[2].toInt())
        for (part in parts.drop(1)) {
            val name = part.substringBefore('=', "")
            val value = part.substringAfter('=', "")
            scene = when {
                name.isEmpty() && (part.startsWith("http://") || part.startsWith("https://") || part.startsWith("/") || part in Keys.BUILT_IN_PAGES) ->
                    if (scene.page != null) return fail("has two pages") else scene.copy(page = part)
                name == "brightness" -> value.toIntOrNull()?.takeIf { it in -1..100 }?.let { scene.copy(brightness = it) }
                    ?: return fail("brightness must be 0-100, or -1 for the device's own")
                name == "rest" -> value.takeIf { it in Keys.REST_STYLES && it != "page" }?.let { scene.copy(restStyle = it) }
                    ?: return fail("rest must be black, dim, clock or photos")
                name == "rest_after" -> value.toIntOrNull()?.takeIf { it in 0..240 }?.let { scene.copy(restAfterMinutes = it) }
                    ?: return fail("rest_after must be 0-240 minutes")
                else -> return fail("doesn't know \"$part\"")
            }
        }
        return Result.success(scene)
    }

    private fun fail(message: String) = Result.failure<Scene>(IllegalArgumentException(message))

    /** The scene for [minuteOfDay]: the last one started, or yesterday's last one; null if none. */
    fun active(scenes: List<Scene>, minuteOfDay: Int): Scene? =
        scenes.lastOrNull { it.start <= minuteOfDay } ?: scenes.lastOrNull()
}
