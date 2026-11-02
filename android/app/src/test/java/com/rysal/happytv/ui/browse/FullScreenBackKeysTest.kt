package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #16 QA: in full screen, Back belongs to the host whatever has focus. The harness
 * mimics the combined screen: a focusable item under a root handler that, like
 * LiveTvScreen's, fans the rail out on Back unless the player is full screen.
 * Covers the three ways OK enters Full (guide dialog "Watch now", a Search
 * result, a guide channel cell): in each the focus is either still in the combined
 * screen or nowhere when the first Back arrives.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class FullScreenBackKeysTest {

    @get:Rule
    val compose = createComposeRule()

    private var full by mutableStateOf(true)
    private val backs = mutableListOf<Boolean>()
    private var railOpened = 0
    private val item = FocusRequester()

    private fun show() {
        compose.setContent {
            Box(Modifier.fullScreenBackKeys(active = full, onBack = { backs += it })) {
                Box(
                    Modifier
                        .size(100.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Back && !full) {
                                if (event.type == KeyEventType.KeyDown) railOpened++
                                true
                            } else {
                                false
                            }
                        }
                        .focusRequester(item)
                        .focusable(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
    }

    @Test
    fun `back in full goes to the host when focus is still in the combined screen`() {
        show()
        compose.runOnIdle { item.requestFocus() }
        pressBack()
        assertEquals(listOf(false), backs)
        assertEquals(0, railOpened)
    }

    @Test
    fun `with nothing focused compose delivers no key at all so the rail cannot open`() {
        // Measured: without a focus target the key reaches neither handler. The host
        // then gets Back through the system dispatcher (a BackHandler enabled in
        // Full), and entering Full moves focus to the player canvas with awaitFocus.
        show()
        pressBack()
        assertEquals(0, railOpened)
    }

    @Test
    fun `back outside full is left to the screen`() {
        show()
        full = false
        compose.waitForIdle()
        compose.runOnIdle { item.requestFocus() }
        pressBack()
        assertEquals(emptyList<Boolean>(), backs)
        assertEquals(1, railOpened)
    }
}
