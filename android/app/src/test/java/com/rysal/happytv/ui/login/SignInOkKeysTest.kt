package com.rysal.happytv.ui.login

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #243: every sign-in control reads OK the way the rest of the app does (`htvClickable`):
 * D-pad centre, Enter and numpad Enter each activate it on release, and a key-up that never
 * had a key-down on that control (OK still held when the screen appears) activates nothing.
 * Runs on the real [LoginScreen], with the key events dispatched to the activity the way a
 * remote delivers them. Fake data only.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class SignInOkKeysTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clicks = mutableListOf<String>()

    private val okKeys = listOf(
        "DPAD_CENTER" to KeyEvent.KEYCODE_DPAD_CENTER,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "NUMPAD_ENTER" to KeyEvent.KEYCODE_NUMPAD_ENTER,
    )

    private val saved = SavedServer(
        id = "s1",
        name = "Home",
        hostLabel = "provider.example:8080",
        username = "demo",
        lastSignedInLabel = null,
        inUse = false,
    )

    private fun show(state: LoginUiState) {
        clicks.clear()
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LoginScreen(
                        state = state,
                        onServerUrlChange = {},
                        onUsernameChange = {},
                        onPasswordChange = {},
                        onRememberMeToggle = { clicks += "remember" },
                        onSubmit = { clicks += "signin" },
                        onRetry = { clicks += "retry" },
                        onChangeServer = { clicks += "change" },
                        onOpenUrlFormats = { clicks += "formats" },
                        onCloseUrlFormats = { clicks += "close" },
                        onPickUrlFormat = { clicks += "pick" },
                        onPickSavedServer = { clicks += "saved" },
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

    private fun control(label: String): SemanticsMatcher =
        hasClickAction() and (hasText(label) or hasContentDescription(label))

    private fun focus(label: String) {
        compose.onNode(control(label)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun send(action: Int, code: Int) {
        compose.activityRule.scenario.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(0L, 0L, action, code, 0))
        }
        compose.waitForIdle()
    }

    private fun press(code: Int) {
        send(KeyEvent.ACTION_DOWN, code)
        send(KeyEvent.ACTION_UP, code)
    }

    private fun assertEachOkKeyActivates(label: String, expected: String) {
        for ((name, code) in okKeys) {
            clicks.clear()
            focus(label)
            press(code)
            assertEquals("$name on '$label'", listOf(expected), clicks.toList())
        }
    }

    private fun assertLoneKeyUpActivatesNothing(label: String) {
        clicks.clear()
        focus(label)
        for ((name, code) in okKeys) {
            send(KeyEvent.ACTION_UP, code)
            assertEquals("lone $name key-up on '$label'", emptyList<String>(), clicks.toList())
        }
    }

    private val form = LoginUiState(serverUrl = "provider.example:8080", username = "demo", password = "pw")

    @Test
    fun signInButton_takesEveryOkKey_andIgnoresLoneKeyUp() {
        show(form)
        assertEachOkKeyActivates("Sign in", "signin")
        assertLoneKeyUpActivatesNothing("Sign in")
    }

    @Test
    fun checkbox_takesEveryOkKey_andIgnoresLoneKeyUp() {
        show(form)
        assertEachOkKeyActivates("Remember me on this TV", "remember")
        assertLoneKeyUpActivatesNothing("Remember me on this TV")
    }

    @Test
    fun textLink_takesEveryOkKey_andIgnoresLoneKeyUp() {
        show(form)
        assertEachOkKeyActivates("Common URL formats", "formats")
        assertLoneKeyUpActivatesNothing("Common URL formats")
    }

    @Test
    fun retryAndChangeServer_takeEveryOkKey_andIgnoreLoneKeyUp() {
        show(form.copy(phase = LoginPhase.Error(LoginErrorKind.Unreachable, "Can't reach the server.")))
        assertEachOkKeyActivates("Retry", "retry")
        assertEachOkKeyActivates("Change server", "change")
        assertLoneKeyUpActivatesNothing("Retry")
        assertLoneKeyUpActivatesNothing("Change server")
    }

    @Test
    fun savedServerButton_takesEveryOkKey_andIgnoresLoneKeyUp() {
        show(form.copy(savedServers = listOf(saved), savedServersLoaded = true))
        assertEachOkKeyActivates("Home", "saved")
        assertLoneKeyUpActivatesNothing("Home")
    }

    @Test
    fun urlFormatOption_takesEveryOkKey_andIgnoresLoneKeyUp() {
        show(form.copy(overlay = LoginOverlay.UrlFormats, focusTarget = LoginFocusTarget.FormatOption))
        assertEachOkKeyActivates("http://provider.example:8080", "pick")
        assertLoneKeyUpActivatesNothing("http://provider.example:8080")
    }
}
