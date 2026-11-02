package com.rysal.happytv.ui.login

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #165: the three form fields have a name a screen reader (and `tv-ok.sh`, which reads the
 * accessibility dump) can use even while empty, and an empty username shows its message under the
 * field like the server URL and password do.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LoginFieldsAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(state: LoginUiState) {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LoginScreen(
                        state = state,
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

    @Test
    fun emptyFields_haveAccessibleNames() {
        setScreen(LoginUiState())

        listOf("Server URL", "Username", "Password").forEach { name ->
            compose.onNode(hasContentDescription(name) and isFocusable()).assertExists()
        }
    }

    @Test
    fun passwordValue_isNeverInTheAccessibleName() {
        setScreen(LoginUiState(password = "hunter2"))

        compose.onNode(hasContentDescription("Password") and isFocusable()).assertExists()
        compose.onNode(hasContentDescription("hunter2", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("hunter2", substring = true)).assertDoesNotExist()
    }

    @Test
    fun usernameError_isShownUnderTheField_andNamedOnIt() {
        setScreen(LoginUiState(usernameError = "Enter your username."))

        compose.onNode(hasText("Enter your username.")).assertExists()
        compose.onNode(hasContentDescription("Username. Enter your username.") and isFocusable()).assertExists()
    }
}
