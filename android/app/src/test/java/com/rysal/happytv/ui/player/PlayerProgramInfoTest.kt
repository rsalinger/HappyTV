package com.rysal.happytv.ui.player

import androidx.compose.ui.graphics.Color
import com.rysal.happytv.ui.theme.HappyTvDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** #131: Start over enablement, the Program info card mapping, control order and scrim contrast. */
class PlayerProgramInfoTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L

    private fun program(startAgoMin: Long = 12, lengthMin: Long = 60, description: String? = "A long story.") =
        PlayerProgram("Headline report", now - startAgoMin * min, now - startAgoMin * min + lengthMin * min, description)

    private fun content(
        mode: PlaybackMode = PlaybackMode.Live,
        archive: Boolean = true,
        nowNext: PlayerNowNext? = PlayerNowNext(program(), PlayerProgram("Market wrap", now + 48 * min, now + 78 * min)),
        tracks: PlayerTracks? = null,
    ) = PlayerContent(
        url = "u",
        mode = mode,
        title = "Headline report",
        topLine = "101 · Northbay News",
        channelName = "Northbay News",
        monogram = "NN",
        monogramPalette = null,
        nowNext = nowNext,
        tvArchive = archive,
        tracks = tracks,
        progress = PlayerProgress(null, null, 0f, 0f, null, ScrubberDot.PulsingRed),
        badge = null,
    )

    // ------------------------------------------------------------- start over

    @Test
    fun `start over is enabled on a live archive channel with a known program start`() {
        assertTrue(PlayerStartOver.isEnabled(content()))
        assertNull(PlayerStartOver.unavailableReason(content()))
    }

    @Test
    fun `start over is greyed out without catch-up`() {
        val c = content(archive = false)
        assertFalse(PlayerStartOver.isEnabled(c))
        assertEquals("No replay on this channel", PlayerStartOver.unavailableReason(c))
    }

    @Test
    fun `start over is greyed out without guide data`() {
        for (nn in listOf(null, PlayerNowNext(null, PlayerProgram("Later", now + min, now + 2 * min)))) {
            val c = content(nowNext = nn)
            assertFalse(PlayerStartOver.isEnabled(c))
            assertEquals("No guide info for this show", PlayerStartOver.unavailableReason(c))
        }
    }

    @Test
    fun `start over is greyed out for a program with no usable start`() {
        val zero = content(nowNext = PlayerNowNext(PlayerProgram("X", 0L, now + min), null))
        val inverted = content(nowNext = PlayerNowNext(PlayerProgram("X", now, now - min), null))
        assertFalse(PlayerStartOver.isEnabled(zero))
        assertFalse(PlayerStartOver.isEnabled(inverted))
    }

    @Test
    fun `start over only exists in live mode`() {
        assertFalse(PlayerStartOver.isEnabled(content(mode = PlaybackMode.Replay)))
        assertFalse(PlayerStartOver.isEnabled(content(mode = PlaybackMode.Movie)))
    }

    @Test
    fun `the timeshift window rounds a program up to whole minutes`() {
        assertEquals(60, PlayerStartOver.durationMinutes(PlayerProgram("x", 0L, 60 * min)))
        assertEquals(31, PlayerStartOver.durationMinutes(PlayerProgram("x", 0L, 30 * min + 1)))
        assertEquals(1, PlayerStartOver.durationMinutes(PlayerProgram("x", 0L, 10L)))
    }

    // ------------------------------------------------------------- info card

    @Test
    fun `the info card carries channel, title, time range, time left, description and next`() {
        val card = PlayerInfoCard.from(content(), now)!!
        assertEquals("101 · Northbay News", card.channelLine)
        assertEquals("Headline report", card.title)
        assertEquals(PlayerFormatting.rangeLabel(now - 12 * min, now + 48 * min), card.timeRange)
        assertEquals("48 min left", card.timeLeft)
        assertEquals("A long story.", card.description)
        assertEquals("Market wrap", card.nextTitle)
        assertEquals(PlayerFormatting.rangeLabel(now + 48 * min, now + 78 * min), card.nextRange)
    }

    @Test
    fun `a blank description is no description`() {
        val c = content(nowNext = PlayerNowNext(program(description = "   "), null))
        assertNull(PlayerInfoCard.from(c, now)!!.description)
        assertNull(PlayerInfoCard.from(c, now)!!.nextTitle)
    }

    @Test
    fun `no guide data means no card, so the button is greyed out`() {
        assertNull(PlayerInfoCard.from(content(nowNext = null), now))
        assertNull(PlayerInfoCard.from(content(nowNext = PlayerNowNext(null, null)), now))
    }

    @Test
    fun `a guide gap still shows what is next`() {
        val c = content(nowNext = PlayerNowNext(null, PlayerProgram("Market wrap", now + 5 * min, now + 35 * min)))
        val card = PlayerInfoCard.from(c, now)!!
        assertEquals("Headline report", card.title) // falls back to the player's title
        assertNull(card.timeRange)
        assertNull(card.timeLeft)
        assertEquals("Market wrap", card.nextTitle)
    }

    @Test
    fun `time left reads in minutes then hours and ends with the program`() {
        assertEquals("1 min left", PlayerInfoCard.timeLeftLabel(now + 1, now))
        assertEquals("59 min left", PlayerInfoCard.timeLeftLabel(now + 59 * min, now))
        assertEquals("1 h left", PlayerInfoCard.timeLeftLabel(now + 60 * min, now))
        assertEquals("1 h 5 min left", PlayerInfoCard.timeLeftLabel(now + 65 * min, now))
        assertNull(PlayerInfoCard.timeLeftLabel(now, now))
        assertNull(PlayerInfoCard.timeLeftLabel(now - min, now))
    }

    // --------------------------------------------------------- control order

    @Test
    fun `live order is play, start over, program info, favorite`() {
        assertEquals(
            listOf(PlayerControl.PlayPause, PlayerControl.StartOver, PlayerControl.ProgramInfo, PlayerControl.Favorite),
            playerControlOrder(content()),
        )
    }

    @Test
    fun `replay swaps start over for go live in the same slot`() {
        assertEquals(
            listOf(PlayerControl.PlayPause, PlayerControl.GoLive, PlayerControl.ProgramInfo, PlayerControl.Favorite),
            playerControlOrder(content(mode = PlaybackMode.Replay)),
        )
    }

    @Test
    fun `greyed-out buttons stay in the order, so the reason is reachable`() {
        val order = playerControlOrder(content(archive = false, nowNext = null))
        assertTrue(PlayerControl.StartOver in order)
        assertTrue(PlayerControl.ProgramInfo in order)
    }

    @Test
    fun `movies and episodes keep play, tracks, favorite`() {
        val tracks = PlayerTracks(
            audio = listOf(PlayerTrackOption("English", 0, 0), PlayerTrackOption("Spanish", 0, 1)),
            subtitles = listOf(PlayerTrackOption("Off", 0, null)),
        )
        assertEquals(
            listOf(PlayerControl.PlayPause, PlayerControl.Tracks, PlayerControl.Favorite),
            playerControlOrder(content(mode = PlaybackMode.Movie, nowNext = null, tracks = tracks)),
        )
        assertEquals(
            listOf(PlayerControl.PlayPause, PlayerControl.Favorite),
            playerControlOrder(content(mode = PlaybackMode.Movie, nowNext = null)),
        )
    }

    // ----------------------------------------------------------------- scrim

    /** WCAG relative luminance of an opaque sRGB colour. */
    private fun luminance(r: Float, g: Float, b: Float): Double {
        fun lin(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    private fun contrast(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    /** [text] over white video darkened by a black scrim of [scrimAlpha] (the worst case: brightest picture). */
    private fun contrastOverWhiteVideo(text: Color, scrimAlpha: Float): Double {
        val bg = 1f - scrimAlpha
        return contrast(luminance(text.red, text.green, text.blue), luminance(bg, bg, bg))
    }

    @Test
    fun `bottom scrim backs controls and scrubber only`() {
        // 1080 artboard: control row at 800, soft fade above it, nothing higher than 728.
        assertEquals(1080 - 728, PlayerScrim.BOTTOM_HEIGHT_PX)
        assertTrue(PlayerScrim.BOTTOM_FADE_PX in 60..80)
        assertEquals(0f, PlayerScrim.bottomAlphaAt(0f), 0f)
        assertTrue(PlayerScrim.BOTTOM_HEIGHT_PX < 560 / 1.5)
    }

    @Test
    fun `bottom text keeps 4_5 to 1 over white video where it sits`() {
        // Highest text over the scrim: the focused control's label (top 904) and, below it, the
        // progress times (966 to 1010); both sit on the scrim's solid part, at least 176 px in.
        val artboardTop = 1080 - PlayerScrim.BOTTOM_HEIGHT_PX
        val labelTop = (904 - artboardTop).toFloat()
        val alpha = PlayerScrim.bottomAlphaAt(labelTop)
        assertTrue("alpha at label top $alpha", alpha >= 0.8f)
        for ((name, color) in listOf(
            "primary" to HappyTvDarkColors.TextPrimary,
            "secondary" to HappyTvDarkColors.TextSecondary,
        )) {
            val ratio = contrastOverWhiteVideo(color, alpha)
            assertTrue("$name text over white video at the label row is $ratio", ratio >= 4.5)
        }
        // The scrim's own darkest band is darker still.
        assertTrue(
            contrastOverWhiteVideo(HappyTvDarkColors.TextSecondary, PlayerScrim.bottomAlphaAt(PlayerScrim.BOTTOM_HEIGHT_PX.toFloat())) >=
                contrastOverWhiteVideo(HappyTvDarkColors.TextSecondary, alpha),
        )
    }

    @Test
    fun `top text keeps 4_5 to 1 over white video down to the end of the title block`() {
        // Top block: top 54, monogram 112 tall; title and top line end by 166.
        val alpha = PlayerScrim.topAlphaAt(166f)
        assertNotNull(alpha)
        for (color in listOf(HappyTvDarkColors.TextPrimary, HappyTvDarkColors.TextSecondary)) {
            val ratio = contrastOverWhiteVideo(color, alpha)
            assertTrue("top text over white video at 166 px is $ratio (alpha $alpha)", ratio >= 4.5)
        }
        assertTrue(PlayerScrim.TOP_HEIGHT_PX < 320)
    }
}
