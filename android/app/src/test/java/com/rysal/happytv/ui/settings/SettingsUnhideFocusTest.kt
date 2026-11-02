package com.rysal.happytv.ui.settings

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.dao.HiddenChannelListing
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #114: unhiding a row in Settings > Guide > hidden categories hands focus to the row that
 * takes its place (the next one, the previous one when it was last, the "None" row when the
 * list empties), instead of dropping it to the top.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsUnhideFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val hidden = MutableStateFlow(
        listOf("News", "Sports", "Docs", "Kids").mapIndexed { i, name ->
            Category(id = "c$i", type = StreamType.LIVE, name = name, isHidden = true)
        },
    )

    private val channels = MutableStateFlow(
        listOf(
            HiddenChannelListing("1", "Alpha One", "News"),
            HiddenChannelListing("2", "Bravo Two", "Sports"),
            HiddenChannelListing("3", "Charlie Three", null),
            HiddenChannelListing("4", null, null),
        ),
    )

    private fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) {
            File(context.cacheDir, "settings-unhide-${System.nanoTime()}.preferences_pb")
        }
        val viewModel = SettingsViewModel(
            settings = SettingsRepository(dataStore),
            loadAccount = { SettingsAccount("Home", "provider.example", 8080, "demo-user", 0L) },
            onRefreshEverything = {},
            onSignOut = {},
            onGuideScheduleChanged = {},
            appScope = CoroutineScope(Dispatchers.Unconfined),
            startSection = SettingsSection.HIDDEN,
            hiddenChannels = channels,
            onUnhideChannel = { id -> channels.update { rows -> rows.filterNot { it.streamId == id } } },
            hiddenCategories = hidden,
            onUnhideCategory = { id, _ -> hidden.update { rows -> rows.filterNot { it.id == id } } },
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

    private fun focusedText(): String {
        val nodes = compose.onAllNodes(isFocusable() and isFocused()).fetchSemanticsNodes()
        if (nodes.size != 1) return "<${nodes.size} focused>"
        return nodes[0].config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: ""
    }

    private fun press(key: Key) {
        compose.onAllNodes(isFocusable() and isFocused())[0].performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun awaitFocused(text: String) {
        compose.waitUntil(5_000) { focusedText() == text }
        assertEquals(text, focusedText())
    }

    /** Rail -> pane, then Down to the row labelled [label]. */
    private fun walkTo(label: String) {
        press(Key.DirectionRight)
        var presses = 0
        while (focusedText() != label && presses++ < 20) press(Key.DirectionDown)
        assertEquals(label, focusedText())
    }

    @Test
    fun unhidingAMiddleRow_focusesTheRowThatTookItsPlace() {
        setUp()
        // Sorted by name: Docs, Kids, News, Sports.
        walkTo("Show Kids")

        press(Key.DirectionCenter)

        awaitFocused("Show News")
    }

    @Test
    fun unhidingTheLastRow_focusesThePreviousRow() {
        setUp()
        walkTo("Show Sports")

        press(Key.DirectionCenter)

        awaitFocused("Show News")
    }

    @Test
    fun unhidingAChannel_focusesTheNextChannelRow() {
        setUp()
        walkTo("Show Alpha One")

        press(Key.DirectionCenter)

        awaitFocused("Show Bravo Two")
        assertEquals(listOf("2", "3", "4"), channels.value.map { it.streamId })
    }

    @Test
    fun unhidingTheLastChannel_focusesThePreviousRow() {
        setUp()
        // The channel that left the catalog is listed last, and still removable.
        walkTo("Show removed channel")

        press(Key.DirectionCenter)

        awaitFocused("Show Charlie Three")
        assertEquals(listOf("1", "2", "3"), channels.value.map { it.streamId })
    }

    @Test
    fun unhidingTheOnlyChannel_focusesTheNoChannelsRow() {
        channels.value = channels.value.take(1)
        setUp()
        walkTo("Show Alpha One")

        press(Key.DirectionCenter)

        awaitFocused("No hidden channels")
    }

    @Test
    fun emptyGroups_sayWhatIsMissing() {
        hidden.value = emptyList()
        channels.value = emptyList()
        setUp()

        compose.onNodeWithText("Categories").assertExists()
        compose.onNodeWithText("Channels").assertExists()
        compose.onNodeWithText("No hidden categories").assertExists()
        compose.onNodeWithText("No hidden channels").assertExists()
    }

    @Test
    fun bothGroupsAreListed_withCaptions() {
        hidden.value = hidden.value.take(1)
        channels.value = channels.value.drop(1)
        setUp()

        compose.onNodeWithText("Show News").assertExists()
        compose.onNodeWithText("Hidden from Live TV", substring = true).assertExists()
        // The list is windowed: walk down so each channel row is composed before it is checked.
        walkTo("Show Bravo Two")
        compose.onNodeWithText("Sports").assertExists()
        press(Key.DirectionDown)
        compose.onNodeWithText("No category").assertExists()
        press(Key.DirectionDown)
        compose.onNodeWithText("No longer offered by your provider").assertExists()
    }

    @Test
    fun downFromTheLastCategory_entersTheChannelsGroup() {
        hidden.value = hidden.value.take(1)
        setUp()
        walkTo("Show News")

        press(Key.DirectionDown)

        assertEquals("Show Alpha One", focusedText())
    }

    @Test
    fun unhidingTheOnlyRow_focusesTheNoneRow() {
        hidden.value = hidden.value.take(1)
        setUp()
        walkTo("Show News")

        press(Key.DirectionCenter)

        awaitFocused("No hidden categories")
    }
}
