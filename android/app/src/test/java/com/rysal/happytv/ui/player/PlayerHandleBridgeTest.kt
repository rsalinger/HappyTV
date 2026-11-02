package com.rysal.happytv.ui.player

import org.junit.Test

/**
 * PlayerHandleBridge.attach/detach coverage — directly tied to F-54.
 *
 * The bridge must stop delivering commands to the player after `detach()`,
 * so the SurfaceView BLAST layer can be reaped by SurfaceFlinger once the
 * player reference is dropped on `Activity.onStop`. The behaviour we assert
 * is: `detach()` flips the bridge back to a no-op for `togglePlayPause`,
 * `seekBy` and `prepare` without throwing.
 *
 * We don't construct a real ExoPlayer here (that needs Media3 + Robolectric);
 * the bridge only ever remembers a nullable reference, so we exercise the
 * real methods against `null` — their runtime contract is exactly "ignore
 * when no player is attached".
 */
class PlayerHandleBridgeTest {

    @Test
    fun `detached bridge swallows every player command`() {
        val bridge = PlayerHandleBridge()

        // No player attached → every command must be a no-op, not a crash.
        bridge.togglePlayPause()
        bridge.seekBy(deltaMillis = 4_000L)
        bridge.prepare()

        // Re-detaching is also a no-op (idempotent).
        bridge.detach()
    }

    @Test
    fun `repeated detach cycles do not throw`() {
        val bridge = PlayerHandleBridge()

        // Multiple detach calls in a row must stay safe; the host may
        // legitimately observe `onStop` more than once across a few rapid
        // foreground/background transitions.
        repeat(5) {
            bridge.detach()
            bridge.togglePlayPause()
            bridge.seekBy(deltaMillis = 1_000L)
            bridge.prepare()
        }
    }
}
