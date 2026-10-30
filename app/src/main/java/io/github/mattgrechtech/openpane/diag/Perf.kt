package io.github.mattgrechtech.openpane.diag

import org.json.JSONObject

/**
 * How long the things people notice take, against a budget each: the screen waking, a pop-up
 * appearing, a camera's first frame, a page loading. Keeps the last few of each in memory (no
 * timers, no files), for diagnostics, the admin page and the smoke test. No Android dependencies.
 */
object Perf {

    enum class Metric(val key: String, val budgetMillis: Long) {
        /** A wake command to the dashboard's next frame. */
        WAKE("wake", 150),
        /** A pop-up command to the pop-up's first frame on screen. */
        POPUP("popup", 300),
        /** A camera pop-up command to the live picture's first frame. */
        CAMERA_FIRST_FRAME("camera_first_frame", 1500),
        /** A page starting to load to finished. */
        PAGE_LOAD("page_load", 8000),
    }

    private const val KEEP = 20
    private val samples = Metric.entries.associateWith { ArrayDeque<Long>(KEEP) }

    fun record(metric: Metric, millis: Long) {
        if (millis < 0) return
        val list = samples.getValue(metric)
        synchronized(list) {
            if (list.size == KEEP) list.removeFirst()
            list.addLast(millis)
        }
        if (millis > metric.budgetMillis) AppLog.w("Perf", "${metric.key} took $millis ms (budget ${metric.budgetMillis} ms)")
    }

    /** Each metric's last, median and worst of the recent samples, its budget, and whether the last was over. */
    fun toJson(): JSONObject {
        val json = JSONObject()
        for (metric in Metric.entries) {
            val list = samples.getValue(metric)
            val copy = synchronized(list) { list.toList() }
            if (copy.isEmpty()) continue
            val sorted = copy.sorted()
            json.put(
                metric.key,
                JSONObject()
                    .put("last", copy.last())
                    .put("median", sorted[sorted.size / 2])
                    .put("worst", sorted.last())
                    .put("count", copy.size)
                    .put("budget", metric.budgetMillis)
                    .put("over_budget", copy.last() > metric.budgetMillis),
            )
        }
        return json
    }

    /** For tests. */
    fun clear() = samples.values.forEach { synchronized(it) { it.clear() } }
}
