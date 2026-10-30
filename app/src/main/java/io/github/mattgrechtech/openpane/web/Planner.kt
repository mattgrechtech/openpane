package io.github.mattgrechtech.openpane.web

import io.github.mattgrechtech.openpane.diag.AppLog
import io.github.mattgrechtech.openpane.settings.Keys
import io.github.mattgrechtech.openpane.settings.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The family planner's data: the household's Home Assistant calendars and to-do lists, read with
 * the TV's own sign-in, shaped for the planner page (`assets/planner/`). Blocking: call off the
 * main thread.
 */
class Planner(private val settings: Settings, private val ha: HaApi) {

    @Volatile private var cached: Pair<Long, JSONObject>? = null

    /** Calendars and to-dos for [days] days from today (1-14). Cached for half a minute. */
    fun data(days: Int, now: ZonedDateTime = ZonedDateTime.now()): JSONObject {
        val span = days.coerceIn(1, MAX_DAYS)
        cached?.let { (at, json) -> if (System.currentTimeMillis() - at < CACHE_MILLIS && json.optInt("days") == span) return json }
        if (settings.isWebMode() || settings.haBaseUrl() == null) {
            return JSONObject().put("ok", false).put("error", "the planner needs the app signed in to Home Assistant")
        }
        val start = now.toLocalDate().atStartOfDay(now.zone)
        val end = start.plusDays(span.toLong())
        // No answer at all means Home Assistant is down or refused us: say so, rather than "no calendars".
        val all = ha.getJson("/api/calendars") as? JSONArray
            ?: return JSONObject().put("ok", false).put("error", "can't reach Home Assistant")
        val calendars = chosen(all, Keys.PLANNER_CALENDARS)
        val events = JSONArray()
        for (i in 0 until calendars.length()) {
            val calendar = calendars.getJSONObject(i)
            val id = calendar.getString("entity_id")
            val path = "/api/calendars/$id?start=${encode(start)}&end=${encode(end)}"
            val answer = ha.getJson(path) as? JSONArray ?: continue
            for (event in events(id, answer, now.zone)) events.put(event)
        }
        val board = settings[Keys.PLANNER_BOARD]
        val found = todos(board)
        val lists = (0 until found.length()).map { found.getJSONObject(it) }
        val result = JSONObject()
            .put("ok", true)
            .put("days", span)
            .put("start", start.toLocalDate().toString())
            .put("calendars", calendars)
            .put("events", events)
            .put("todos", JSONArray(lists.filter { it.optString("entity_id") != board }))
            .put("board", lists.firstOrNull { it.optString("entity_id") == board })
        cached = System.currentTimeMillis() to result
        return result
    }

    /** Adds a note to the family board (the [Keys.PLANNER_BOARD] to-do list). */
    fun addNote(text: String): JSONObject {
        val board = settings[Keys.PLANNER_BOARD]
        val note = text.trim()
        if (board.isEmpty()) return JSONObject().put("ok", false).put("error", "no family board set (planner_board)")
        if (note.isEmpty() || note.length > 200) return JSONObject().put("ok", false).put("error", "a note is 1-200 characters")
        val ok = ha.postJson("/api/services/todo/add_item", JSONObject().put("entity_id", board).put("item", note)) != null
        cached = null
        return JSONObject().put("ok", ok).apply { if (!ok) put("error", "Home Assistant didn't take it") }
    }

    /** Marks a to-do item done (or not done again), then forgets the cache so the page sees it. */
    fun setTodo(entity: String, uid: String, done: Boolean): JSONObject {
        if (!TODO_ENTITY.matches(entity) || uid.isEmpty() || uid.length > 200) {
            return JSONObject().put("ok", false).put("error", "entity must be a todo entity id, uid an item's uid")
        }
        if (entity != settings[Keys.PLANNER_BOARD] && allowed(entity, Keys.PLANNER_TODOS, todoNames().keys).not()) {
            return JSONObject().put("ok", false).put("error", "that list isn't on the planner")
        }
        val body = JSONObject().put("entity_id", entity).put("item", uid).put("status", if (done) "completed" else "needs_action")
        val ok = ha.postJson("/api/services/todo/update_item", body) != null
        cached = null
        return JSONObject().put("ok", ok).apply { if (!ok) put("error", "Home Assistant didn't take it") }
    }

    /** The planner's to-do lists, plus the [board] list whichever lists are chosen. */
    private fun todos(board: String): JSONArray {
        val names = todoNames()
        val ids = names.keys.filter { allowed(it, Keys.PLANNER_TODOS, names.keys) || it == board }
        val lists = JSONArray()
        if (ids.isEmpty()) return lists
        val body = JSONObject().put("entity_id", JSONArray(ids)).put("status", JSONArray(listOf("needs_action")))
        val answer = ha.postJson("/api/services/todo/get_items?return_response", body) as? JSONObject
        val response = answer?.optJSONObject("service_response") ?: return lists
        for (id in ids) {
            val items = response.optJSONObject(id)?.optJSONArray("items") ?: continue
            lists.put(JSONObject().put("entity_id", id).put("name", names[id]).put("items", todoItems(items)))
        }
        return lists
    }

    /** Every to-do list in Home Assistant, by entity id, with its name. */
    private fun todoNames(): Map<String, String> {
        val states = ha.getJson("/api/states") as? JSONArray ?: return emptyMap()
        val names = linkedMapOf<String, String>()
        for (i in 0 until states.length()) {
            val state = states.optJSONObject(i) ?: continue
            val id = state.optString("entity_id")
            if (!TODO_ENTITY.matches(id)) continue
            names[id] = state.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifEmpty { id }
        }
        return names
    }

    private fun chosen(all: JSONArray, key: io.github.mattgrechtech.openpane.settings.Key<String>): JSONArray {
        val ids = (0 until all.length()).mapNotNull { all.optJSONObject(it)?.optString("entity_id") }.toSet()
        val kept = JSONArray()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (allowed(item.optString("entity_id"), key, ids)) kept.put(item)
        }
        return kept
    }

    /** True when [id] is on the list in [key] (an empty list means all of them). */
    private fun allowed(id: String, key: io.github.mattgrechtech.openpane.settings.Key<String>, all: Collection<String>): Boolean {
        val wanted = Keys.splitList(settings[key])
        return id in all && (wanted.isEmpty() || id in wanted)
    }

    private fun encode(time: ZonedDateTime) =
        URLEncoder.encode(time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "UTF-8")

    companion object {
        private const val TAG = "Planner"
        const val MAX_DAYS = 14
        private const val CACHE_MILLIS = 30_000L
        private val TODO_ENTITY = Regex("todo\\.[a-z0-9_]{1,100}")

        /**
         * One calendar's events from Home Assistant, as the page wants them: calendar, summary,
         * start and end (local "2026-10-31T09:30" or a date for all-day), all_day, location.
         * Events without a summary or a start are left out.
         */
        fun events(calendar: String, answer: JSONArray, zone: ZoneId): List<JSONObject> {
            val events = mutableListOf<JSONObject>()
            for (i in 0 until answer.length()) {
                val event = answer.optJSONObject(i) ?: continue
                val summary = event.optString("summary").trim()
                val start = event.optJSONObject("start") ?: continue
                val end = event.optJSONObject("end") ?: start
                val allDay = start.has("date") && !start.has("dateTime")
                val from = time(start, zone) ?: continue
                val to = time(end, zone) ?: from
                if (summary.isEmpty()) continue
                events += JSONObject()
                    .put("calendar", calendar)
                    .put("summary", summary.take(200))
                    .put("start", from)
                    .put("end", to)
                    .put("all_day", allDay)
                    .put("location", event.optString("location").trim().take(200))
            }
            return events.sortedBy { it.getString("start") }
        }

        /** A start or end as local time ("2026-10-31T09:30") or a date ("2026-10-31"). */
        private fun time(value: JSONObject, zone: ZoneId): String? = try {
            when {
                value.has("dateTime") -> ZonedDateTime.parse(value.getString("dateTime"))
                    .withZoneSameInstant(zone).toLocalDateTime().withSecond(0).withNano(0).toString()
                value.has("date") -> LocalDate.parse(value.getString("date")).toString()
                else -> null
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Unreadable event time: ${e.javaClass.simpleName}")
            null
        }

        /** Open to-do items: uid, summary and due (if any), at most 50. */
        fun todoItems(items: JSONArray): JSONArray {
            val kept = JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val summary = item.optString("summary").trim()
                if (summary.isEmpty() || item.optString("status") == "completed") continue
                kept.put(
                    JSONObject().put("uid", item.optString("uid")).put("summary", summary.take(200))
                        .put("due", item.optString("due"))
                )
                if (kept.length() >= 50) break
            }
            return kept
        }
    }
}
