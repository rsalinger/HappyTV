package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.RoomTestTeardown
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.guide.GuideViewModel
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-53 end to end, on the JVM: the "Recently watched" pane of the real
 * [LiveTvScreen] over a real Room-backed [BrowseViewModel], driven with the
 * same key injection as [LiveTvChannelLongPressTest] (F-43's harness).
 *
 * The acceptance lines, each as its own test:
 *  - long-press OK on a recently-watched item opens the menu with
 *    "Remove from recently watched" (and the usual actions below it) — and
 *    does **not** play;
 *  - selecting remove drops the item from the list **and** from
 *    `recent_channels` (persisted), with focus handed to the next row;
 *  - short press still plays the channel;
 *  - removing the last item lands on the pane's own empty state.
 *
 * `@Config(application = Application::class)` pins the plain framework
 * Application so Graph/app-startup wiring stays out of scope. The clock is 0,
 * so every channel label reads the deterministic "… replay available" form.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LiveTvRecentlyWatchedRemoveTest {

    @get:Rule
    val compose = createComposeRule()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private lateinit var viewModel: BrowseViewModel
    private lateinit var guideViewModel: GuideViewModel

    /** Short presses and menu selects both come through here. */
    private var played = 0

    private val northbayRow = hasContentDescription(
        "Northbay News, channel 101, replay available",
    )
    private val capitalRow = hasContentDescription(
        "Capital Desk, channel 102, replay available",
    )

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.categoryDao().replaceAll(
            StreamType.LIVE,
            listOf(Category(id = "news", type = StreamType.LIVE, name = "News")),
        )
        db.liveStreamDao().replaceAll(
            listOf(
                LiveStream(
                    id = "101",
                    categoryId = "news",
                    num = 101,
                    name = "Northbay News",
                    tvArchive = true,
                    epgChannelId = "northbay.example",
                ),
                LiveStream(
                    id = "102",
                    categoryId = "news",
                    num = 102,
                    name = "Capital Desk",
                    tvArchive = true,
                    epgChannelId = "capital.example",
                ),
            ),
        )
        guideViewModel = testGuideViewModel(context, db)
        viewModel = BrowseViewModel(
            categoryDao = db.categoryDao(),
            liveStreamDao = db.liveStreamDao(),
            favoriteDao = db.favoriteDao(),
            recentChannelDao = db.recentChannelDao(),
            hiddenChannelDao = db.hiddenChannelDao(),
            epgProgramDao = db.epgProgramDao(),
            xmltvMode = flowOf(false),
            clock = { 0L },
            // These tests drive the channel list (#15 made the guide the default view).
            liveTvPrefs = flowOf(LiveTvPrefs(view = com.rysal.happytv.data.LiveTvView.LIST)),
        )
        // Both channels have been watched, and the pane is the one F-53 is
        // about: the "Recently watched" selection's channel list.
        viewModel.recordWatched("101")
        viewModel.recordWatched("102")
        viewModel.selectCategory(LiveCategorySelection.RecentlyWatched)
        advanceUntilIdle()
        played = 0
    }

    @After
    fun tearDown() {
        RoomTestTeardown.shutDown(listOf(viewModel.viewModelScope.coroutineContext.job, guideViewModel.viewModelScope.coroutineContext.job),
            db,
        )
    }

    private fun setScreen() {
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LiveTvScreen(
                        viewModelFactory = object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                viewModel as T
                        },
                        guideViewModelFactory = factoryOf(guideViewModel),
                        onPlayChannel = { played++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Nothing holds focus when the screen mounts, and key injection only
     * reaches the *focused* node — so take focus the way accessibility would:
     * through the row's own RequestFocus semantics action.
     */
    private fun focusRow(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNode(matcher)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it?.invoke() }
        compose.waitForIdle()
        compose.onNode(matcher and isFocused()).assertExists()
    }

    /** The hold every TV app uses: down, stay past the 500 ms, up. */
    private fun holdOkOn(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNode(matcher).performKeyInput { pressKey(Key.DirectionCenter, 800) }
        compose.waitForIdle()
    }

    private fun assertMenuOpenOnRemoveItem() {
        compose.onNode(hasText("Remove from recently watched")).assertExists()
        // F-43's contract, kept: the D-pad is *in* the menu — first item focused.
        compose.onNode(isFocused() and hasText("Remove from recently watched")).assertExists()
    }

    /**
     * Waits (bounded) for [matcher] to leave the tree. Remove writes through Room
     * and the list updates when the invalidation flow re-emits on another
     * thread, so an assertion straight after a key press races that hop.
     */
    private fun awaitGone(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Waits (bounded) for [matcher] to appear. */
    private fun awaitExists(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** What `recent_channels` holds right now — the persisted half of remove. */
    private fun storedRecentIds(): List<String> = runBlocking {
        db.recentChannelDao().recent().first().map { it.streamId }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun shortPress_stillPlaysTheRecentlyWatchedChannel() {
        setScreen()
        focusRow(northbayRow)

        compose.onNode(northbayRow).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals("a short OK press is still play", 1, played)
        compose.onNode(hasText("Remove from recently watched")).assertDoesNotExist()
    }

    @Test
    fun longPress_opensTheMenuWithRemoveFirstAndDoesNotPlay() {
        setScreen()
        focusRow(northbayRow)

        holdOkOn(northbayRow)

        assertEquals("a hold must not play behind the menu", 0, played)
        assertMenuOpenOnRemoveItem()
        // "Room for later actions": the F-43 actions still sit below the new one.
        compose.onNode(hasText("Add to favorites")).assertExists()
    }

    @Test
    fun remove_dropsTheRowPersistsAndHandsFocusToTheNextRow() {
        setScreen()
        focusRow(northbayRow)
        holdOkOn(northbayRow)
        assertMenuOpenOnRemoveItem()

        // OK on the menu item — same injection as the F-43 favorite test.
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        awaitGone(hasText("Remove from recently watched"))
        awaitGone(northbayRow)
        awaitExists(capitalRow)
        assertEquals("removing never plays", 0, played)
        // Persisted: the row is gone from `recent_channels`, not just the list.
        assertEquals(listOf("102"), storedRecentIds())
        // The pane keeps the D-pad: focus lands on the row that took its place.
        awaitExists(capitalRow and isFocused())
    }

    @Test
    fun remove_lastItem_landsOnTheRecentlyWatchedEmptyState() {
        setScreen()
        // Drop the second row first, then remove the only one left the way a
        // user would. `removeRecentlyWatched` writes through Room, whose
        // invalidation lands on its own executor — so wait for the row to
        // actually leave instead of pumping the test scheduler.
        viewModel.removeRecentlyWatched("102")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(capitalRow).fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(capitalRow).assertDoesNotExist()

        focusRow(northbayRow)
        holdOkOn(northbayRow)
        assertMenuOpenOnRemoveItem()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        // The row leaves and the empty state arrives via Room's async
        // invalidation, so wait on the real condition (this was the #41 flake).
        awaitGone(northbayRow)
        // F-53's own empty state, not the generic "No channels to show".
        awaitExists(hasText("No recently watched channels"))
        assertEquals(listOf<String>(), storedRecentIds())
        // F-57: the pane must not be left with nothing focused after the
        // last remove. The fix wires a FocusRequester on the empty state's
        // container Box (before HtEmptyState in the modifier chain), so
        // `focusedButton.tryRequestFocus()` finds the CTA button as the
        // first focusable after it. The very next D-pad press therefore
        // acts on a visible focused element, not the header back button.
        awaitExists(hasText("Browse all channels") and isFocused())
        assertTrue(
            "F-57: the empty state CTA must hold focus after the last remove",
            compose.onAllNodes(hasText("Browse all channels") and isFocused())
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
