package com.rysal.happytv.ui.catalog

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.search.channelNumberText
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Long text on the details page, the poster caption and the search card (#93). */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DetailsOverflowTest {

    @get:Rule
    val compose = createComposeRule()

    private val longTitle = "A Hero's Journey: The Making of Percy Jackson and the Olympians"
    // Explicit line breaks: Robolectric's text metrics do not wrap a long line the way a device does.
    private val longPlot = (1..10).joinToString("\n") { "Line $it of the plot." }

    @Test
    fun theTitleUsesTheWholeWidthAndShowsInFull() {
        var widthPx = 0f
        var rightLimitPx = 0f
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                widthPx = with(LocalDensity.current) { d.px(720).toPx() }
                rightLimitPx = with(LocalDensity.current) { d.px(1264).toPx() }
                Column(Modifier.size(width = d.px(720), height = d.px(600))) {
                    DetailsTitle(
                        text = longTitle,
                        base = browseType().display,
                        modifier = Modifier.fitContentWidth().width(d.px(1264)),
                    )
                }
            } }
        }
        compose.waitForIdle()
        val node = compose.onAllNodes(hasText(longTitle)).fetchSemanticsNodes().single()
        assertTrue("laid out wider than the 720 px column", node.boundsInRoot.width > widthPx)
        assertTrue("stays inside the right safe edge", node.boundsInRoot.right <= rightLimitPx + 1f)
    }

    @Test
    fun aPlotThatFitsIsNotAFocusStop() {
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                PlotText(
                    text = "Short plot.",
                    style = browseType().body,
                    color = HappyTvColors.TextSecondary,
                    maxLines = 3,
                    focusRequester = FocusRequester(),
                    onOpen = {},
                )
            } }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Short plot.")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
    }

    @Test
    fun aTruncatedPlotOpensOnOk() {
        val opened = mutableStateOf(false)
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                Column(Modifier.size(width = d.px(720), height = d.px(600))) {
                    PlotText(
                        text = longPlot,
                        style = browseType().body,
                        color = HappyTvColors.TextSecondary,
                        maxLines = 3,
                        focusRequester = FocusRequester(),
                        onOpen = { opened.value = true },
                    )
                }
            } }
        }
        compose.waitForIdle()
        val plot = compose.onNode(hasText(longPlot.trim(), substring = true))
        plot.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
        plot.requestFocus()
        plot.performKeyInput { pressKey(Key.Enter) }
        assertTrue(opened.value)
    }

    @Test
    fun theDialogShowsTheWholePlotAndCloses() {
        val closed = mutableStateOf(false)
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                PlotDialog(title = "Title", plot = longPlot, onDismiss = { closed.value = true })
            } }
        }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasText(longPlot.trim(), substring = true)).fetchSemanticsNodes().size)
        compose.onNode(hasText("Close")).performClick()
        assertTrue(closed.value)
    }

    @Test
    fun theEpisodeNoteHasTheHeadingRuntimeAndDescription() {
        val episode = EpisodeItem(
            id = "e3", season = 1, number = 3, title = "The Long Night", runtimeLabel = "42 min",
            plot = "Everyone gathers at the fort.", thumbnailUrl = null,
        )
        compose.setContent { HappyTvTheme { HappyTvCanvas { EpisodeNote(episode) } } }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasText("S1 E3", substring = true)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText("42 min", substring = true)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText("Everyone gathers at the fort.")).fetchSemanticsNodes().size)
    }

    @Test
    fun theChannelNumberShrinksToTheNumberOnly() {
        assertEquals("101", channelNumberText("Channel 101"))
        assertNull(channelNumberText("Channel"))
        assertFalse(channelNumberText("Channel 7")!!.contains("Channel"))
    }

    @Test
    fun aPosterCaptionKeepsTheYearAfterTheTitle() {
        val text = posterCaptionText("Dune", "2021", androidx.compose.ui.graphics.Color.Gray)
        assertEquals("Dune  2021", text.text)
        assertEquals("Dune", posterCaptionText("Dune", null, androidx.compose.ui.graphics.Color.Gray).text)
    }

    @Test
    fun theYearAppearsOnceWhateverFormTheTitleHasIt() {
        listOf(
            "The Last Bus (2022)", "The Last Bus [2022]", "The Last Bus - 2022", "The Last Bus 2022",
            "The Last Bus-2022", "The Last Bus \u2013 2022",
        ).forEach {
            assertEquals(it, DisplayTitle(null, "The Last Bus", "2022"), displayTitle(it, "2022"))
        }
        // No year from the provider: only a bracketed one is taken from the title.
        assertEquals(DisplayTitle(null, "Law & Order", "1990"), displayTitle("Law & Order (1990)", null))
        assertEquals(DisplayTitle(null, "Law & Order 1990", null), displayTitle("Law & Order 1990", null))
        assertEquals(DisplayTitle(null, "Law & Order", "1990"), displayTitle("Law & Order (1990)", "1990"))
    }

    @Test
    fun aNumberThatIsPartOfTheTitleStays() {
        assertEquals(DisplayTitle(null, "Blade Runner 2049", "2017"), displayTitle("Blade Runner 2049", "2017"))
        assertEquals(DisplayTitle(null, "1984", "1984"), displayTitle("1984", "1984"))
        assertEquals(DisplayTitle(null, "Wonder Woman 1984", "2020"), displayTitle("Wonder Woman 1984", "2020"))
        // A different year in the title is not ours to drop.
        assertEquals(DisplayTitle(null, "Dune (1984)", "2021"), displayTitle("Dune (1984)", "2021"))
    }

    @Test
    fun theTagAndTheYearBothComeOff() {
        assertEquals(DisplayTitle("4K", "Dune", "2021"), displayTitle("[4K] Dune - 2021", "2021"))
    }
}
