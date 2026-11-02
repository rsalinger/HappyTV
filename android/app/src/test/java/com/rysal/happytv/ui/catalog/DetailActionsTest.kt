package com.rysal.happytv.ui.catalog

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import com.rysal.happytv.ui.browse.HtPrimaryButton
import com.rysal.happytv.ui.browse.HtSecondaryButton
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
 * #78 + #79: the details action row. With Resume, Start over and the favorite button, nothing is
 * cut, everything stays left of the 1824 px right safe edge when the row starts at the text
 * column (x = 560), and D-pad Right walks all three.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DetailActionsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun detail(resume: ResumePoint?, favorite: Boolean = false, series: Boolean = false) = CatalogDetail(
        id = "a", name = "Alpha", isSeries = series, year = null, runtimeLabel = null, ageLabel = null,
        ratingLabel = null, genres = emptyList(), plot = null, cast = emptyList(), isFavorite = favorite,
        resume = resume,
    )

    private var rightEdgePx = 0f
    private var limitPx = 0f

    private fun show(detail: CatalogDetail) {
        val play = FocusRequester()
        val fav = FocusRequester()
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                val density = LocalDensity.current
                limitPx = with(density) { d.px(1824).toPx() }
                Box(Modifier.fillMaxSize().offsetPx(560, 0)) {
                    DetailActions(
                        detail = detail,
                        onPlay = { _, _ -> },
                        onToggleFavorite = {},
                        playButton = play,
                        favoriteButton = fav,
                    )
                }
                LaunchedEffect(Unit) { play.requestFocus() }
            } }
        }
        compose.waitForIdle()
    }

    private fun placedRightEdge(): Float = compose.onAllNodes(
        hasText("Resume", substring = true) or hasText("Start over") or hasText("Add to favorites") or
            hasText("In favorites") or hasText("Add to favorites"),
    ).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }.maxOf { it.boundsInRoot.right }

    @Test
    fun `three full-label buttons fit before the right safe edge with the longest resume label`() {
        show(detail(ResumePoint(3_909_000L, null, null)))
        compose.onNodeWithText("Resume from 1:05:09").assertExists()
        compose.onNodeWithText("Start over").assertExists()
        compose.onNodeWithText("Add to favorites").assertExists()
        // Text nodes are placed whole: their bounds are not clipped by a narrower parent.
        val right = placedRightEdge()
        // The row starts at x = 560 (shifted in px by the canvas scale already applied to limit).
        assertTrue("row ends at $right, limit $limitPx", right <= limitPx + 0.5f)
    }

    @Test
    fun `D-pad right walks Resume, Start over and the favorite button`() {
        show(detail(ResumePoint(2_480_000L, null, null)))
        compose.onNodeWithText("Resume from 41:20").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Start over").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Add to favorites").assertIsFocused()
    }

    @Test
    fun `the favorite reads In favorites when starred`() {
        show(detail(ResumePoint(2_480_000L, null, null), favorite = true))
        compose.onNodeWithText("In favorites").assertExists()
    }

    @Test
    fun `without progress the favorite keeps its full label`() {
        show(detail(null))
        compose.onNodeWithText("Play").assertExists()
        compose.onNodeWithText("Add to favorites").assertExists()
    }

    @Test
    fun `an up next episode has Play and the full favorite label, and no Start over`() {
        show(detail(ResumePoint(0L, "e2", "S2 E4"), series = true))
        compose.onNodeWithText("Play S2 E4").assertExists()
        compose.onNodeWithText("Add to favorites").assertExists()
        assertEquals(0, compose.onAllNodes(hasText("Start over")).fetchSemanticsNodes().size)
    }

    @Test
    fun `measure three full labels against the right safe edge, the reason the favorite is a star`() {
        var rowRight = 0f
        compose.setContent {
            HappyTvTheme { HappyTvCanvas {
                val d = browseDesign()
                val density = LocalDensity.current
                limitPx = with(density) { d.px(1824).toPx() }
                Box(Modifier.fillMaxSize().offsetPx(560, 0)) {
                    Row(
                        Modifier.fitContentWidth().onGloballyPositioned { rowRight = it.boundsInRoot().right },
                        horizontalArrangement = Arrangement.spacedBy(d.px(24)),
                    ) {
                        HtPrimaryButton(label = "Resume from 1:05:09", onClick = {})
                        HtSecondaryButton(label = "Start over", onClick = {})
                        HtSecondaryButton(label = "Add to favorites", onClick = {})
                    }
                }
            } }
        }
        compose.waitForIdle()
        println("DetailActionsMeasure fullLabelsRight=$rowRight limit=$limitPx")
    }
}
