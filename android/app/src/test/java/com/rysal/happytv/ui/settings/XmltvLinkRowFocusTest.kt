package com.rysal.happytv.ui.settings

import android.app.Application
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
 * #18: landing on the XMLTV link field (D-pad Right from the rail) must not
 * open an editor or the keyboard — only OK does. Not editing means no text
 * input node exists; the shown text is the non-secret label.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class XmltvLinkRowFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val typed = mutableListOf<String>()
    private val requester = FocusRequester()

    private fun show() {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    XmltvLinkRow(
                        value = SettingsViewModel.AUTO_GUIDE_LABEL,
                        onValueChange = { typed += it },
                        focusRequester = requester,
                        onFocused = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun focusAloneDoesNotStartEditing_andShowsOnlyTheLabel() {
        show()
        compose.runOnIdle { requester.requestFocus() }
        compose.waitForIdle()

        compose.onNode(isFocusable()).assertIsFocused()
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        assertTrue(typed.isEmpty())
    }

    /** #53: Back in the editor must hand focus back to the link field, not drop it. */
    @Test
    fun backFromTheEditor_returnsFocusToTheLinkField() {
        show()
        compose.runOnIdle { requester.requestFocus() }
        compose.waitForIdle()
        compose.onNode(isFocusable()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)

        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Back) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        compose.onNode(isFocusable()).assertIsFocused()
    }

    @Test
    fun okStartsEditing_withAnEmptyBuffer() {
        show()
        compose.runOnIdle { requester.requestFocus() }
        compose.waitForIdle()

        compose.onNode(isFocusable())
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()

        assertEquals(1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        // The stored link is never loaded into the editor.
        assertTrue(compose.onAllNodes(hasText(SettingsViewModel.AUTO_GUIDE_LABEL)).fetchSemanticsNodes().isEmpty())
    }
}
