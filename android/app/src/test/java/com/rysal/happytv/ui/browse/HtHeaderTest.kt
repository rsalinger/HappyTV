package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec #7 section 2 / issue #13: the compact inner-screen header is 72 px of
 * artboard, and the title and subtitle share **one** line, with the subtitle
 * ellipsizing instead of wrapping or clipping.
 *
 * Renders at 960 x 540 dp, density 2, so 1 artboard px is exactly 0.5 dp.
 * Robolectric measures text at about 1 px per character, so the "long" strings
 * are thousands of characters to genuinely overflow the 864 dp header.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class HtHeaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(title: String, subtitle: String, withSearch: Boolean = true) {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    HtHeader(
                        title = title,
                        subtitle = subtitle,
                        onBack = {},
                        onSearchClick = if (withSearch) ({}) else null,
                        onMoreClick = if (withSearch) ({}) else null,
                        modifier = Modifier.offset(48.dp, 27.dp).width(864.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun layoutOf(rule: ComposeContentTestRule, text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, substring = true).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    /**
     * #117: nothing lies left of Back. A focusable node to its left (the folded rail's rows, or any
     * off-screen node) must never take focus from a Left press; focus stays on Back.
     */
    @Test
    fun `Left from the back button keeps focus on Back even with a focusable to its left`() {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    androidx.compose.foundation.layout.Box {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .offset(0.dp, 27.dp)
                                .size(8.dp)
                                .focusable(),
                        )
                        HtHeader(
                            title = "Live TV",
                            subtitle = "",
                            onBack = {},
                            modifier = Modifier.offset(48.dp, 27.dp).width(864.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.RequestFocus) { it?.invoke() }
        compose.waitForIdle()
        repeat(3) {
            compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
            compose.waitForIdle()
            compose.onNode(hasContentDescription("Back") and isFocused()).assertExists()
        }
    }

    @Test
    fun `back and more buttons are 64 px`() {
        render("Live TV", "News · 86 channels")
        compose.onNodeWithContentDescription("Back").assertHeightIsEqualTo(32.dp)
        compose.onNodeWithContentDescription("More options").assertHeightIsEqualTo(32.dp)
        compose.onNode(hasText("Search")).assertIsDisplayed()
    }

    @Test
    fun `title starts 24 px after the back button and subtitle 16 px after the title`() {
        render("Live TV", "News · 86 channels")
        val back = compose.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Live TV").getUnclippedBoundsInRoot()
        val subtitle = compose.onNodeWithText("News · 86 channels").getUnclippedBoundsInRoot()
        // 1 artboard px = 0.5 dp here: 24 px gap = 12 dp, 16 px gap = 8 dp.
        assertEquals("title left edge", (back.right + 12.dp).value, title.left.value, 0.6f)
        assertEquals("subtitle left edge", (title.right + 8.dp).value, subtitle.left.value, 0.6f)
    }

    @Test
    fun `title is left aligned even when the right side is crowded`() {
        render("Settings", "Changes save right away and stay on this TV.", withSearch = false)
        val back = compose.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Settings").getUnclippedBoundsInRoot()
        assertEquals((back.right + 12.dp).value, title.left.value, 0.6f)
    }

    @Test
    fun `title and subtitle sit on one line`() {
        render("Live TV", "News · 86 channels")
        val title = compose.onNodeWithText("Live TV").getUnclippedBoundsInRoot()
        val subtitle = compose.onNodeWithText("News · 86 channels").getUnclippedBoundsInRoot()
        // Side by side, never stacked: the subtitle starts after the title ends…
        assertTrue("subtitle must follow the title", subtitle.left >= title.right)
        // …and the two vertical ranges overlap (same line).
        assertTrue("subtitle must share the title's line", subtitle.top < title.bottom)
        // Both fit inside the 36 dp (72 px) header.
        assertTrue(title.bottom - title.top <= 36.dp)
        assertTrue(subtitle.bottom - subtitle.top <= 36.dp)
    }

    @Test
    fun `a long subtitle ellipsizes on one line instead of clipping`() {
        val long = "Recently watched · " + "very long category name ".repeat(400) + "· 1,432 channels"
        render("Live TV", long)
        compose.onNodeWithText(long, substring = true).assertIsDisplayed()
        val layout = layoutOf(compose, long)
        assertEquals("subtitle must stay one line", 1, layout.lineCount)
        // Robolectric's text shadow cannot report the ellipsis itself, so
        // assert what it guarantees: the 9,600-px string was squeezed into the
        // room left of the Search pill instead of pushing it away or wrapping.
        val subtitle = compose.onNodeWithText(long, substring = true).getUnclippedBoundsInRoot()
        val search = compose.onNode(hasText("Search")).getUnclippedBoundsInRoot()
        assertTrue("subtitle must end before the controls", subtitle.right <= search.left)
        assertTrue(layout.size.width < long.length)
        assertTrue(subtitle.bottom - subtitle.top <= 36.dp)
        // The right-hand controls are still on screen.
        compose.onNode(hasText("Search")).assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").assertIsDisplayed()
    }

    @Test
    fun `a long title ellipsizes and keeps the subtitle`() {
        val long = "A very long poster grid category name ".repeat(300)
        render(long, "1,432 movies")
        compose.onNodeWithText("1,432 movies").assertIsDisplayed()
        val layout = layoutOf(compose, long)
        assertEquals(1, layout.lineCount)
        assertTrue(layout.size.width < long.length)
        val title = compose.onNodeWithText(long, substring = true).getUnclippedBoundsInRoot()
        val subtitle = compose.onNodeWithText("1,432 movies").getUnclippedBoundsInRoot()
        assertTrue("subtitle must stay after the title", subtitle.left >= title.right)
    }

    @Test
    fun `search and more are optional so settings and details can drop them`() {
        render("Settings", "Changes save right away and stay on this TV.", withSearch = false)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Search")).fetchSemanticsNodes().size)
    }

    @Test
    fun `header geometry constants are the spec values`() {
        assertEquals(72, HEADER_HEIGHT_PX)
        assertEquals(126, HEADER_BOTTOM_PX)
        assertEquals(64, HEADER_BUTTON_PX)
    }
}
