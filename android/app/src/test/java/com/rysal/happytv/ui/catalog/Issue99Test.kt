package com.rysal.happytv.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #99: which episode row gets focus on coming back from the player, and the Continue watching menu width. */
class Issue99Test {
    @Test
    fun `after Next episode focus goes to the episode Continue watching points at`() {
        assertEquals("e22", episodeToFocus("e20", resumeAtLaunch = "e20", latestResumeEpisodeId = "e22"))
    }

    @Test
    fun `before the saved row is read the started episode is used`() {
        assertEquals("e20", episodeToFocus("e20", "e20", null))
    }

    @Test
    fun `playing with the Play button leaves focus on the primary action`() {
        assertNull(episodeToFocus(null, "e20", "e22"))
    }

    @Test
    fun `an episode started from another resume point keeps focus when nothing was saved`() {
        assertEquals("e5", episodeToFocus("e5", resumeAtLaunch = "e1", latestResumeEpisodeId = "e1"))
    }

    private val episode = EpisodeItem("e1", 1, 1, "Pilot", null, null, null)

    @Test
    fun `OK on a watched episode row starts at 0 without Start over`() {
        val point = episodePlayPoint(episode, EpisodeMark(watched = true, positionMillis = 2_600_000L))
        assertEquals(0L, point.positionMillis)
        assertEquals(false, point.startOver)
        assertEquals("e1", point.episodeId)
    }

    @Test
    fun `OK on an episode part way resumes it and never starts over`() {
        val point = episodePlayPoint(episode, EpisodeMark(watched = false, positionMillis = 600_000L))
        assertEquals(600_000L, point.positionMillis)
        assertEquals(false, point.startOver)
    }

    @Test
    fun `only the explicit Start over action sets startOver`() {
        assertTrue(ResumePoint(600_000L, "e1", "S1 E1").asStartOver().startOver)
        assertEquals(false, ResumePoint(0L, "e1", "S1 E1").startOver)
    }

    @Test
    fun `Continue watching menu is wider than the default and stays inside the safe edge`() {
        assertTrue(CONTINUE_MENU_WIDTH_PX > 600)
        assertTrue(1824 - CONTINUE_MENU_WIDTH_PX >= 96)
    }
}
