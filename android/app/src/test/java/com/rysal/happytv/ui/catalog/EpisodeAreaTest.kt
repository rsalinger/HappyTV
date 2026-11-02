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
 * #74: the season chips and the episode list under the actions. A Play button stands in for the
 * action row; the area sits where the details page puts it (x = 560, ending at the 54 px bottom
 * safe edge).
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EpisodeAreaTest {

    @get:Rule
    val compose = createComposeRule()

    private fun episode(season: Int, n: Int, title: String = "Title $season-$n", plot: String? = "Plot $season-$n") =
        EpisodeItem("e$season-$n", season, n, title, "42 min", plot, null)

    private fun seasons(vararg counts: Pair<Int, Int>) =
        counts.map { (season, count) -> SeasonItem(season, (1..count).map { episode(season, it) }) }

    private var picked by mutableStateOf<Int?>(null)
    private val played = mutableListOf<String>()
    private var retries = 0
    private var bottomLimitPx = 0f

    private fun show(
        state: EpisodesState,
        marks: Map<String, EpisodeMark> = emptyMap(),
        selected: Int? = null,
    ) {
        picked = selected
        val play = FocusRequester()
        val focus = EpisodeFocus()
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                val listState = rememberLazyListState()
                Box(
                    Modifier.offsetPx(560, 560)
                        .size(width = d.px(1264), height = d.px(1026 - 560))
                        // The 54 px safe edge, as laid out on this screen.
                        .onGloballyPositioned { bottomLimitPx = it.boundsInRoot().bottom },
                ) {
                Column(Modifier.fillMaxSize()) {
                    HtPrimaryButton(label = "Play S1 E1", onClick = {}, focusRequester = play)
                    EpisodeArea(
                        state = state,
                        marks = marks,
                        selectedSeason = picked ?: (state as? EpisodesState.Loaded)?.seasons?.firstOrNull()?.number,
                        onSeasonSelected = { picked = it },
                        onPlayEpisode = { played += it.id },
                        onRetry = { retries += 1 },
                        focus = focus,
                        listState = listState,
                        modifier = Modifier.requiredWidth(d.px(1264)).weight(1f),
                    )
                }
                }
                LaunchedEffect(Unit) { play.requestFocus() }
            } }
        }
        compose.waitForIdle()
    }

    private fun down() { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; compose.waitForIdle() }
    private fun up() { compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }; compose.waitForIdle() }
    private fun right() { compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }; compose.waitForIdle() }

    private fun placed(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().any { it.layoutInfo.isPlaced }

    @Test
    fun `Down from the actions lands on the selected chip, Down again on the list, Up goes back`() {
        show(EpisodesState.Loaded(seasons(1 to 3, 2 to 3, 3 to 3)), selected = 2)
        compose.onNodeWithText("Play S1 E1").assertIsFocused()

        down()
        // Not the nearest chip (Season 1): the selected one.
        compose.onNodeWithText("Season 2").assertIsFocused()

        down()
        compose.onNodeWithText("E1 · Title 2-1", substring = true).assertIsFocused()

        down()
        compose.onNodeWithText("E2 · Title 2-2", substring = true).assertIsFocused()

        up(); up()
        compose.onNodeWithText("Season 2").assertIsFocused()
        up()
        compose.onNodeWithText("Play S1 E1").assertIsFocused()
    }

    @Test
    fun `focusing another chip swaps the list and focus stays on the chip`() {
        show(EpisodesState.Loaded(seasons(1 to 2, 2 to 2, 3 to 2)))
        down()
        compose.onNodeWithText("Season 1").assertIsFocused()
        assertTrue(placed("Title 1-1"))

        right()
        compose.onNodeWithText("Season 2").assertIsFocused()
        assertTrue("the list follows the chip", placed("Title 2-1"))
        assertEquals(false, placed("Title 1-1"))

        right()
        compose.onNodeWithText("Season 3").assertIsFocused()
        assertTrue(placed("Title 3-2"))
    }

    @Test
    fun `OK on an episode plays that episode`() {
        show(EpisodesState.Loaded(seasons(1 to 2)))
        down(); down()
        compose.onNodeWithText("E1 · Title 1-1", substring = true).performClick()
        assertEquals(listOf("e1-1"), played)
    }

    @Test
    fun `season 0 reads Specials`() {
        show(EpisodesState.Loaded(seasons(1 to 1, 0 to 1)))
        assertTrue(placed("Specials"))
        assertTrue(placed("Season 1"))
        assertEquals(false, placed("Season 0"))
    }

    @Test
    fun `watched episodes say so and an episode part way is not marked watched`() {
        show(
            EpisodesState.Loaded(seasons(1 to 3)),
            marks = mapOf(
                "e1-1" to EpisodeMark(watched = true),
                "e1-2" to EpisodeMark(fraction = 0.4f, positionMillis = 900_000L),
            ),
        )
        val watched = compose.onAllNodes(hasText("Watched")).fetchSemanticsNodes().count { it.layoutInfo.isPlaced }
        assertEquals(1, watched)
    }

    @Test
    fun `the error state offers Try again`() {
        show(EpisodesState.Failed)
        assertTrue(placed("Could not load episodes."))
        down()
        compose.onNodeWithText("Try again").assertIsFocused()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `while loading there is a skeleton and nothing to focus`() {
        show(EpisodesState.Loading)
        assertEquals(false, placed("Season"))
        down()
        compose.onNodeWithText("Play S1 E1").assertIsFocused()
    }

    @Test
    fun `the last episode and its ring stay above the bottom safe area`() {
        show(EpisodesState.Loaded(seasons(1 to 9)))
        down()
        repeat(10) { down() }
        compose.onNodeWithText("E9 · Title 1-9", substring = true).assertIsFocused()
        val bottom = compose.onAllNodes(hasText("E9 · Title 1-9", substring = true))
            .fetchSemanticsNodes().first { it.layoutInfo.isPlaced }.boundsInRoot.bottom
        val all = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
            .filter { it.layoutInfo.isPlaced }.joinToString { "${it.boundsInRoot}" }
        assertTrue("row bottom $bottom within $bottomLimitPx; nodes $all", bottom <= bottomLimitPx)
    }
}
