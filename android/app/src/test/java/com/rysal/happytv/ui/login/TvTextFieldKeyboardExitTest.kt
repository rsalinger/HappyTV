package com.rysal.happytv.ui.login

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #187 items 1 and 2: leaving the keyboard must leave editing (the D-pad moves
 * again at once) and focus must stay on the field, never fall to the first
 * stop on screen (here the first field stands in for Server URL).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TvTextFieldKeyboardExitTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val firstFocus = FocusRequester()
    private val secondFocus = FocusRequester()

    private fun setScreen() {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    Column {
                        TvTextField(
                            value = "one", onValueChange = {}, placeholder = "", accessibleName = "First",
                            focusRequester = firstFocus,
                        )
                        TvTextField(
                            value = "two", onValueChange = {}, placeholder = "", accessibleName = "Second",
                            focusRequester = secondFocus,
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun startEditingSecond() {
        setScreen()
        compose.waitForIdle()
        compose.runOnIdle { secondFocus.requestFocus() }
        compose.onNode(isFocused() and hasContentDescription("Second"))
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertNotEditing() {
        compose.waitForIdle()
        assertTrue(
            "the field is still in editing mode",
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * The TV's keyboard takes Back before the app's normal key handlers (this
     * is what a test TV showed in #187), so the field must end editing from
     * the pre-keyboard hook, and keep focus on itself.
     */
    @Test
    fun backBeforeKeyboard_endsEditing_andKeepsFocusOnTheField() {
        startEditingSecond()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }

        assertNotEditing()
        compose.onNode(isFocused() and hasContentDescription("Second")).assertExists()
    }

    /** Back delivered to the app (no keyboard) also leaves focus on the field. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backKey_whileEditing_keepsFocusOnTheField() {
        startEditingSecond()
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Back) }

        assertNotEditing()
        compose.onNode(isFocused() and hasContentDescription("Second")).assertExists()
    }
}
