package io.github.mattgrechtech.tvkiosk.control

/**
 * When the dashboard rests (goes dark while nobody is around) and when motion wakes it.
 * Times are elapsed-realtime milliseconds from the caller; no Android dependencies.
 *
 * Home Assistant reports motion (a moment) or presence (on until cleared); the remote counts as
 * motion. With [restAfterMillis] above 0, the screen rests that long after the last of them,
 * never while someone is present.
 */
class Presence {

    enum class Result {
        /** The screen was resting and should wake. */
        WOKE,
        /** Not resting: the rest timer starts again. The caller still makes sure the screen is on. */
        AWAKE,
        /** Presence cleared: the rest timer starts now. */
        CLEARED,
        /** Just after resting began, so the change of light can't wake it straight back up. */
        IGNORED_GRACE,
        /** Quiet hours: motion doesn't wake the screen. */
        IGNORED_QUIET,
    }

    var restAfterMillis = 0L
    var lastActivity = 0L
        private set
    /** When motion or presence was last reported by Home Assistant; 0 is never. */
    var lastMotion = 0L
        private set
    var present = false
        private set
    var restingSince = NOT_RESTING
        private set
    val resting get() = restingSince != NOT_RESTING

    /** Motion, or a presence change when [present] isn't null. [quiet] is true in quiet hours. */
    fun motion(now: Long, present: Boolean? = null, quiet: Boolean = false): Result {
        if (present == false) {
            this.present = false
            lastActivity = now
            return Result.CLEARED
        }
        lastMotion = now
        if (present == true) this.present = true
        if (resting && now - restingSince < GRACE_MILLIS) return Result.IGNORED_GRACE
        if (quiet) return Result.IGNORED_QUIET
        lastActivity = now
        if (!resting) return Result.AWAKE
        restingSince = NOT_RESTING
        return Result.WOKE
    }

    /** The remote or a command woke the screen. Returns true if it was resting. */
    fun awake(now: Long): Boolean {
        lastActivity = now
        val was = resting
        restingSince = NOT_RESTING
        return was
    }

    /** True when it's time to rest: enabled, nobody present, and quiet for long enough. */
    fun restDue(now: Long): Boolean =
        restAfterMillis > 0 && !resting && !present && now - lastActivity >= restAfterMillis

    fun rest(now: Long) {
        restingSince = now
    }

    companion object {
        const val GRACE_MILLIS = 3_000L
        private const val NOT_RESTING = Long.MIN_VALUE
    }
}
