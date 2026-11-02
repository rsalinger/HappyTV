package com.rysal.happytv.ui.settings

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #59: vertical D-pad movement in a Settings pane must visit every row in
 * order, and a segmented row must be entered on its selected segment. QA on
 * the TV: Down from "Refresh on start" skipped the Stream format segmented row
 * and the next Down jumped to the rail's About row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsVerticalFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: SettingsViewModel

    private fun setUp(section: SettingsSection) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) {
            File(context.cacheDir, "settings-vfocus-${System.nanoTime()}.preferences_pb")
        }
        val viewModel = SettingsViewModel(
            settings = SettingsRepository(dataStore),
            loadAccount = { SettingsAccount("Home", "provider.example", 8080, "demo-user", 0L) },
            onRefreshEverything = {},
            onSignOut = {},
            onGuideScheduleChanged = {},
            appScope = CoroutineScope(Dispatchers.Unconfined),
            startSection = section,
        )
        vm = viewModel
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

    /** The focused node's first text (row label or segment label). */
    private fun focusedText(): String {
        val nodes = compose.onAllNodes(isFocusable() and isFocused()).fetchSemanticsNodes()
        assertEquals("exactly one focused node", 1, nodes.size)
        return nodes[0].config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: ""
    }

    private fun press(key: Key) {
        compose.onAllNodes(isFocusable() and isFocused())[0].performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Right from the rail into the pane, then Down through [expected] and Up back. */
    private fun walk(section: SettingsSection, expected: List<String>) {
        setUp(section)
        assertEquals(section.label, focusedText())
        press(Key.DirectionRight)
        val down = mutableListOf(focusedText())
        repeat(expected.size - 1) {
            press(Key.DirectionDown)
            down += focusedText()
        }
        assertEquals("Down order", expected, down)
        val up = mutableListOf(focusedText())
        repeat(expected.size - 1) {
            press(Key.DirectionUp)
            up += focusedText()
        }
        assertEquals("Up order", expected.reversed(), up)
    }

    @Test
    fun refreshPane_visitsEveryRowInOrder() = walk(
        SettingsSection.REFRESH,
        listOf("Refresh on start", "Adaptive (m3u8)", "Refresh everything now"),
    )

    @Test
    fun liveTvPane_visitsEveryRowInOrder() = walk(
        SettingsSection.LIVE_TV,
        listOf("Favorites", "Keep categories open"),
    )

    @Test
    fun guidePane_visitsEveryRowInOrder() = walk(
        SettingsSection.GUIDE,
        listOf("From provider", "12 hours"),
    )

    @Test
    fun playerPane_visitsEveryRowInOrder() = walk(
        SettingsSection.PLAYER,
        listOf("Start replays from the beginning", "Show channels", "Auto"),
    )

    @Test
    fun accountPane_visitsEveryRowInOrder() = walk(
        SettingsSection.ACCOUNT,
        listOf("Server", "Username", "Signed in", "Back up favorites and history", "Switch account"),
    )

    @Test
    fun aboutPane_visitsEveryRowInOrder() = walk(
        SettingsSection.ABOUT,
        listOf("Version", "Build", "Your data", "Reset to defaults"),
    )

    @Test
    fun downOnLastRow_staysInThePane() {
        setUp(SettingsSection.REFRESH)
        press(Key.DirectionRight)
        repeat(4) { press(Key.DirectionDown) }
        assertEquals("Refresh everything now", focusedText())
    }

    /**
     * #162: Left from a pane row goes to the rail row of the section that is open. It used to land on
     * the rail row level with the focused row, and the rail selects on focus, so the pane switched.
     */
    private fun leftReturnsToSection(section: SettingsSection, firstRow: String, rowsDown: Int = 0) {
        setUp(section)
        press(Key.DirectionRight)
        assertEquals(firstRow, focusedText())
        repeat(rowsDown) { press(Key.DirectionDown) }
        press(Key.DirectionLeft)
        assertEquals(section.label, focusedText())
        assertEquals(section, vm.ui.value.section)
    }

    @Test
    fun leftFromLiveTvRow_returnsToLiveTv() = leftReturnsToSection(SettingsSection.LIVE_TV, "Favorites", rowsDown = 1)

    @Test
    fun leftFromLastRefreshRow_returnsToRefresh() = leftReturnsToSection(SettingsSection.REFRESH, "Refresh on start", rowsDown = 2)

    @Test
    fun leftFromLastAboutRow_returnsToAbout() = leftReturnsToSection(SettingsSection.ABOUT, "Version", rowsDown = 3)

    @Test
    fun leftFromHiddenItemsRow_returnsToHiddenItems() = leftReturnsToSection(SettingsSection.HIDDEN, "No hidden categories")

    @Test
    fun leftBetweenSegments_stillMovesWithinTheSegmentedRow() {
        setUp(SettingsSection.REFRESH)
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertEquals("Adaptive (m3u8)", focusedText())
        press(Key.DirectionRight)
        assertEquals("TS", focusedText())
        press(Key.DirectionLeft)
        assertEquals("Adaptive (m3u8)", focusedText())
    }
}
