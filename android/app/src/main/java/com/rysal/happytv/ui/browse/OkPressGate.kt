package com.rysal.happytv.ui.browse

import kotlinx.coroutines.Job

/**
 * F-43's "long-press OK" decision, as a plain state object the modifier feeds.
 *
 * [htvClickable] wires this to the remote's key events and runs the 500 ms
 * hold timer ([LONG_PRESS_OK_MILLIS]); every decision the handler makes lives
 * here instead, so the whole contract — short press still plays, a hold opens
 * the menu exactly once and swallows the release that would otherwise play —
 * is unit-testable without a Chromecast in the room.
 *
 * Two facts about foundation's `clickable` shape this class:
 *
 *  - It fires its `onClick` on the key **up** of DPAD_CENTER / ENTER /
 *    NUMPAD_ENTER *unconditionally* (see `isClick` in foundation's
 *    `Clickable.android.kt` — it does not check that it saw the down). So once
 *    a hold has opened the menu, the release the user makes while letting go
 *    must be eaten at [onEnterUp] or the channel plays behind the menu.
 *  - The mirror case: an enter-key up with no down seen on this same node
 *    means focus arrived mid-hold — the channel menu auto-focusing its first
 *    item does exactly that. Clicking then would select a menu item the
 *    instant the menu opened, so that release is eaten too.
 *
 * The down itself always passes through (returns `false`): `clickable`'s own
 * press interaction and the short-press path stay foundation's, unchanged.
 *
 * @param onLongPress fired when the hold crosses the threshold — the caller
 *   (a `rememberUpdatedState`-backed lambda) opens the channel menu.
 */
internal class OkPressGate(private val onLongPress: () -> Unit) {

    /** This node saw an enter-key DOWN with repeat count 0 since the last up. */
    private var pressed = false

    /** The hold already crossed the threshold for this press. */
    private var longPressFired = false

    /** The in-flight hold timer; cancelled on release and on leaving composition. */
    var holdJob: Job? = null

    /**
     * Enter-key DOWN. Returns true when the event must be swallowed.
     *
     * A hold is recognised two ways: the 500 ms timer, and the system's own
     * signal on the key itself, a repeat ([repeatCount] > 0) or
     * `FLAG_LONG_PRESS` ([longPressFlag]). The second is what
     * `adb shell input keyevent --longpress` and remotes that send repeats
     * produce, with no 500 ms gap the timer could see. Either way the press
     * counts once and the release is eaten.
     */
    fun onEnterDown(repeatCount: Int, longPressFlag: Boolean = false): Boolean {
        if (repeatCount == 0) {
            pressed = true
            longPressFired = false
        }
        // `clickable` ignores repeats itself (it tracks one press per key).
        if ((repeatCount > 0 || longPressFlag) && pressed) {
            cancelHold()
            onHoldElapsed()
        }
        return false
    }

    /** Enter-key UP. Returns true when the event must be swallowed. */
    fun onEnterUp(): Boolean = when {
        // Hold opened the menu: eat the release — no play behind the menu.
        pressed && longPressFired -> {
            pressed = false
            longPressFired = false
            true
        }
        // No press seen here: focus moved mid-hold (the menu took it).
        !pressed -> true
        // Ordinary short press: let `clickable` see the up and play the channel.
        else -> {
            pressed = false
            false
        }
    }

    /** The hold crossed [LONG_PRESS_OK_MILLIS]; opens the menu exactly once. */
    fun onHoldElapsed() {
        if (pressed && !longPressFired) {
            longPressFired = true
            onLongPress()
        }
    }

    /** Abandon any in-flight hold timer (release, or the row left composition). */
    fun cancelHold() {
        holdJob?.cancel()
        holdJob = null
    }
}

/** Chromecast remote: holding OK ≈ half a second, the gesture every TV app uses. */
internal const val LONG_PRESS_OK_MILLIS = 500L

/**
 * For a node that must only react to a press it saw from the start (#145): a menu item
 * that takes focus while OK is still held. Foundation's `clickable` fires on any
 * enter-key up, so the release of the hold that opened the menu would pick the first
 * item. This swallows an up (and the repeated downs) that has no fresh down on this node.
 * Keeps no state beyond [pressed], so a normal press+release passes untouched.
 */
internal class OrphanEnterGuard {
    private var pressed = false

    /** Enter-key DOWN. Returns true when the event must be swallowed. */
    fun onDown(repeatCount: Int): Boolean {
        if (repeatCount == 0) {
            pressed = true
            return false
        }
        // A repeat with no fresh down here is the tail of a hold that began elsewhere.
        return !pressed
    }

    /** Enter-key UP. Returns true when the event must be swallowed. */
    fun onUp(): Boolean {
        val orphan = !pressed
        pressed = false
        return orphan
    }
}
