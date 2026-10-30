package io.github.mattgrechtech.openpane.control

/** When to change page and when the screen goes black at night. No Android dependencies. */
object Schedule {

    /** Someone using the remote holds the page for at least this long. */
    const val INTERACTION_HOLD_MILLIS = 60_000L

    /** After the remote wakes a scheduled black screen, it stays on this long without input. */
    const val NIGHT_WAKE_MILLIS = 5 * 60_000L

    /**
     * True when it's time for the next page: [intervalSeconds] have passed since the last change,
     * and nobody has pressed a key for at least as long (and at least a minute).
     */
    fun rotationDue(now: Long, lastChange: Long, lastInteraction: Long, intervalSeconds: Int): Boolean {
        if (intervalSeconds <= 0) return false
        val interval = intervalSeconds * 1000L
        return now - lastChange >= interval && now - lastInteraction >= maxOf(interval, INTERACTION_HOLD_MILLIS)
    }

    /** How often the burn-in guard moves the page. */
    const val BURN_IN_STEP_MILLIS = 2 * 60_000L

    private val BURN_IN_PATH = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)

    /**
     * Where the burn-in guard puts the page at [now]: a slow loop of small offsets (x, y), each
     * -[maxPx]..[maxPx], so no pixel shows the same thing all day.
     */
    fun burnInOffset(now: Long, maxPx: Int): Pair<Int, Int> {
        val (x, y) = BURN_IN_PATH[((now / BURN_IN_STEP_MILLIS) % BURN_IN_PATH.size).toInt()]
        return x * maxPx to y * maxPx
    }

    /** The page after [current] in a list of [count], wrapping round. */
    fun nextIndex(current: Int, count: Int): Int = if (count <= 0) 0 else (current + 1).mod(count)

    /**
     * True when [hour] is inside the nightly window from [from] (inclusive) to [to] (exclusive),
     * which may wrap past midnight. [from] of -1, or [from] equal to [to], means never.
     */
    fun inNightWindow(hour: Int, from: Int, to: Int): Boolean = when {
        from < 0 || from == to -> false
        from < to -> hour in from until to
        else -> hour >= from || hour < to
    }
}
