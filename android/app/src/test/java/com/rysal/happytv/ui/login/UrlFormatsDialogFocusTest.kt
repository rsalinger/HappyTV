package com.rysal.happytv.ui.login

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The other modal on this screen, checked while fixing F-66 (the cleartext
 * sheet). Its scrim carries the same `focusProperties { canFocus = false }`
 * that made the sheet a dead end — but it also wraps the panel in its own
 * `focusable()` Box, so this pins down which of the two shapes actually works.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class UrlFormatsDialogFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private fun button(label: String): SemanticsMatcher {
        val contains = hasAnyDescendant(hasText(label))
        return SemanticsMatcher("innermost node containing '$label'") { node ->
            contains.matches(node) && node.children.none { contains.matches(it) }
        }
    }

    private fun setScreenWithFormatsOpen() {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LoginScreen(
                        state = LoginUiState(
                            serverUrl = "provider.example:8080",
                            username = "demo",
                            password = "hunter2",
                            overlay = LoginOverlay.UrlFormats,
                            focusTarget = LoginFocusTarget.FormatOption,
                        ),
                        onServerUrlChange = {},
                        onUsernameChange = {},
                        onPasswordChange = {},
                        onRememberMeToggle = {},
                        onSubmit = {},
                        onRetry = {},
                        onChangeServer = {},
                        onM3uPressed = {},
                        onOpenUrlFormats = {},
                        onCloseUrlFormats = {},
                        onPickUrlFormat = {},
                        onPickSavedServer = {},
                        onSheetUseHttps = {},
                        onSheetContinueAnyway = {},
                        onSheetCancel = {},
                        onFocusHandled = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The dialog opens on its first option, per the handoff. */
    @Test
    fun formatsDialogOpens_onFirstOption() {
        setScreenWithFormatsOpen()

        compose.onNode(button("http://provider.example:8080")).assertIsFocused()
    }

    /** DOWN walks to the next option — the dialog is not a single-stop trap. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun down_movesToNextOption() {
        setScreenWithFormatsOpen()

        compose.onNode(button("http://provider.example:8080"))
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()

        compose.onNode(button("http://provider.example")).assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(from: String, key: Key) {
        compose.onNode(button(from)).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /**
     * #161: Down from the last example landed on "Remember me on this TV" in the form behind the
     * scrim. Walk every example, then keep pressing the arrows: focus must stay on a dialog
     * control.
     */
    @Test
    fun focusStaysInTheDialog_whateverTheArrow() {
        setScreenWithFormatsOpen()

        press("http://provider.example:8080", Key.DirectionDown)
        press("http://provider.example", Key.DirectionDown)
        press("https://provider.example:8443", Key.DirectionDown)
        compose.onNode(button("Close")).assertIsFocused()

        for (key in listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionLeft)) {
            press("Close", key)
            compose.onNode(button("Close")).assertIsFocused()
        }
        // Up walks back through the examples and stops on the first one.
        press("Close", Key.DirectionUp)
        press("https://provider.example:8443", Key.DirectionUp)
        press("http://provider.example", Key.DirectionUp)
        press("http://provider.example:8080", Key.DirectionUp)
        compose.onNode(button("http://provider.example:8080")).assertIsFocused()
    }

    /** The helper line matches the https-only behaviour of parsePanelUrl (#22). */
    @Test
    fun formatsDialog_explainsHttpsDefault() {
        setScreenWithFormatsOpen()

        compose.onNode(
            hasText("You can leave off https://. HappyTV uses an encrypted connection unless you type http://."),
        ).assertExists()
        compose.onNode(hasText("tries https first", substring = true)).assertDoesNotExist()
    }
}