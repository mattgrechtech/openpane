package io.github.mattgrechtech.openpane.diag

import io.github.mattgrechtech.openpane.diag.Perf.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerfTest {

    @Before fun reset() = Perf.clear()

    @Test fun keepsLastMedianWorstAndBudget() {
        for (ms in listOf(100L, 90L, 400L, 120L)) Perf.record(Metric.POPUP, ms)
        val popup = Perf.toJson().getJSONObject("popup")
        assertEquals(120L, popup.getLong("last"))
        assertEquals(120L, popup.getLong("median"))
        assertEquals(400L, popup.getLong("worst"))
        assertEquals(4, popup.getInt("count"))
        assertEquals(300L, popup.getLong("budget"))
        assertFalse(popup.getBoolean("over_budget"))
    }

    @Test fun overBudgetAndOnlyRecentSamples() {
        repeat(30) { Perf.record(Metric.WAKE, 10) }
        Perf.record(Metric.WAKE, 500)
        val wake = Perf.toJson().getJSONObject("wake")
        assertEquals(20, wake.getInt("count"))
        assertTrue(wake.getBoolean("over_budget"))
        assertFalse(Perf.toJson().has("camera_first_frame"))
    }
}
