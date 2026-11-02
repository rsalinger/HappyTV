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
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.guide.GuideViewModel
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-43 end to end, on the JVM: the real [LiveTvScreen] over a real Room-backed
 * [BrowseViewModel], driven the way a Chromecast remote drives it — DPAD_CENTER
 * injected into the focused channel row.
 *
 * The three acceptance lines, each as its own test:
 *  - long-press OK on a channel opens the submenu (and does **not** play);
 *  - short press still plays;
 *  - Back closes the menu without playing — and the D-pad lands back on the row.
 *
 * Plus the menu's D-pad contract: focus moves into the first item when it
 * opens, Up/Down walk the items, OK on "Add to favorites" flips the row's star.
 *
 * `@Config(application = Application::class)` pins the plain framework
 * Application so Graph/app-startup wiring stays out of scope, same as the
 * other Robolectric tests here. The clock is 0 so no guide row matches and
 * every channel label reads the deterministic "… replay available" form.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LiveTvChannelLongPressTest {

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
        // #10: Live TV opens category-first; these tests drive the channel rows.
        viewModel.selectCategory(LiveCategorySelection.AllChannels)
        advanceUntilIdle()
        played = 0
    }

    @After
    fun tearDown() {
        // Cancel and JOIN before the Main dispatcher goes away or the db
        // closes. A Room flow query still in flight at db.close() throws
        // "Cannot perform this operation because the connection pool has been
        // closed" on its own thread — an uncaught exception that poisons the
        // next test with UncaughtExceptionsBeforeTest. Joining the scope's job
        // drains every collector while the db is still open, so the race
        // cannot happen (and Main is still set while they drain).
        runBlocking {
            for (job in listOf(viewModel, guideViewModel).map { it.viewModelScope.coroutineContext.job }) {
                job.cancel()
                job.join()
            }
        }
        Dispatchers.resetMain()
        db.close()
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
    private fun focusNorthbayRow() {
        compose.onNode(northbayRow)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it?.invoke() }
        compose.waitForIdle()
        compose.onNode(northbayRow and isFocused()).assertExists()
    }

    /** The hold every TV app uses: down, stay past the 500 ms, up. */
    private fun holdOkOn(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNode(matcher).performKeyInput { pressKey(Key.DirectionCenter, 800) }
        compose.waitForIdle()
    }

    private fun assertMenuOpenOnFavoriteItem() {
        compose.onNode(hasText("Add to favorites")).assertExists()
        // F-43: the D-pad is *in* the menu — the first item holds focus.
        compose.onNode(isFocused() and hasText("Add to favorites")).assertExists()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun shortPress_stillPlaysTheChannelAndNeverOpensTheMenu() {
        setScreen()
        focusNorthbayRow()

        compose.onNode(northbayRow).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals("a short OK press is still play", 1, played)
        compose.onNode(hasText("Add to favorites")).assertDoesNotExist()
    }

    @Test
    fun longPress_opensTheChannelMenuAndDoesNotPlay() {
        setScreen()
        focusNorthbayRow()

        holdOkOn(northbayRow)

        assertEquals("a hold must not play behind the menu", 0, played)
        assertMenuOpenOnFavoriteItem()
    }

    @Test
    fun back_closesTheMenuWithoutPlayingAndReturnsToTheRow() {
        setScreen()
        focusNorthbayRow()
        holdOkOn(northbayRow)
        assertMenuOpenOnFavoriteItem()

        compose.onNode(isFocused()).performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()

        compose.onNode(hasText("Add to favorites")).assertDoesNotExist()
        assertEquals("Back closes the menu; it never plays", 0, played)
        // Handoff: focus hands back to whatever opened the menu — the row.
        compose.onNode(northbayRow and isFocused()).assertExists()
    }

    @Test
    fun menuIsDpadNavigableAndFavoriteFlipsTheStarOnTheRow() {
        setScreen()
        focusNorthbayRow()
        holdOkOn(northbayRow)
        assertMenuOpenOnFavoriteItem()

        // Down / Up walk the items (both channels carry replay, so item two is
        // "Watch from the start").
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasText("Watch from the start")).assertExists()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertMenuOpenOnFavoriteItem()

        // OK selects: the menu closes, the row's star flips — and no play.
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        compose.onNode(hasText("Add to favorites")).assertDoesNotExist()
        // The star flips only after the favorite write lands in Room and the
        // observing flow re-emits — both on Room's executors, which
        // waitForIdle() knows nothing about. Wait for the flip itself; asserting
        // straight after waitForIdle() raced the write on a loaded host.
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodes(hasContentDescription("Remove from favorites"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasContentDescription("Remove from favorites")).assertExists()
        assertEquals("selecting a menu item never plays the channel", 0, played)
        // Focus landed back on the opening row, ready for the next gesture.
        compose.onNode(northbayRow and isFocused()).assertExists()
    }
}
