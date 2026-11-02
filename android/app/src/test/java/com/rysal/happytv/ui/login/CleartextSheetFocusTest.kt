package com.rysal.happytv.ui.login

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-66: the unencrypted-server sheet must be reachable with the D-pad.
 *
 * On build `21aca46` the sheet rendered all three buttons while **no node held
 * focus**, so neither "Use HTTPS" nor "Continue anyway" could be pressed and a
 * plain-HTTP provider could not be signed into from the TV at all
 * (`qa` evidence: 30 nodes, every one `focused="false"`).
 *
 * The contract is in the design — design spec §"Unencrypted-server sheet": "Buttons:
 * **Use HTTPS** (primary, focused; …), **Continue anyway** …, **Cancel**". This
 * test drives the real screen with the D-pad only and asserts that contract:
 * focus lands on Use HTTPS when the sheet opens, RIGHT walks to Continue
 * anyway, and OK on Continue anyway answers the sheet.
 *
 * `@Config(application = Application::class)` pins the plain framework
 * Application so app-startup wiring (Graph, Room, cert pinning) stays out of
 * scope, same as the other Robolectric Compose tests here.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CleartextSheetFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val hostLabel = "10.0.0.50:8092"

    /** Counted so "OK pressed Continue anyway" is an assertion, not a hope. */
    private var continueAnywayPressed = 0
    private var useHttpsPressed = 0
    private var cancelPressed = 0

    /**
     * Renders the sign-in screen with the sheet already open — the state
     * [LoginViewModel.attempt] leaves behind on a plain-HTTP URL with no
     * remembered consent — and gives it the one-shot focus ask the ViewModel
     * hands the screen when it raises the sheet.
     */
    private fun setScreenWithSheetOpen(focusTarget: LoginFocusTarget?) {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LoginScreen(
                        state = LoginUiState(
                            serverUrl = "http://$hostLabel",
                            username = "demo",
                            password = "hunter2",
                            overlay = LoginOverlay.CleartextSheet(hostLabel),
                            focusTarget = focusTarget,
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
                        onSheetUseHttps = { useHttpsPressed++ },
                        onSheetContinueAnyway = { continueAnywayPressed++ },
                        onSheetCancel = { cancelPressed++ },
                        onFocusHandled = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The button's *focusable* node is the pill Box; its label is a child Text.
     * `!isRoot()` matters: the whole screen is one ancestor node that also has
     * a "Use HTTPS" descendant, so without it the matcher finds two nodes and
     * the assertion reports "found 2 nodes" instead of where focus is.
     */
    private fun button(label: String) = hasAnyDescendant(hasText(label)) and !isRoot()

    /**
     * F-66, the mechanism. `focusProperties { canFocus = false }` does not stay
     * on the node it is written on — it propagates to every focusable
     * descendant. The sheet's scrim carried it, so all three buttons inherited
     * it and none could take focus: the QA dump showed every node
     * `focused="false"`, and OK did nothing on any of them.
     *
     * The scrim itself is not a focus stop (it has no `focusable`), so the
     * property guarded nothing and cost the whole modal. If a future Compose
     * bump stops propagating it, this test goes red and the sheet's scrim can
     * go back to being inert for the right reason instead of by accident.
     */
    @Test
    fun canFocusFalseScrim_wouldStillBlockDescendantFocus() {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .focusProperties { canFocus = false },
                    ) {
                        Box(Modifier.focusable())
                    }
                }
            }
        }
        compose.waitForIdle()

        assertEquals(
            "the property propagates — it is why the scrim must not carry it",
            0,
            compose.onAllNodes(isFocusable() and isFocused()).fetchSemanticsNodes().size,
        )
    }

    /**
     * F-66, the dead end itself: a node must hold focus the moment the sheet
     * opens — and per the handoff it must be the primary action, not whatever
     * the form was on before.
     */
    @Test
    fun sheetOpens_withUseHttpsFocused() {
        setScreenWithSheetOpen(focusTarget = LoginFocusTarget.SheetUseHttps)

        compose.onNode(button("Use HTTPS")).assertIsFocused()
    }

    /**
     * F-66, the walk: RIGHT from the primary action reaches the secondary one,
     * which is the answer a QA panel actually needs ("Continue anyway").
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightFromUseHttps_movesToContinueAnyway() {
        setScreenWithSheetOpen(focusTarget = LoginFocusTarget.SheetUseHttps)

        compose.onNode(button("Use HTTPS")).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        compose.onNode(button("Continue anyway")).assertIsFocused()
    }

    /** RIGHT again lands on Cancel — the third stop is reachable too. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightTwice_movesToCancel() {
        setScreenWithSheetOpen(focusTarget = LoginFocusTarget.SheetUseHttps)

        compose.onNode(button("Use HTTPS")).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNode(button("Continue anyway")).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        compose.onNode(button("Cancel")).assertIsFocused()
    }

    /**
     * F-66, the payoff: OK on "Continue anyway" answers the sheet. The button
     * fires on the key **release** (`TvButton`'s `onPreviewKeyEvent` watches
     * `KeyUp`), so the test sends a real press-and-release the way a remote
     * does — a bare `performClick` would not exercise the D-pad path at all.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun okOnContinueAnyway_answersTheSheet() {
        setScreenWithSheetOpen(focusTarget = LoginFocusTarget.SheetUseHttps)

        compose.onNode(button("Use HTTPS")).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNode(button("Continue anyway")).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals("OK on 'Continue anyway' must sign in over http", 1, continueAnywayPressed)
        assertEquals("OK must not also fire the primary action", 0, useHttpsPressed)
        assertEquals("OK must not cancel the sheet", 0, cancelPressed)
    }

    /** The primary action is pressable from the same starting position. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun okOnUseHttps_answersTheSheet() {
        setScreenWithSheetOpen(focusTarget = LoginFocusTarget.SheetUseHttps)

        compose.onNode(button("Use HTTPS")).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals("OK on 'Use HTTPS' must switch to https and sign in", 1, useHttpsPressed)
        assertEquals(0, continueAnywayPressed)
    }

    /** Cancel is pressable too — the third stop is not decorative. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun okOnCancel_answersTheSheet() {
        setScreenWithSheetOpen(focusTarget = LoginFocusTarget.SheetUseHttps)

        compose.onNode(button("Use HTTPS")).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNode(button("Continue anyway")).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNode(button("Cancel")).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals(1, cancelPressed)
        assertEquals(0, continueAnywayPressed)
    }
}