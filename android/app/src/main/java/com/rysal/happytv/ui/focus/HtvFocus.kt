package com.rysal.happytv.ui.focus

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/** How long a "give this node focus" request keeps trying before it gives up. */
const val FOCUS_WAIT_TIMEOUT_MS = 2_000L

/**
 * Attaches [requester] (when there is one) to the chain.
 *
 * **Ordering contract (CHANGELOG F-43, #53).** A [FocusRequester] only finds focus
 * targets that come *after* it in the modifier chain. Put this before the
 * `focusable()` / `clickable` / `htvClickable*` it is meant to reach; placed
 * behind them, `requestFocus()` is a silent no-op. `FocusRequesterOrderTest`
 * scans the sources and fails on a misordered chain.
 */
fun Modifier.htvFocusTarget(requester: FocusRequester?): Modifier =
    if (requester != null) this.focusRequester(requester) else this

/** `requestFocus()` that reports "no node attached yet" as `false` instead of throwing. */
fun FocusRequester.tryRequest(): Boolean = try {
    requestFocus()
    true
} catch (_: IllegalStateException) {
    false
}

/**
 * Asks for focus until it lands, bounded by **time** (frame-clock milliseconds),
 * not by a frame count: a slow composition (busy TV, Robolectric under load) takes
 * as many frames as it needs inside [timeoutMs].
 *
 * Each round waits one frame (so a node being composed or placed can attach), runs
 * [request], waits another frame (so the focus change is delivered) and then asks
 * [landed]. Returns true as soon as [landed] says so, false on timeout.
 */
suspend fun awaitFocus(
    timeoutMs: Long = FOCUS_WAIT_TIMEOUT_MS,
    landed: () -> Boolean,
    request: suspend () -> Unit,
): Boolean {
    var startNanos = -1L
    while (true) {
        val now = withFrameNanos { it }
        if (startNanos < 0) startNanos = now
        request()
        val after = withFrameNanos { it }
        if (landed()) return true
        if ((after - startNanos) / 1_000_000L >= timeoutMs) return false
    }
}

/**
 * Requests focus on this node as soon as it is attached and placed, retrying until
 * [landed] (default: the request was accepted by an attached node) or [timeoutMs].
 */
suspend fun FocusRequester.requestWhenAttached(
    timeoutMs: Long = FOCUS_WAIT_TIMEOUT_MS,
    landed: (() -> Boolean)? = null,
): Boolean {
    var accepted = false
    return awaitFocus(
        timeoutMs = timeoutMs,
        landed = { landed?.invoke() ?: accepted },
        request = { accepted = tryRequest() },
    )
}
