package com.rysal.happytv.ui.login

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
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
 * F-32: Gboard's **Next** button on the sign-in form must advance the form —
 * it used to be swallowed by `TvTextField`'s default `onImeAction = {}`.
 *
 * Each test drives the real screen: the ViewModel's one-shot focus target puts
 * the D-pad on the field, OK opens it for editing (handoff: "OK on a field
 * opens the system keyboard"), and the field's IME action fires through
 * `KeyboardActions.onNext` exactly as Gboard would send it. What is asserted
 * afterwards is where focus landed, that editing ended, and — on the password
 * field — that nothing was submitted (design spec: OK on the button confirms).
 *
 * `@Config(application = Application::class)` pins the plain framework
 * Application so app-startup wiring (Graph, Room, cert pinning) stays out of
 * scope, same as the other Robolectric tests here.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LoginScreenImeHandoffTest {

    @get:Rule
    val compose = createComposeRule()

    /** Counted, so "Next does not submit" is an assertion and not a hope. */
    private var submitted = 0

    // Handoff form values — the fields must be filled so every stop has text
    // to be found by, and so masking is observable on the password.
    private val serverUrl = "provider.example:8080"
    private val username = "demo-user"
    private val password = "hunter2"
    private val passwordShown = "•".repeat(password.length)

    // ------------------------------------------------------------------ drives

    /**
     * Renders the sign-in card with [focusTarget] already focused, the way
     * [LoginViewModel] leaves it after any state change that moves focus.
     */
    private fun setScreen(focusTarget: LoginFocusTarget) {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LoginScreen(
                        state = LoginUiState(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            focusTarget = focusTarget,
                        ),
                        onServerUrlChange = {},
                        onUsernameChange = {},
                        onPasswordChange = {},
                        onRememberMeToggle = {},
                        onSubmit = { submitted++ },
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
    }

    /**
     * Focus [from], press OK on it (which starts editing — the field's
     * `onPreviewKeyEvent`), then fire the field's IME action for real.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun imeNextFrom(from: LoginFocusTarget, fromText: String) {
        setScreen(from)
        compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyDescendant(hasText(fromText)))
            .performKeyInput { pressKey(Key.DirectionCenter) }
        // TvTextField waits out a 60 ms IME settle before the editor takes
        // focus, so the editable field shows up a beat after the OK press.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
    }

    /** The field stopped editing — focus left it, which is what ends `editing`. */
    private fun assertEditingEnded() {
        assertTrue(
            "a text field is still in editing mode",
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun assertFocusOn(text: String) {
        compose.onNode(isFocused() and hasAnyDescendant(hasText(text))).assertExists()
    }

    // ------------------------------------------------------------------ tests

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun serverUrlNext_movesFocusToUsername() {
        imeNextFrom(LoginFocusTarget.ServerUrl, serverUrl)

        assertFocusOn(username)
        assertEditingEnded()
        assertEquals("Next must never sign in", 0, submitted)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun usernameNext_movesFocusToPassword() {
        imeNextFrom(LoginFocusTarget.Username, username)

        assertFocusOn(passwordShown)
        assertEditingEnded()
        assertEquals("Next must never sign in", 0, submitted)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun passwordNext_movesFocusToSignInWithoutSubmitting() {
        imeNextFrom(LoginFocusTarget.Password, passwordShown)

        assertFocusOn("Sign in")
        assertEditingEnded()
        assertEquals("design spec: OK on the button is what confirms", 0, submitted)
    }
}
