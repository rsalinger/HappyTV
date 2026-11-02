package com.rysal.happytv.perf

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Timing lines QA reads from logcat (`adb logcat -s HtvPerf:I`), format from
 * `docs/WORKFLOW.md`: `HtvPerf: <event> <millis>ms [detail]`.
 *
 * Events that span a screen boundary (tile OK on the dashboard -> rows drawn in
 * Live TV) are bracketed by [start] and [end]: the start mark lives in a
 * process-wide map, so it survives the activity change. [end] logs once and
 * clears the mark, so a recomposition after the event cannot log it twice.
 *
 * No URLs, credentials or provider names ever go in `detail`; counts only.
 */
object HtvPerf {
    const val TAG = "HtvPerf"

    // Event names (kept here so the screens and tests agree on spelling).
    const val LIVETV_OPEN = "livetv.open"
    const val LIVETV_CATEGORY_ROWS = "livetv.category.rows"
    const val LIVETV_CATEGORY_EPG = "livetv.category.epg"
    const val GUIDE_OPEN = "guide.open"
    const val GUIDE_DETAILS_OPEN = "guide.details.open"
    const val GUIDE_DETAILS_CLOSE = "guide.details.close"
    const val PLAYER_BACK_PREVIEW = "player.back.preview"
    const val PLAYER_BACK_GAP = "player.back.gap"
    const val PLAYER_ZAP = "player.zap"
    /** Next episode requested (button or countdown end) to its first frame rendered (#84). */
    const val PLAYER_NEXT_EPISODE = "player.next.episode"
    /** First scrubber press of a burst to its single seekTo; detail `presses=N` (#102). */
    const val PLAYER_SEEK = "player.seek"
    const val SEARCH_RESULTS = "search.results"
    const val CATALOG_OPEN = "catalog.open"
    const val CATALOG_CATEGORY = "catalog.category"
    const val SHOW_EPISODES = "show.episodes"

    private val starts = ConcurrentHashMap<String, Long>()

    /** Swappable for tests; nanoTime because `SystemClock` is stubbed in unit tests. */
    @Volatile
    internal var nowMs: () -> Long = { System.nanoTime() / 1_000_000L }

    /** Swappable for tests; the default writes to logcat. */
    @Volatile
    internal var sink: (String) -> Unit = { line -> Log.i(TAG, line) }

    /** Begin (or restart) timing [event]. */
    fun start(event: String) {
        starts[event] = nowMs()
    }

    /**
     * Begin timing [event] unless a mark younger than [freshMs] already exists.
     * Used by activity `onCreate` so an `am start` launch is measured too, while
     * a tile click's earlier (more accurate) mark is kept.
     */
    fun startIfNotRecent(event: String, freshMs: Long = 3_000L) {
        val now = nowMs()
        val existing = starts[event]
        if (existing == null || now - existing > freshMs) starts[event] = now
    }

    /** True while [event] has a start mark that [end] has not consumed. */
    fun isRunning(event: String): Boolean = starts.containsKey(event)

    /** Drop a mark without logging (the event was abandoned). */
    fun cancel(event: String) {
        starts.remove(event)
    }

    /**
     * Log [event]'s elapsed time and clear its mark. A no-op without a mark, so
     * callers can `end` from a recomposition-safe place.
     *
     * @return the elapsed millis, or null when no mark was set.
     */
    fun end(event: String, detail: String = ""): Long? {
        val begun = starts.remove(event) ?: return null
        val elapsed = nowMs() - begun
        log(event, elapsed, detail)
        return elapsed
    }

    /** Log an already-measured duration. */
    fun log(event: String, elapsedMs: Long, detail: String = "") {
        sink(if (detail.isEmpty()) "$event ${elapsedMs}ms" else "$event ${elapsedMs}ms $detail")
    }

    internal fun resetForTest() {
        starts.clear()
        nowMs = { System.nanoTime() / 1_000_000L }
        sink = { line -> Log.i(TAG, line) }
    }
}
