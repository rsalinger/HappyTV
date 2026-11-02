package com.rysal.happytv.ui.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #102 QA fixes: where OK goes, and when the root must hold focus. */
class PlayerKeyRoutingTest {

    @Test
    fun okFromHiddenOnlyShowsControls() {
        assertFalse(okTogglesPlayback(controlsVisible = false, tracksPanelOpen = false, focusedControl = null, scrubberFocused = false))
        assertTrue(okOnlyShowsControls(controlsVisible = false, tracksPanelOpen = false))
    }

    @Test
    fun okVisibleDoesNotJustShowControls() {
        assertFalse(okOnlyShowsControls(controlsVisible = true, tracksPanelOpen = false))
        assertFalse(okOnlyShowsControls(controlsVisible = false, tracksPanelOpen = true))
    }

    @Test
    fun mediaKeysMapToActions() {
        assertEquals(MediaKeyAction.Toggle, Key.MediaPlayPause.mediaKeyAction())
        assertEquals(MediaKeyAction.Toggle, Key.Spacebar.mediaKeyAction())
        assertEquals(MediaKeyAction.Play, Key.MediaPlay.mediaKeyAction())
        assertEquals(MediaKeyAction.Pause, Key.MediaPause.mediaKeyAction())
        assertEquals(null, Key.Enter.mediaKeyAction())
        assertEquals(null, Key.DirectionCenter.mediaKeyAction())
    }

    @Test
    fun mediaKeysActHiddenOrVisibleButNotOverTracksPanel() {
        assertTrue(mediaKeysAct(tracksPanelOpen = false))
        assertFalse(mediaKeysAct(tracksPanelOpen = true))
    }

    @Test
    fun okOnAnyControlButtonGoesToThatButton() {
        for (c in PlayerControl.values()) {
            assertFalse("$c", okTogglesPlayback(true, false, c, false))
        }
    }

    @Test
    fun okOnTheScrubberOrWithNothingFocusedTogglesPlayback() {
        assertTrue(okTogglesPlayback(true, false, null, scrubberFocused = true))
        assertTrue(okTogglesPlayback(true, false, null, scrubberFocused = false))
    }

    @Test
    fun okInTheTracksPanelIsTheRowsOwn() {
        assertFalse(okTogglesPlayback(true, true, null, false))
        assertFalse(okTogglesPlayback(false, true, null, false))
    }

    @Test
    fun rootFocusIsNeededOnlyWhenNothingElseOwnsIt() {
        assertTrue(needsRootFocus(ready = true, controlsVisible = false, tracksPanelOpen = false, leaveConfirmOpen = false, endCardOpen = false))
        assertFalse(needsRootFocus(false, false, false, false, false))
        assertFalse(needsRootFocus(true, true, false, false, false))
        assertFalse(needsRootFocus(true, false, true, false, false))
        assertFalse("leave dialog owns focus", needsRootFocus(true, false, false, true, false))
        assertFalse("Next-episode card owns focus", needsRootFocus(true, false, false, false, true))
    }

    @Test
    fun playPauseShowsTheActionNotTheState() {
        assertTrue(playPauseIcon(isPlaying = true) === PlayerIcons.Pause)
        assertTrue(playPauseIcon(isPlaying = false) === PlayerIcons.Play)
        assertEquals("Pause", playPauseLabel(true))
        assertEquals("Play", playPauseLabel(false))
    }

    @Test
    fun theSeekIdleWindowIsLongerThanTheRepeatDelay() {
        assertEquals(true, SeekSession.IDLE_COMMIT_MILLIS > 500L)
    }
}
