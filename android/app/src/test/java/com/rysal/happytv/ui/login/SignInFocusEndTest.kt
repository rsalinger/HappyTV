package com.rysal.happytv.ui.login

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #246: the sign-in card ends at Sign in; Down, Right and Left from it go nowhere. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SignInFocusEndTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(extra: LoginUiState.() -> LoginUiState = { this }) {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LoginScreen(
                        state = LoginUiState(
                            serverUrl = "provider.example:8080",
                            username = "demo",
                            password = "pw",
                            focusTarget = LoginFocusTarget.Submit,
                        ).extra(),
                        onServerUrlChange = {},
                        onUsernameChange = {},
                        onPasswordChange = {},
                        onRememberMeToggle = {},
                        onSubmit = {},
                        onRetry = {},
                        onChangeServer = {},
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

    @OptIn(ExperimentalTestApi::class)
    private fun assertKeyKeepsFocusOnSignIn(key: Key, extra: LoginUiState.() -> LoginUiState = { this }) {
        show(extra)
        val signIn = compose.onNode(hasClickAction() and hasText("Sign in"))
        signIn.assertIsFocused()

        signIn.performKeyInput { pressKey(key) }
        compose.waitForIdle()

        signIn.assertIsFocused()
    }

    @Test
    fun downFromSignIn_keepsFocusOnSignIn() = assertKeyKeepsFocusOnSignIn(Key.DirectionDown)

    /** Without an explicit rule, spatial search jumped up to the Password field. */
    @Test
    fun rightFromSignIn_keepsFocusOnSignIn() = assertKeyKeepsFocusOnSignIn(Key.DirectionRight)

    @Test
    fun leftFromSignIn_keepsFocusOnSignIn() = assertKeyKeepsFocusOnSignIn(Key.DirectionLeft)

    private val saved = SavedServer(
        id = "s1",
        name = "Home",
        hostLabel = "provider.example:8080",
        username = "demo",
        lastSignedInLabel = null,
        inUse = false,
    )

    private val withRail: LoginUiState.() -> LoginUiState = { copy(savedServers = listOf(saved), savedServersLoaded = true) }

    /** The rail sits to the right of the card when saved servers exist. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightFromSignIn_withSavedServers_reachesFirstSavedServer() {
        show(withRail)
        val signIn = compose.onNode(hasClickAction() and hasText("Sign in"))
        signIn.assertIsFocused()

        signIn.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        compose.onNode(hasClickAction() and hasText("Home")).assertIsFocused()
    }
}
