package com.rysal.happytv.ui.main

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #192: the long-press menu traps focus; Back closes it and focus returns to the tile. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class DashboardMenuFocusTrapTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(DashboardUiState(menuTile = StreamType.LIVE))
    private var closed = 0

    /** Stops on every side of the menu, as the tiles are on the dashboard, with the menu over them. */
    private fun setUp() {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    val tile = remember { FocusRequester() }
                    Box(Modifier.fillMaxSize()) {
                        listOf(
                            "tileLeft" to Alignment.CenterStart,
                            "tileRight" to Alignment.CenterEnd,
                            "tileUp" to Alignment.TopCenter,
                            "tileDown" to Alignment.BottomCenter,
                        ).forEach { (tag, align) ->
                            Box(
                                Modifier.align(align).size(40.dp).background(Color.Gray)
                                    .testTag(tag).focusable(),
                            )
                        }
                        Box(Modifier.size(40.dp).focusRequester(tile).testTag("tile").focusable())
                        RestoreFocusWhenMenuCloses(state.menuTile) { tile.requestFocus() }
                        if (state.menuTile != null) {
                            TileLongPressMenu(
                                state = state,
                                onRefresh = {},
                                onShowAbout = { state = state.copy(menuShowingAbout = true) },
                                onClose = {
                                    closed++
                                    state = state.copy(menuTile = null, menuShowingAbout = false)
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `arrow keys at the first item stay in the menu`() {
        setUp()
        for (key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp)) {
            press(key)
            compose.onNodeWithContentDescription("Refresh Live TV now").assertIsFocused()
        }
    }

    @Test
    fun `arrow keys at the last item stay in the menu`() {
        setUp()
        press(Key.DirectionDown)
        compose.onNodeWithContentDescription("About this data").assertIsFocused()
        for (key in listOf(Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            press(key)
            compose.onNodeWithContentDescription("About this data").assertIsFocused()
        }
    }

    @Test
    fun `arrow keys in the about view stay on close`() {
        setUp()
        press(Key.DirectionDown)
        press(Key.Enter)
        compose.onNodeWithContentDescription("Close").assertIsFocused()
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            press(key)
            compose.onNodeWithContentDescription("Close").assertIsFocused()
        }
    }

    @Test
    fun `back closes the menu and focus returns to the tile`() {
        setUp()
        press(Key.Back)
        assertEquals(1, closed)
        compose.onNodeWithTag("tile").assertIsFocused()
    }
}
