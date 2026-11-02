package com.rysal.happytv.ui.settings

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #48 follow-up: the two Settings confirmations must take D-pad focus.
 *
 * QA on the TV: "Refresh everything now?" opened with nothing focused inside
 * it, LEFT/RIGHT moved the rows behind it and OK did nothing. This drives the
 * real [SettingsScreen] with real key events (same approach as
 * `CleartextSheetFocusTest`).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsDialogFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<String>()
    private lateinit var viewModel: SettingsViewModel
    private var signOutGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) {
            File(context.cacheDir, "settings-focus-${System.nanoTime()}.preferences_pb")
        }
        viewModel = SettingsViewModel(
            settings = SettingsRepository(dataStore),
            loadAccount = { SettingsAccount("Home", "provider.example", 8080, "demo-user", 0L) },
            onRefreshEverything = { actions += "refresh" },
            onSignOut = { actions += "sign-out"; signOutGate?.await() },
            onGuideScheduleChanged = {},
            appScope = CoroutineScope(Dispatchers.Unconfined),
            startSection = SettingsSection.REFRESH,
        )
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    SettingsScreen(
                        onBack = {},
                        onGoToDashboard = {},
                        onGoToSignIn = {},
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun node(label: String) = hasText(label) and isFocusable() and !isRoot()

    private fun focused() = compose.onAllNodes(isFocusable() and isFocused()).fetchSemanticsNodes()

    private fun press(label: String, key: Key) {
        compose.onNode(node(label)).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun open(dialog: SettingsDialog) {
        compose.runOnIdle {
            when (dialog) {
                SettingsDialog.SIGN_OUT -> viewModel.switchAccount()
                SettingsDialog.REFRESH_EVERYTHING -> viewModel.refreshEverything()
                SettingsDialog.ALLOW_UNENCRYPTED_SIGN_IN -> viewModel.setAllowSignInOverUnencrypted(true)
                SettingsDialog.RESET_DEFAULTS -> viewModel.resetToDefaults()
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun refreshDialog_opensWithCancelFocused_andOkOnConfirmRefreshes() {
        open(SettingsDialog.REFRESH_EVERYTHING)

        // #166: DESIGN.md says confirmations open with Cancel focused.
        compose.onNode(node("Cancel")).assertIsFocused()
        press("Cancel", Key.DirectionLeft)
        compose.onNode(node("Refresh everything")).assertIsFocused()
        press("Refresh everything", Key.DirectionCenter)
        assertEquals(listOf("refresh"), actions)
    }

    @Test
    fun refreshDialog_focusIsTrapped_andCancelDismisses() {
        open(SettingsDialog.REFRESH_EVERYTHING)

        compose.onNode(node("Cancel")).assertIsFocused()
        // Pushing further must keep focus on a dialog button, never the rows or rail behind.
        for (key in listOf(Key.DirectionRight, Key.DirectionDown, Key.DirectionUp)) {
            press("Cancel", key)
            assertEquals(1, focused().size)
            compose.onNode(node("Cancel")).assertIsFocused()
        }
        press("Cancel", Key.DirectionLeft)
        compose.onNode(node("Refresh everything")).assertIsFocused()
        press("Refresh everything", Key.DirectionLeft)
        compose.onNode(node("Refresh everything")).assertIsFocused()

        press("Refresh everything", Key.DirectionRight)
        press("Cancel", Key.DirectionCenter)
        assertNull(viewModel.ui.value.dialog)
        assertEquals(emptyList<String>(), actions)
        // Focus is back on the rail row that was focused before the dialog opened.
        compose.onNode(node("Refresh")).assertIsFocused()
    }

    @Test
    fun signOutDialog_opensWithCancelFocused_andOkOnCancelDoesNotSignOut() {
        open(SettingsDialog.SIGN_OUT)

        compose.onNode(node("Cancel")).assertIsFocused()
        press("Cancel", Key.DirectionCenter)
        assertNull(viewModel.ui.value.dialog)
        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun signOutDialog_leftReachesSignOut_andOkConfirms() {
        open(SettingsDialog.SIGN_OUT)

        press("Cancel", Key.DirectionLeft)
        compose.onNode(node("Sign out")).assertIsFocused()
        press("Sign out", Key.DirectionCenter)
        assertEquals(listOf("sign-out"), actions)
    }

    @Test
    fun unencryptedSignInDialog_opensWithCancelFocused_andOkOnCancelLeavesItOff() {
        open(SettingsDialog.ALLOW_UNENCRYPTED_SIGN_IN)

        compose.onNode(node("Cancel")).assertIsFocused()
        press("Cancel", Key.DirectionCenter)
        assertNull(viewModel.ui.value.dialog)
        assertEquals(false, viewModel.ui.value.settings.allowSignInOverUnencrypted)
    }

    @Test
    fun unencryptedSignInDialog_leftReachesTurnOn_andOkSavesIt() {
        open(SettingsDialog.ALLOW_UNENCRYPTED_SIGN_IN)

        press("Cancel", Key.DirectionLeft)
        compose.onNode(node("Turn on")).assertIsFocused()
        press("Turn on", Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(true, viewModel.ui.value.settings.allowSignInOverUnencrypted)
    }

    /** #159: the title names the signed-in server, not a constant. */
    @Test
    fun signOutDialog_titleNamesTheServer() {
        open(SettingsDialog.SIGN_OUT)

        compose.onNode(hasText("Sign out of Home?")).assertExists()
        compose.onNode(hasText("recently watched and hidden items", substring = true)).assertExists()
    }

    /** #166: Reset to defaults asks first, opens on Cancel, and Cancel changes nothing. */
    @Test
    fun resetDialog_opensWithCancelFocused_andOkOnCancelResetsNothing() {
        open(SettingsDialog.RESET_DEFAULTS)

        compose.onNode(hasText("Reset to defaults?")).assertExists()
        compose.onNode(node("Cancel")).assertIsFocused()
        press("Cancel", Key.DirectionCenter)
        assertNull(viewModel.ui.value.dialog)
        assertEquals(false, viewModel.ui.value.resetDone)
    }

    @Test
    fun resetDialog_leftReachesConfirm_andOkResets() {
        open(SettingsDialog.RESET_DEFAULTS)

        press("Cancel", Key.DirectionLeft)
        compose.onNode(node("Reset settings")).assertIsFocused()
        press("Reset settings", Key.DirectionCenter)
        compose.waitForIdle()
        assertNull(viewModel.ui.value.dialog)
        assertEquals(true, viewModel.ui.value.resetDone)
    }

    /** #160: after the confirmation the screen shows progress and nothing behind it takes focus. */
    @Test
    fun signOut_showsProgressAndTrapsFocusWhileItRuns() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        signOutGate = gate
        compose.runOnIdle { viewModel.switchAccount() }
        compose.waitForIdle()
        press("Cancel", Key.DirectionLeft)
        press("Sign out", Key.DirectionCenter)
        compose.waitForIdle()

        compose.onNode(hasText("Signing out")).assertExists()
        // Focus sits on the progress card, never on a rail or pane row.
        assertEquals(1, focused().size)
        val before = focused()[0].id
        for (key in listOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight)) {
            compose.onAllNodes(isFocusable() and isFocused())[0].performKeyInput { pressKey(key) }
            compose.waitForIdle()
            assertEquals(1, focused().size)
            assertEquals(before, focused()[0].id)
        }

        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNode(hasText("Signing out")).assertDoesNotExist()
    }
}
