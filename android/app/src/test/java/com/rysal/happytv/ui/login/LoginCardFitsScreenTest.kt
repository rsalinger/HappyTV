package com.rysal.happytv.ui.login

import android.app.Application
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #187 item 4: with every validation message showing, nothing runs off the bottom. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LoginCardFitsScreenTest {

    private val CARD_BOTTOM_PADDING_PX = 40f

    @get:Rule
    val compose = createComposeRule()

    private fun assertStatusLineInsideScreen() {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LoginScreen(
                        state = LoginUiState(
                            serverUrlError = "Enter your server URL.",
                            usernameError = "Enter your username.",
                            passwordError = "Enter your password.",
                        ),
                        onServerUrlChange = {}, onUsernameChange = {}, onPasswordChange = {},
                        onRememberMeToggle = {}, onSubmit = {}, onRetry = {}, onChangeServer = {},
                        onM3uPressed = {}, onOpenUrlFormats = {}, onCloseUrlFormats = {},
                        onPickUrlFormat = {}, onPickSavedServer = {}, onSheetUseHttps = {},
                        onSheetContinueAnyway = {}, onSheetCancel = {}, onFocusHandled = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onRoot().getBoundsInRoot()
        val status = compose.onNodeWithText("Don’t have an account? Ask your IPTV provider.").getBoundsInRoot()
        // The card pads 40 artboard px under the status line; the whole card,
        // padding included, must end on screen.
        val padding = root.height * (CARD_BOTTOM_PADDING_PX / 1080f)
        assertTrue(
            "status line bottom ${status.bottom} + card padding $padding is below the screen bottom ${root.bottom}",
            status.bottom + padding <= root.bottom,
        )
    }

    @Test
    @Config(qualifiers = "w1920dp-h1080dp-mdpi")
    fun allErrors_fitAt1080p() = assertStatusLineInsideScreen()
}
