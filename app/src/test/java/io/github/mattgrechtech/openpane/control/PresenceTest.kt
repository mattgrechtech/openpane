package io.github.mattgrechtech.openpane.control

import io.github.mattgrechtech.openpane.control.Presence.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {

    private val minute = 60_000L

    private fun presence(restMinutes: Long = 5) = Presence().apply { restAfterMillis = restMinutes * minute }

    @Test
    fun neverRestsWhenOff() {
        val p = presence(0)
        assertFalse(p.restDue(10 * 60 * minute))
    }

    @Test
    fun restsAfterQuietPeriod() {
        val p = presence()
        p.motion(0)
        assertFalse(p.restDue(5 * minute - 1))
        assertTrue(p.restDue(5 * minute))
    }

    @Test
    fun motionRestartsTheTimer() {
        val p = presence()
        p.motion(0)
        assertEquals(Result.AWAKE, p.motion(4 * minute))
        assertFalse(p.restDue(8 * minute))
        assertTrue(p.restDue(9 * minute))
    }

    @Test
    fun motionWakesFromRest() {
        val p = presence()
        p.rest(5 * minute)
        assertTrue(p.resting)
        assertEquals(Result.WOKE, p.motion(6 * minute))
        assertFalse(p.resting)
        assertFalse(p.restDue(10 * minute))
    }

    @Test
    fun graceStopsAnInstantWake() {
        val p = presence()
        p.rest(5 * minute)
        assertEquals(Result.IGNORED_GRACE, p.motion(5 * minute + Presence.GRACE_MILLIS - 1))
        assertTrue(p.resting)
        assertEquals(Result.WOKE, p.motion(5 * minute + Presence.GRACE_MILLIS))
    }

    @Test
    fun presenceHoldsUntilCleared() {
        val p = presence()
        p.motion(0, present = true)
        assertFalse(p.restDue(60 * minute))
        assertEquals(Result.CLEARED, p.motion(60 * minute, present = false))
        assertFalse(p.restDue(64 * minute))
        assertTrue(p.restDue(65 * minute))
    }

    @Test
    fun clearingNeverWakes() {
        val p = presence()
        p.rest(5 * minute)
        assertEquals(Result.CLEARED, p.motion(6 * minute, present = false))
        assertTrue(p.resting)
    }

    @Test
    fun quietHoursIgnoreMotionButKeepPresence() {
        val p = presence()
        p.rest(5 * minute)
        assertEquals(Result.IGNORED_QUIET, p.motion(6 * minute, present = true, quiet = true))
        assertTrue(p.resting)
        assertTrue(p.present)
    }

    @Test
    fun remoteWakesAndRestartsTheTimer() {
        val p = presence()
        p.rest(5 * minute)
        assertTrue(p.awake(6 * minute))
        assertFalse(p.resting)
        assertFalse(p.awake(7 * minute))
        assertTrue(p.restDue(12 * minute))
    }

    @Test
    fun noSecondRestWhileResting() {
        val p = presence()
        p.rest(5 * minute)
        assertFalse(p.restDue(60 * minute))
    }
}
