package com.rysal.happytv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OkPressGate] — F-43's hold-OK contract, event by event.
 *
 * The gate decides whether a key event is swallowed; `htvClickable` wires it
 * to the real remote and the 500 ms timer. The two decisions that matter:
 *
 *  - the **up after a hold** is eaten, because foundation's `clickable` fires
 *    its `onClick` on enter-key up unconditionally — the channel must not play
 *    behind the open menu;
 *  - the **short press passes through untouched**, because "must not steal
 *    the short-press OK behavior" is the other half of the acceptance.
 */
class OkPressGateTest {

    private var longPresses = 0
    private val gate get() = OkPressGate { longPresses++ }

    @Test
    fun `a key repeat is the long press, once, and the release is eaten`() {
        // `adb shell input keyevent --longpress`: down, then a repeat, then up,
        // with no 500 ms gap for the timer to see.
        val g = gate
        assertEquals(false, g.onEnterDown(0))
        assertEquals(false, g.onEnterDown(1))
        assertEquals(1, longPresses)
        g.onEnterDown(2)
        g.onHoldElapsed()
        assertEquals("one long press per hold", 1, longPresses)
        assertEquals("the release must be eaten, so it never also clicks", true, g.onEnterUp())
    }

    @Test
    fun `the long-press flag on a down is the long press`() {
        val g = gate
        g.onEnterDown(0)
        g.onEnterDown(0, longPressFlag = true)
        assertEquals(1, longPresses)
        assertEquals(true, g.onEnterUp())
    }

    @Test
    fun `a repeat with no down seen here is not a long press`() {
        // Focus arrived mid-hold (a menu took it): nothing fires, the up is eaten.
        val g = gate
        g.onEnterDown(4)
        assertEquals(0, longPresses)
        assertEquals(true, g.onEnterUp())
    }

    @Test
    fun `short press passes both events through so the channel still plays`() {
        val g = gate
        assertEquals("down must reach clickable", false, g.onEnterDown(0))
        assertEquals("up must reach clickable — that click is play", false, g.onEnterUp())
        assertEquals(0, longPresses)
    }

    @Test
    fun `a hold past the threshold opens the menu once and swallows the release`() {
        val g = gate
        assertEquals(false, g.onEnterDown(0))
        g.onHoldElapsed()
        assertEquals(1, longPresses)
        // Native repeats or a second timer tick must not open a second menu.
        g.onHoldElapsed()
        assertEquals(1, longPresses)
        assertEquals("the release must be eaten — no play behind the menu", true, g.onEnterUp())
    }

    @Test
    fun `letting go before the threshold never opens the menu`() {
        val g = gate
        g.onEnterDown(0)
        assertEquals(false, g.onEnterUp())
        // A late timer tick after release (racing the cancel) is a no-op.
        g.onHoldElapsed()
        assertEquals(0, longPresses)
    }

    @Test
    fun `repeats while holding neither reset nor re-fire the long press`() {
        val g = gate
        g.onEnterDown(0)
        g.onEnterDown(3)
        g.onHoldElapsed()
        assertEquals(1, longPresses)
        g.onEnterDown(4)
        assertEquals(1, longPresses)
        assertEquals(true, g.onEnterUp())
    }

    @Test
    fun `a release with no press on this node is swallowed — focus arrived mid-hold`() {
        // The menu auto-focuses its first item while the user still holds OK;
        // the release lands there, and clicking would select the item instantly.
        val g = gate
        assertEquals(true, g.onEnterUp())
    }

    @Test
    fun `the next short press plays again after a long press`() {
        val g = gate
        g.onEnterDown(0)
        g.onHoldElapsed()
        assertEquals(true, g.onEnterUp())

        assertEquals(false, g.onEnterDown(0))
        assertEquals(false, g.onEnterUp())
        assertEquals(1, longPresses)
    }

    @Test
    fun `cancelling the hold job before the threshold kills the timer`() {
        val g = gate
        g.onEnterDown(0)
        g.cancelHold() // release, or the row left composition: the timer dies
        assertNull("no job left that could fire the menu", g.holdJob)
    }
}

class OrphanEnterGuardTest {
    @Test
    fun `an up with no down here is swallowed once, then a fresh press passes`() {
        val g = OrphanEnterGuard()
        org.junit.Assert.assertTrue(g.onDown(repeatCount = 3))
        org.junit.Assert.assertTrue(g.onUp())
        org.junit.Assert.assertFalse(g.onDown(repeatCount = 0))
        org.junit.Assert.assertFalse(g.onUp())
    }
}
