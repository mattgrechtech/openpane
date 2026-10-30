package io.github.mattgrechtech.tvkiosk.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {

    @Test fun rotatesAfterTheIntervalWhenNobodyIsUsingTheRemote() {
        assertFalse(Schedule.rotationDue(now = 29_000, lastChange = 0, lastInteraction = -100_000, intervalSeconds = 30))
        assertTrue(Schedule.rotationDue(now = 30_000, lastChange = 0, lastInteraction = -100_000, intervalSeconds = 30))
    }

    @Test fun theRemoteHoldsThePageForAtLeastAMinute() {
        // Interval 30 s, but a key was pressed 40 s ago: stay.
        assertFalse(Schedule.rotationDue(now = 100_000, lastChange = 0, lastInteraction = 60_000, intervalSeconds = 30))
        assertTrue(Schedule.rotationDue(now = 121_000, lastChange = 0, lastInteraction = 60_000, intervalSeconds = 30))
        // Interval 5 min: the hold is the interval.
        assertFalse(Schedule.rotationDue(now = 400_000, lastChange = 0, lastInteraction = 200_000, intervalSeconds = 300))
    }

    @Test fun offNeverRotates() {
        assertFalse(Schedule.rotationDue(now = Long.MAX_VALUE / 2, lastChange = 0, lastInteraction = 0, intervalSeconds = 0))
    }

    @Test fun nextPageWrapsRound() {
        assertEquals(1, Schedule.nextIndex(0, 3))
        assertEquals(0, Schedule.nextIndex(2, 3))
        assertEquals(0, Schedule.nextIndex(5, 0))
    }

    @Test fun nightWindowCanCrossMidnight() {
        assertTrue(Schedule.inNightWindow(23, from = 23, to = 6))
        assertTrue(Schedule.inNightWindow(2, from = 23, to = 6))
        assertFalse(Schedule.inNightWindow(6, from = 23, to = 6))
        assertFalse(Schedule.inNightWindow(12, from = 23, to = 6))
    }

    @Test fun nightWindowWithinADay() {
        assertTrue(Schedule.inNightWindow(1, from = 1, to = 5))
        assertFalse(Schedule.inNightWindow(5, from = 1, to = 5))
        assertFalse(Schedule.inNightWindow(0, from = 1, to = 5))
    }

    @Test fun noNightWindowWhenOffOrEmpty() {
        assertFalse(Schedule.inNightWindow(3, from = -1, to = 7))
        assertFalse(Schedule.inNightWindow(7, from = 7, to = 7))
    }

    @Test fun burnInGuardLoopsThroughSmallOffsets() {
        val step = Schedule.BURN_IN_STEP_MILLIS
        val offsets = (0 until 9).map { Schedule.burnInOffset(it * step, 8) }
        assertEquals(9, offsets.toSet().size)
        assertTrue(offsets.all { (x, y) -> x in -8..8 && y in -8..8 })
        assertEquals(0 to 0, Schedule.burnInOffset(0, 8))
        assertEquals(offsets[0], Schedule.burnInOffset(9 * step, 8))
        assertEquals(offsets[1], Schedule.burnInOffset(step + step / 2, 8))
    }
}
