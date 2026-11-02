package com.rysal.happytv.ui.main

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The dashboard's tile bodies route Up through [dpadNav] placed before
 * `combinedClickable().focusable()`, the chain TileBody uses. (Placed after, the
 * handler did not fire on the TV and Up fell back to geometry.) Up on the last tile
 * (Shows) must reach the theme toggle even though the toggle is not its geometric
 * neighbour; Up on the others stays on Search.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DashboardToggleFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `up from the last tile reaches the toggle and up from the others reaches search`() {
        val search = FocusRequester()
        val toggle = FocusRequester()
        val tiles = List(3) { FocusRequester() }
        compose.setContent {
            Column {
                Row {
                    Stop("search", search, Modifier.dpadNav(right = { toggle.requestFocus() }, down = { tiles[0].requestFocus() }))
                    Stop("toggle", toggle, Modifier.dpadNav(left = { search.requestFocus() }, down = { tiles[2].requestFocus() }))
                }
                Row {
                    tiles.forEachIndexed { i, r ->
                        Stop(
                            "tile$i",
                            r,
                            Modifier.dpadNav(up = { if (i == tiles.lastIndex) toggle.requestFocus() else search.requestFocus() }),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("tile2").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("tile2").assertIsFocused()
        compose.onNodeWithTag("tile2").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("toggle").assertIsFocused()
        compose.onNodeWithTag("toggle").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("tile2").assertIsFocused()

        compose.onNodeWithTag("tile1").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("tile1").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("search").assertIsFocused()
        compose.onNodeWithTag("search").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("toggle").assertIsFocused()
    }

    @Composable
    private fun Stop(tag: String, requester: FocusRequester, nav: Modifier) {
        Box(
            Modifier
                .size(60.dp)
                .testTag(tag)
                .focusRequester(requester)
                .then(nav)
                .combinedClickable(onClick = {})
                .focusable(),
        )
    }
}
