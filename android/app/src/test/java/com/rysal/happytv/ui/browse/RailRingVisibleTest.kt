package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.focus.requestWhenAttached
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shared rail (Live TV, Movies, Shows): a focused row, with room for its ring and glow, is
 * always fully inside the rail's viewport, however focus got there. QA saw the top row cut at the
 * header after scrolling down and back up (#72).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RailRingVisibleTest {

    @get:Rule
    val compose = createComposeRule()

    private var ringPx = 0f
    private val rows = List(30) { index ->
        RailEntry.Row(key = "row-$index", label = "Row $index", count = index, icon = null, selected = index == 0)
    }

    private fun start() {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    val d = browseDesign()
                    ringPx = with(LocalDensity.current) { d.px(RAIL_RING_PX).toPx() }
                    val requester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { requester.requestWhenAttached() }
                    HtRail(
                        entries = rows,
                        listState = rememberLazyListState(),
                        entryKey = "row-0",
                        entryRequester = requester,
                        onSelect = {},
                        onRowFocused = {},
                        onLongPress = {},
                        // About ten rows tall, so thirty scroll.
                        modifier = Modifier
                            .testTag("rail")
                            .size(width = d.px(RAIL_WIDTH_PX), height = d.px(740)),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun assertFocusedRowInsideWithRing(step: String) {
        val viewport = compose.onNodeWithTagBounds("rail")
        val row = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        assertTrue("$step: row top ${row.top} - ring $ringPx is above the viewport top ${viewport.top}", row.top - ringPx >= viewport.top - 0.5f)
        assertTrue("$step: row bottom ${row.bottom} + ring $ringPx is below the viewport bottom ${viewport.bottom}", row.bottom + ringPx <= viewport.bottom + 0.5f)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithTagBounds(tag: String) =
        onNode(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the first row has its ring room when the rail opens`() {
        start()
        assertFocusedRowInsideWithRing("opening")
    }

    @Test
    fun `going down the whole rail and back up keeps the focused row, ring included, in view`() {
        start()
        repeat(rows.size - 1) { step ->
            press(Key.DirectionDown)
            assertFocusedRowInsideWithRing("down ${step + 1}")
        }
        repeat(rows.size - 1) { step ->
            press(Key.DirectionUp)
            assertFocusedRowInsideWithRing("up ${step + 1}")
        }
        // Back on the first row, with all of its ring room.
        val viewport = compose.onNodeWithTagBounds("rail")
        val row = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        assertEquals("first row is focused again", viewport.top + ringPx, row.top, 1f)
    }
}
