package com.rysal.happytv.ui.catalog

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.browse.HtPrimaryButton
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.offsetPx
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #74: an episode row holds its title, plot line and thumbnail whole. Run on a 1920 x 1080 dp, mdpi
 * window, so one design px is one device px: at the default tiny Robolectric screen the text
 * metrics round up and a row that fits on the TV reads as cut.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w1920dp-h1080dp-mdpi")
class EpisodeRowFitTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: EpisodesState) {
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                val listState = rememberLazyListState()
                Box(Modifier.offsetPx(560, 560).size(width = d.px(1264), height = d.px(1026 - 560))) {
                    Column(Modifier.fillMaxSize()) {
                        EpisodeArea(
                            state = state, marks = emptyMap(), selectedSeason = 1,
                            onSeasonSelected = {}, onPlayEpisode = {}, onRetry = {},
                            focus = EpisodeFocus(), listState = listState,
                            modifier = Modifier.requiredWidth(d.px(1264)).weight(1f),
                        )
                    }
                }
            } }
        }
        compose.waitForIdle()
    }

    @Test
    fun `every row holds its title, its plot line and its thumbnail whole`() {
        show(EpisodesState.Loaded(listOf(SeasonItem(1, (1..3).map {
            EpisodeItem("e$it", 1, it, "Title $it", "42 min", "Plot $it", "https://img.example/t$it.jpg")
        }))))
        val rows = compose.onAllNodes(hasText("Title", substring = true))
            .fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }.map { it.boundsInRoot }
        assertEquals(3, rows.size)
        (1..3).forEach { n ->
            fun textNode(text: String, substring: Boolean) = compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true)
                .fetchSemanticsNodes().filter { it.layoutInfo.isPlaced && it.boundsInRoot.height > 0f }
                .minByOrNull { it.boundsInRoot.height } ?: error("no node for $text; have ${compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().map { "${it.layoutInfo.isPlaced} ${it.boundsInRoot} ${it.size}" }}")
            fun overflowed(node: androidx.compose.ui.semantics.SemanticsNode): Boolean {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                return results.any { it.didOverflowHeight }
            }
            val row = rows[n - 1]
            val plotNode = textNode("Plot $n", false)
            val titleNode = textNode("E$n", true)
            val plot = plotNode.boundsInRoot
            val title = titleNode.boundsInRoot
            assertTrue("plot $plot inside row $row", plot.top >= row.top && plot.bottom <= row.bottom)
            assertTrue("title $title inside row $row", title.top >= row.top && title.bottom <= row.bottom)
            assertTrue("title above plot", title.bottom <= plot.top + 1f)
            // 1 design px = 1 px here: the plot line keeps the row's 10 px bottom padding, so the
            // row's own clip never touches it.
            assertTrue("plot bottom ${plot.bottom} vs row bottom ${row.bottom}", row.bottom - plot.bottom >= 10f)
            assertEquals("plot text is cut", false, overflowed(plotNode))
            assertEquals("title text is cut", false, overflowed(titleNode))
        }
        // The thumbnail is 16:9 and no taller than the row.
        assertEquals(16f / 9f, THUMB_WIDTH_PX / THUMB_HEIGHT_PX.toFloat(), 0.02f)
        assertTrue(THUMB_HEIGHT_PX < ROW_HEIGHT_PX)
    }
}
