package com.rysal.happytv.ui.main

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.view.KeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.browse.ignoreOrphanEnterUp
import com.rysal.happytv.ui.browse.okHoldKeys
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #145: holding OK on a dashboard tile opens the menu; the release of that hold must do
 * nothing. The harness uses the same modifier chains as `TileBody` (okHoldKeys ahead of
 * combinedClickable) and `MenuItem` (ignoreOrphanEnterUp ahead of clickable), with the
 * menu taking focus the moment it opens, as `TileLongPressMenu` does.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DashboardHoldOkReleaseTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var opened = 0
    private var menuOpens = 0
    private var itemClicks = 0
    private val tileRequester = FocusRequester()

    @Composable
    private fun Harness() {
        val tile = remember { tileRequester }
        val item = remember { FocusRequester() }
        var menuOpen by remember { mutableStateOf(false) }
        Column {
            Box(
                Modifier
                    .testTag("tile")
                    .size(80.dp)
                    .focusRequester(tile)
                    .okHoldKeys(onLongClick = { menuOpens++; menuOpen = true })
                    .combinedClickable(onLongClick = { menuOpens++; menuOpen = true }, onClick = { opened++ })
                    .focusable(),
            )
            if (menuOpen) {
                Box(
                    Modifier
                        .testTag("item")
                        .size(80.dp)
                        .focusRequester(item)
                        .ignoreOrphanEnterUp()
                        .clickable { itemClicks++ }
                        .focusable(),
                )
                LaunchedEffect(Unit) { item.requestFocus() }
            }
        }
    }

    private fun start() {
        opened = 0
        menuOpens = 0
        itemClicks = 0
        compose.setContent { Harness() }
        compose.runOnIdle { tileRequester.requestFocus() }
        compose.onNodeWithTag("tile").assertIsFocused()
    }

    /** A native key event to the focused node, the way a remote's hold arrives (down, repeats, up). */
    private fun key(action: Int, code: Int, repeat: Int = 0) {
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(0L, 0L, action, code, repeat))
        }
        compose.waitForIdle()
    }

    private fun down(repeat: Int = 0) = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat)
    private fun up() = key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)

    private fun holdUntilMenuOpens() {
        down(0)
        down(1) // first repeat: the menu opens and takes focus while OK is still held
        compose.onNodeWithTag("item").assertIsFocused()
    }

    @Test
    fun `hold with repeats opens the menu once and the release clicks nothing`() {
        start()
        holdUntilMenuOpens()
        down(2) // further repeats land on the menu item
        up()
        assertEquals(1, menuOpens)
        assertEquals(0, itemClicks)
        assertEquals(0, opened)
        compose.onNodeWithTag("item").assertIsFocused()
    }

    @Test
    fun `a fresh press on the menu item after the hold clicks it`() {
        start()
        holdUntilMenuOpens()
        up() // the release of the hold: swallowed
        assertEquals(0, itemClicks)
        down()
        up()
        assertEquals(1, itemClicks)
        assertEquals(0, opened)
    }

    @Test
    fun `a short press on the tile still opens it`() {
        start()
        down()
        up()
        compose.waitForIdle()
        assertEquals(1, opened)
        assertEquals(0, menuOpens)
    }

    @Test
    fun `the menu key opens the menu`() {
        start()
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)
        compose.waitForIdle()
        assertEquals(1, menuOpens)
        assertEquals(0, opened)
    }
}
