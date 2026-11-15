package com.rysal.happytv.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/** #263: the playing-to-keepScreenOn mapping. */
class KeepScreenOnTest {
    private fun on(
        hasPlayer: Boolean = true,
        visible: Boolean = true,
        playWhenReady: Boolean = true,
        state: Int,
    ) = shouldKeepScreenOn(hasPlayer, visible, playWhenReady, state)

    @Test fun readyAndPlayingKeepsOn() = assertEquals(true, on(state = Player.STATE_READY))
    @Test fun bufferingWhilePlayingKeepsOn() = assertEquals(true, on(state = Player.STATE_BUFFERING))
    @Test fun pausedReleases() = assertEquals(false, on(playWhenReady = false, state = Player.STATE_READY))
    @Test fun pausedWhileBufferingReleases() =
        assertEquals(false, on(playWhenReady = false, state = Player.STATE_BUFFERING))
    @Test fun endedReleases() = assertEquals(false, on(state = Player.STATE_ENDED))
    @Test fun idleOrErrorReleases() = assertEquals(false, on(state = Player.STATE_IDLE))
    @Test fun noPlayerReleases() = assertEquals(false, on(hasPlayer = false, state = Player.STATE_READY))
    @Test fun hiddenSurfaceReleases() = assertEquals(false, on(visible = false, state = Player.STATE_READY))
}
