package io.github.mattgrechtech.tvkiosk.web

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class PlannerTest {

    private val brisbane = ZoneId.of("Australia/Brisbane")

    @Test
    fun eventsAreNormalisedAndSorted() {
        val answer = JSONArray(
            """[
              {"summary":"Swimming","start":{"dateTime":"2026-11-02T07:30:00+00:00"},"end":{"dateTime":"2026-11-02T08:30:00+00:00"},"location":"Pool"},
              {"summary":"School holidays","start":{"date":"2026-11-01"},"end":{"date":"2026-11-03"}},
              {"summary":"","start":{"date":"2026-11-01"}},
              {"summary":"No start"}
            ]"""
        )
        val events = Planner.events("calendar.family", answer, brisbane)
        assertEquals(2, events.size)
        assertEquals("School holidays", events[0].getString("summary"))
        assertEquals(true, events[0].getBoolean("all_day"))
        assertEquals("2026-11-03", events[0].getString("end"))
        // 07:30 UTC is 17:30 in Brisbane.
        assertEquals("2026-11-02T17:30", events[1].getString("start"))
        assertEquals("Pool", events[1].getString("location"))
        assertEquals("calendar.family", events[1].getString("calendar"))
    }

    @Test
    fun onlyOpenTodoItems() {
        val items = Planner.todoItems(
            JSONArray(
                """[
                  {"uid":"1","summary":"Milk","status":"needs_action"},
                  {"uid":"2","summary":"Bread","status":"completed"},
                  {"uid":"3","summary":"  ","status":"needs_action"},
                  {"uid":"4","summary":"Bins out","status":"needs_action","due":"2026-11-01"}
                ]"""
            )
        )
        assertEquals(2, items.length())
        assertEquals("Milk", items.getJSONObject(0).getString("summary"))
        assertEquals("2026-11-01", items.getJSONObject(1).getString("due"))
    }
}
