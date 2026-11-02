package com.rysal.happytv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

/** #138: only the Back out that follows a hold-return jumps the list to the playing channel. */
class LivePlaybackJumpTest {

    private fun playback() = LivePlayback(playingStreamIdProvider = { "7" }, onPlayChannel = {})

    @Test
    fun `a plain back out does not jump`() {
        val p = playback()
        p.requestRestore("7")
        assertEquals(false, p.restoreJump)
    }

    @Test
    fun `back out after a hold return jumps once`() {
        val p = playback()
        p.jumpToPlayingOnBack = true
        p.requestRestore("7")
        assertEquals(true, p.restoreJump)
        assertEquals("7", p.restoreStreamId)
        p.requestRestore("7")
        assertEquals(false, p.restoreJump)
    }
}
