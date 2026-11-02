package com.rysal.happytv.ui.player

/**
 * Scrubber seeking (#102): turns a burst of Left/Right presses (taps or a hold)
 * into one target position and one `seekTo`. Pure, with the clock passed in,
 * so the acceleration and the coalescing are unit-tested without a player.
 *
 * Acceleration follows how long the key has been held (time since the first
 * key-down of the current run; a new run starts on a fresh press or a change of
 * direction): [FINE_STEP_MILLIS] for the first [FINE_UNTIL_MILLIS], then
 * [MEDIUM_STEP_MILLIS] until [MEDIUM_UNTIL_MILLIS], then [COARSE_STEP_MILLIS].
 * Android repeats a held key about every 50 ms after a 500 ms delay, so a hold
 * crosses a 2-hour movie in under ten seconds.
 */
internal class SeekSession(private val idleMillis: Long = IDLE_COMMIT_MILLIS) {

    /** The target a burst is heading for; null when nothing is pending. */
    var pendingTargetMillis: Long? = null
        private set

    /** True once the current run produced a key repeat (the key is held, not tapped). */
    var held: Boolean = false
        private set

    private var presses = 0
    private var firstPressAt = 0L
    private var runStartedAt = 0L
    private var direction = 0
    private var lastPressAt = 0L
    private var lastCommitTarget: Long? = null
    private var lastCommitAt = 0L

    val hasPending: Boolean get() = pendingTargetMillis != null

    /**
     * One Left (-1) or Right (+1) key-down. [repeatCount] is the key event's
     * repeat count (0 for a fresh press). Returns the new pending target.
     */
    fun press(
        direction: Int,
        repeatCount: Int,
        nowMillis: Long,
        currentPositionMillis: Long,
        durationMillis: Long,
    ): Long {
        require(direction == -1 || direction == 1) { "direction must be -1 or 1" }
        if (repeatCount == 0 || direction != this.direction) {
            runStartedAt = nowMillis
            held = false
        }
        if (repeatCount > 0) held = true
        this.direction = direction

        val base = pendingTargetMillis ?: run {
            firstPressAt = nowMillis
            presses = 0
            // The player's reported position lags a committed seek by up to a
            // tick, so a press right after a commit continues from its target.
            lastCommitTarget?.takeIf { nowMillis - lastCommitAt < SETTLE_MILLIS } ?: currentPositionMillis
        }
        val step = stepMillis(nowMillis - runStartedAt)
        var target = base + direction * step
        target = if (durationMillis > 0L) target.coerceIn(0L, durationMillis) else target.coerceAtLeast(0L)
        pendingTargetMillis = target
        presses++
        lastPressAt = nowMillis
        return target
    }

    /** True when the burst has been quiet for the idle window and should be applied. */
    fun isIdle(nowMillis: Long): Boolean = hasPending && nowMillis - lastPressAt >= idleMillis

    /** Ends the burst; null when nothing was pending (so a second call is a no-op). */
    fun commit(nowMillis: Long): SeekCommit? {
        val target = pendingTargetMillis ?: return null
        pendingTargetMillis = null
        held = false
        lastCommitTarget = target
        lastCommitAt = nowMillis
        return SeekCommit(target, presses, nowMillis - firstPressAt)
    }

    /** True while a just-committed target should still be drawn (the player has not reported it yet). */
    fun isSettling(nowMillis: Long): Boolean =
        !hasPending && lastCommitTarget != null && nowMillis - lastCommitAt < SETTLE_MILLIS

    companion object {
        const val FINE_STEP_MILLIS = 10_000L
        const val MEDIUM_STEP_MILLIS = 30_000L
        const val COARSE_STEP_MILLIS = 60_000L
        const val FINE_UNTIL_MILLIS = 2_000L
        const val MEDIUM_UNTIL_MILLIS = 4_000L
        /**
         * Quiet time before a burst of taps is applied. Must exceed Android's 500 ms
         * key-repeat delay, or the first press of a hold commits before its first
         * repeat arrives (QA on #103).
         */
        const val IDLE_COMMIT_MILLIS = 700L

        /** A held key commits on key-up; this only covers a lost key-up. */
        const val HELD_SAFETY_MILLIS = 2_000L
        const val SETTLE_MILLIS = 1_500L

        /** Step size for a key held for [heldMillis]. */
        fun stepMillis(heldMillis: Long): Long = when {
            heldMillis < FINE_UNTIL_MILLIS -> FINE_STEP_MILLIS
            heldMillis < MEDIUM_UNTIL_MILLIS -> MEDIUM_STEP_MILLIS
            else -> COARSE_STEP_MILLIS
        }
    }
}

/** One coalesced seek: where to go, how many presses it folds, and how long the burst took. */
internal data class SeekCommit(val targetMillis: Long, val presses: Int, val burstMillis: Long)
