package com.rysal.happytv.ui.player

import com.rysal.happytv.data.BackWhileWatching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPolicyTest {

    @Test
    fun `movies resume where they stopped`() {
        assertTrue(PlayerPolicy.resumeOnStart(PlaybackMode.Movie))
    }

    @Test
    fun `replays resume where they stopped`() {
        assertTrue(PlayerPolicy.resumeOnStart(PlaybackMode.Replay))
    }

    @Test
    fun `live never resumes behind the live edge`() {
        assertFalse(PlayerPolicy.resumeOnStart(PlaybackMode.Live))
    }

    @Test
    fun `every mode has an explicit answer`() {
        // Adding a mode must be a conscious policy choice: the `when` in
        // PlayerPolicy is exhaustive, and this fails loudly if one throws.
        PlaybackMode.values().forEach { PlayerPolicy.resumeOnStart(it) }
    }

    // ---- Back table (#16) ----

    private fun back(mode: PlaybackMode, setting: BackWhileWatching, long: Boolean) =
        PlayerPolicy.backAction(mode, setting, long)

    @Test
    fun `live short back shows channels by default`() {
        assertEquals(BackAction.ToPreview, back(PlaybackMode.Live, BackWhileWatching.SHOW_CHANNELS, false))
    }

    @Test
    fun `live short back stops when the setting says stop playback`() {
        assertEquals(BackAction.StopPlayback, back(PlaybackMode.Live, BackWhileWatching.STOP_PLAYBACK, false))
    }

    @Test
    fun `live long back always stops`() {
        BackWhileWatching.entries.forEach {
            assertEquals(BackAction.StopPlayback, back(PlaybackMode.Live, it, true))
        }
    }

    @Test
    fun `movies and replay leave and never preview`() {
        for (mode in listOf(PlaybackMode.Movie, PlaybackMode.Replay)) {
            for (setting in BackWhileWatching.entries) {
                for (long in listOf(false, true)) assertEquals(BackAction.Leave, back(mode, setting, long))
            }
        }
    }

    @Test
    fun `ok on the previewing channel enlarges it and any other channel plays full`() {
        assertEquals(ChannelOkAction.EnterFull, PlayerPolicy.channelOk(HostMode.Preview, "7", "7"))
        assertEquals(ChannelOkAction.PlayFull, PlayerPolicy.channelOk(HostMode.Preview, "7", "8"))
        assertEquals(ChannelOkAction.PlayFull, PlayerPolicy.channelOk(HostMode.Idle, null, "7"))
        assertEquals(ChannelOkAction.PlayFull, PlayerPolicy.channelOk(HostMode.Idle, "7", "7"))
    }

    @Test
    fun `activity stop stops a playing host unless finishing`() {
        assertTrue(PlayerPolicy.stopHostOnActivityStop(HostMode.Full, finishing = false))
        assertTrue(PlayerPolicy.stopHostOnActivityStop(HostMode.Preview, finishing = false))
        assertFalse(PlayerPolicy.stopHostOnActivityStop(HostMode.Idle, finishing = false))
        assertFalse(PlayerPolicy.stopHostOnActivityStop(HostMode.Preview, finishing = true))
    }

    @Test
    fun `back setting defaults to show channels and ignores unknown values`() {
        assertEquals(BackWhileWatching.SHOW_CHANNELS, BackWhileWatching.fromStored(null))
        assertEquals(BackWhileWatching.SHOW_CHANNELS, BackWhileWatching.fromStored("bogus"))
        assertEquals(BackWhileWatching.STOP_PLAYBACK, BackWhileWatching.fromStored("stop_playback"))
    }

    // ---- Back key gate (#16) ----

    private class Presses {
        var short = 0
        var long = 0
        val gate = BackKeyGate({ short++ }, { long++ })
    }

    @Test
    fun `a short press acts once on key up`() {
        val p = Presses()
        p.gate.onDown(0)
        assertEquals(0, p.short)
        p.gate.onUp()
        assertEquals(1, p.short)
        assertEquals(0, p.long)
    }

    @Test
    fun `a hold fires once on the repeat and swallows the release`() {
        val p = Presses()
        p.gate.onDown(0)
        p.gate.onDown(1)
        p.gate.onDown(2)
        p.gate.onUp()
        assertEquals(1, p.long)
        assertEquals(0, p.short)
    }

    @Test
    fun `the long press flag also fires the hold`() {
        val p = Presses()
        p.gate.onDown(0)
        p.gate.onDown(0, longPressFlag = true)
        p.gate.onUp()
        assertEquals(1, p.long)
        assertEquals(0, p.short)
    }

    @Test
    fun `a key up with no key down seen does nothing`() {
        val p = Presses()
        p.gate.onUp()
        assertEquals(0, p.short + p.long)
    }

    @Test
    fun `a reset forgets a half seen press`() {
        val p = Presses()
        p.gate.onDown(0)
        p.gate.reset()
        p.gate.onUp()
        assertEquals(0, p.short + p.long)
    }
}

class LongBackSequenceTest {

    private val seq = LongBackSequence()

    @Test
    fun `a short press passes both events`() {
        assertEquals(LongBackDecision.Pass, seq.onDown(HostMode.Full, 0, false))
        assertEquals(LongBackDecision.Pass, seq.onUp())
    }

    @Test
    fun `a hold in full fires once, swallows repeats and the release`() {
        assertEquals(LongBackDecision.Pass, seq.onDown(HostMode.Full, 0, false))
        assertEquals(LongBackDecision.FireStop, seq.onDown(HostMode.Full, 1, true))
        assertEquals(LongBackDecision.Consume, seq.onDown(HostMode.Full, 2, true))
        assertEquals(LongBackDecision.Consume, seq.onUp())
        // The next press starts clean.
        assertEquals(LongBackDecision.Pass, seq.onDown(HostMode.Idle, 0, false))
        assertEquals(LongBackDecision.Pass, seq.onUp())
    }

    @Test
    fun `the platform long press in preview does nothing and a release is a short back`() {
        seq.onDown(HostMode.Preview, 0, false)
        assertEquals(LongBackDecision.Pass, seq.onDown(HostMode.Preview, 1, true))
        assertEquals(LongBackDecision.Pass, seq.onDown(HostMode.Preview, 30, true))
        assertEquals(LongBackDecision.Pass, seq.onUp())
    }

    @Test
    fun `a release before the timer is a short back and the stale timer does nothing`() {
        seq.onDown(HostMode.Preview, 0, false)
        assertEquals(LongBackDecision.Pass, seq.onUp())
        assertEquals(LongBackDecision.Pass, seq.onHoldTimeout(HostMode.Preview))
    }

    @Test
    fun `the 2 second timer in preview returns to full once and swallows the release`() {
        seq.onDown(HostMode.Preview, 0, false)
        assertEquals(LongBackDecision.Pass, seq.onDown(HostMode.Preview, 1, true))
        assertEquals(LongBackDecision.FireReturn, seq.onHoldTimeout(HostMode.Preview))
        // Fires once; the host is Full now, and repeats and the release are swallowed:
        // never a short Back and never a stop.
        assertEquals(LongBackDecision.Pass, seq.onHoldTimeout(HostMode.Preview))
        assertEquals(LongBackDecision.Consume, seq.onDown(HostMode.Full, 41, true))
        assertEquals(LongBackDecision.Consume, seq.onUp())
        assertEquals(LongBackDecision.Pass, seq.onDown(HostMode.Full, 0, false))
        assertEquals(LongBackDecision.Pass, seq.onUp())
    }

    @Test
    fun `the timer does nothing unless the host is still in preview`() {
        seq.onDown(HostMode.Preview, 0, false)
        assertEquals(LongBackDecision.Pass, seq.onHoldTimeout(HostMode.Idle))
        assertEquals(LongBackDecision.Pass, seq.onHoldTimeout(HostMode.Full))
        assertEquals(LongBackDecision.Pass, seq.onUp())
    }

    @Test
    fun `a hold while idle is left alone`() {
        seq.onDown(HostMode.Idle, 0, false)
        assertEquals(LongBackDecision.Pass, seq.onDown(HostMode.Idle, 1, true))
        assertEquals(LongBackDecision.Pass, seq.onHoldTimeout(HostMode.Idle))
        assertEquals(LongBackDecision.Pass, seq.onUp())
    }

    @Test
    fun `the long press flag alone fires in full`() {
        seq.onDown(HostMode.Full, 0, false)
        assertEquals(LongBackDecision.FireStop, seq.onDown(HostMode.Full, 0, true))
    }

    @Test
    fun `a stop caused by the hold does not turn the rest of it into a short press`() {
        seq.onDown(HostMode.Full, 0, false)
        assertEquals(LongBackDecision.FireStop, seq.onDown(HostMode.Full, 1, true))
        // Mode is Idle now; repeats and the release are still swallowed.
        assertEquals(LongBackDecision.Consume, seq.onDown(HostMode.Idle, 2, true))
        assertEquals(LongBackDecision.Consume, seq.onUp())
    }

    @Test
    fun `replay and live both reveal the controls on play pause`() {
        assertEquals(PlayerControl.PlayPause, defaultControlFor(PlaybackMode.Replay))
        assertEquals(PlayerControl.PlayPause, defaultControlFor(PlaybackMode.Live))
        assertEquals(PlayerControl.PlayPause, defaultControlFor(PlaybackMode.Movie))
    }
}
