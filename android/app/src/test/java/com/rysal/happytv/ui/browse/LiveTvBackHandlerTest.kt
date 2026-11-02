package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.rysal.happytv.ui.guide.GuideViewModel
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Live TV's D-pad contract (spec #7 section 3.2, issue #14), driven through the
 * real [LiveTvScreen] with a real [BrowseViewModel] on in-memory Room.
 *
 * Every path of the accordion rail:
 *  - entry: Favorites with a favorite opens collapsed on the first channel;
 *    with none (or "Choose a category") the rail is open and focused, with the
 *    hint and no channel rows;
 *  - moving focus through the rail loads nothing;
 *  - OK or Right selects, folds the rail away and focuses the first channel;
 *  - Left from a channel row, or Back from the channels, fans the rail out
 *    with focus on the selected category; Left inside the rail goes nowhere;
 *  - Back from the rail leaves Live TV;
 *  - "Keep categories open": the rail stays, selecting moves focus to the list;
 *  - long-press on a category offers Hide category.
 *
 * Also F-40: Back must never crash on a detached [FocusRequester]. Nothing here
 * sleeps: every wait is on a real condition (a node, a focus, a view-model
 * state), which is what the #41 flake fixes asked for.
 *
 * Focus is taken with the semantics RequestFocus action, then D-pad keys go
 * through the compose root, so the tests do not depend on Robolectric granting
 * window focus.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class LiveTvBackHandlerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var db: HappyTvDatabase
    private var viewModel: BrowseViewModel? = null
    private var guideViewModel: GuideViewModel? = null

    /** Counted, so "BACK left the screen" is an assertion and not a hope. */
    private var backPresses = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        // Cancel and JOIN before the Main dispatcher goes away or the db
        // closes. A Room flow query still in flight at db.close() throws
        // "Cannot perform this operation because the connection pool has been
        // closed" on its own thread — an uncaught exception that poisons the
        // NEXT test with UncaughtExceptionsBeforeTest. Joining the scope's job
        // drains every collector while the db is still open.
        RoomTestTeardown.shutDown(listOf(
                viewModel?.viewModelScope?.coroutineContext?.job,
                guideViewModel?.viewModelScope?.coroutineContext?.job,
            ),
            db,
        )
    }

    /** These tests drive the channel list (#15 made the guide the default view). */
    private fun listPrefs(
        openOn: com.rysal.happytv.data.LiveTvOpenOn = com.rysal.happytv.data.LiveTvOpenOn.FAVORITES,
        keepCategoriesOpen: Boolean = false,
    ) = LiveTvPrefs(
        openOn = openOn,
        keepCategoriesOpen = keepCategoriesOpen,
        view = com.rysal.happytv.data.LiveTvView.LIST,
    )

    private fun newViewModel(prefs: LiveTvPrefs) = BrowseViewModel(
        categoryDao = db.categoryDao(),
        liveStreamDao = db.liveStreamDao(),
        favoriteDao = db.favoriteDao(),
        recentChannelDao = db.recentChannelDao(),
        hiddenChannelDao = db.hiddenChannelDao(),
        epgProgramDao = db.epgProgramDao(),
        xmltvMode = flowOf(false),
        clock = { 0L },
        liveTvPrefs = flowOf(prefs),
    )

    /**
     * Enough categories to scroll the rail, one channel per category so the
     * list is never empty while the rail is walked, and (when [withFavorite])
     * one favorite so Favorites has a row to open on.
     */
    private fun seedCatalog(withFavorite: Boolean, firstNumber: Int = 100) = runBlocking {
        val categories = (0 until RAIL_TEST_CATEGORY_COUNT).map { index ->
            Category(
                id = "cat$index",
                type = StreamType.LIVE,
                name = "Category %02d".format(index),
            )
        }
        db.categoryDao().replaceAll(StreamType.LIVE, categories)
        db.liveStreamDao().replaceAll(
            categories.mapIndexed { index, category ->
                LiveStream(
                    id = "ch$index",
                    categoryId = category.id,
                    num = firstNumber + index,
                    name = "Channel %02d".format(index),
                    tvArchive = false,
                )
            },
        )
        if (withFavorite) db.favoriteDao().toggle("ch0", StreamType.LIVE)
    }

    // ------------------------------------------------------------------ drives

    /** Seeds the catalog, starts the screen and waits until Live TV has loaded. */
    private fun startScreen(
        withFavorite: Boolean = true,
        prefs: LiveTvPrefs = listPrefs(),
        firstNumber: Int = 100,
    ) {
        seedCatalog(withFavorite, firstNumber)
        val vm = newViewModel(prefs)
        viewModel = vm
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == BrowseViewModel::class.java) {
                    "Factory only knows about BrowseViewModel; got $modelClass"
                }
                return vm as T
            }
        }
        // The screen builds its guide view model eagerly; without a factory it
        // reaches for Graph, which only exists in the running app (#119).
        val context = ApplicationProvider.getApplicationContext<Application>()
        val gvm = testGuideViewModel(context, db)
        guideViewModel = gvm
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LiveTvScreen(
                        viewModelFactory = factory,
                        guideViewModelFactory = factoryOf(gvm),
                        onBack = { backPresses++ },
                        onPlayChannel = {},
                    )
                }
            }
        }
        compose.waitUntil(WAIT_MILLIS) { !vm.ui.value.isLoading }
        compose.waitForIdle()
    }

    private val vm: BrowseViewModel get() = checkNotNull(viewModel)

    /** A rail row or channel row — merged into one clickable node by the shared surface. */
    private fun row(label: String): SemanticsMatcher =
        hasText(label, substring = true) and hasClickAction()

    private fun focused(matcher: SemanticsMatcher): Boolean = nodeExists(matcher.and(isFocused()))

    /**
     * Existence probe on the public assertion API — [SemanticsNodeInteraction]
     * here exposes no public node-fetch.
     */
    private fun nodeExists(matcher: SemanticsMatcher): Boolean = try {
        compose.onNode(matcher).assertExists()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun awaitFocus(matcher: SemanticsMatcher, what: String) {
        compose.waitUntil(WAIT_MILLIS) { focused(matcher) }
        assertTrue("$what should hold focus", focused(matcher))
    }

    private fun awaitGone(matcher: SemanticsMatcher) {
        compose.waitUntil(WAIT_MILLIS) { !nodeExists(matcher) }
    }

    /** Takes focus on [matcher] through its own RequestFocus action (needs no window focus). */
    private fun takeFocus(matcher: SemanticsMatcher) {
        compose.waitUntil(WAIT_MILLIS) { nodeExists(matcher) }
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus) { it?.invoke() }
        awaitFocus(matcher, "the requested node")
    }

    /** Key press from whatever currently holds focus. */
    private fun pressFocused(key: Key) {
        // Focus can be momentarily nowhere while a lazy rail row is recomposed or
        // scrolled into place under load; wait for a holder instead of racing it.
        compose.waitUntil(WAIT_MILLIS) { nodeExists(isFocused()) }
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    /** Fans the rail out from a focused channel row with Left, waiting for it. */
    private fun fanOutWithLeft() {
        takeFocus(row("Channel 00"))
        pressFocused(Key.DirectionLeft)
        awaitFocus(row("Favorites"), "the selected Favorites row")
    }

    /** From the rail on Favorites: Down to [label], which loads nothing. */
    private fun walkRailTo(label: String) {
        var presses = 0
        while (presses < 60 && !focused(row(label))) {
            pressFocused(Key.DirectionDown)
            presses++
        }
        assertTrue("focus should reach the rail row '$label'", focused(row(label)))
    }

    // ------------------------------------------------------------------ tests

    /**
     * The guard itself: a [FocusRequester] no row is attached to anymore reports
     * failure instead of throwing `IllegalStateException: FocusRequester is not
     * initialized` — the fatal that used to kill the app on BACK (F-40).
     */
    @Test
    fun `guard - unattached requester fails instead of throwing`() {
        val detached = FocusRequester() // never backed by a composed row
        assertFalse(
            "requestFocus() on a detached requester must report failure, not throw",
            detached.tryRequestFocus(),
        )
    }

    @Test
    fun `with a favorite Live TV opens with the rail folded away and focus on the first channel`() {
        startScreen()

        awaitFocus(row("Channel 00"), "the first favorite channel")
        assertFalse("the rail is folded away", nodeExists(row("Category 05")))
        assertTrue(vm.selected.value is LiveCategorySelection.Favorites)
    }

    @Test
    fun `with no favorites the rail opens focused, with the hint and no channel rows`() {
        startScreen(withFavorite = false)

        awaitFocus(row("Favorites"), "the rail's first row")
        compose.onNode(hasText("Pick a category to see its channels")).assertExists()
        assertFalse("no channel rows are drawn", nodeExists(row("Channel 00")))
        assertTrue(vm.ui.value.channels.isEmpty())
        // Landing on the first row must not select it.
        assertTrue(vm.selected.value is LiveCategorySelection.NothingPicked)
    }

    @Test
    fun `Choose a category opens the rail even with favorites`() {
        startScreen(prefs = listPrefs(openOn = com.rysal.happytv.data.LiveTvOpenOn.CHOOSE))

        awaitFocus(row("Favorites"), "the rail's first row")
        compose.onNode(hasText("Pick a category to see its channels")).assertExists()
        assertTrue(vm.selected.value is LiveCategorySelection.NothingPicked)
    }

    @Test
    fun `Left from a channel row fans the rail out with focus on the selected category`() {
        startScreen()

        fanOutWithLeft()

        assertEquals("Left fans the rail out, it does not leave", 0, backPresses)
        compose.onNode(row("Category 05")).assertExists()
    }

    @Test
    fun `moving focus through the rail loads nothing`() {
        startScreen()
        fanOutWithLeft()
        val before = vm.ui.value.channels.map { it.stream.id }

        walkRailTo("Category 03")

        assertTrue(
            "focus alone must not select: still Favorites",
            vm.selected.value is LiveCategorySelection.Favorites,
        )
        assertEquals(before, vm.ui.value.channels.map { it.stream.id })
        // The info band names the focused category while the rail has focus.
        compose.onNode(hasText("Category 03", substring = false) and !hasClickAction()).assertExists()
    }

    @Test
    fun `OK on a category selects it, folds the rail away and focuses the first channel`() {
        startScreen()
        fanOutWithLeft()
        walkRailTo("Category 02")

        pressFocused(Key.DirectionCenter)

        awaitFocus(row("Channel 02"), "the first channel of the category")
        assertEquals(
            LiveCategorySelection.Category("cat2", "Category 02"),
            vm.selected.value,
        )
        awaitGone(row("Category 05"))
    }

    @Test
    fun `Right on a category selects it exactly like OK`() {
        startScreen()
        fanOutWithLeft()
        walkRailTo("Category 04")

        pressFocused(Key.DirectionRight)

        awaitFocus(row("Channel 04"), "the first channel of the category")
        assertEquals(
            LiveCategorySelection.Category("cat4", "Category 04"),
            vm.selected.value,
        )
        awaitGone(row("Category 05"))
    }

    @Test
    fun `Left inside the rail goes nowhere`() {
        startScreen()
        fanOutWithLeft()

        pressFocused(Key.DirectionLeft)

        assertTrue("focus stays on the rail row", focused(row("Favorites")))
        assertEquals(0, backPresses)
    }

    @Test
    fun `Back from a channel fans the rail out on the selected category, and Back from the rail leaves`() {
        startScreen()
        fanOutWithLeft()
        walkRailTo("Category 02")
        pressFocused(Key.DirectionCenter)
        awaitFocus(row("Channel 02"), "the first channel")

        pressBack()

        awaitFocus(row("Category 02"), "the selected category")
        assertEquals("Back from the channels must move focus, not leave", 0, backPresses)

        pressBack()

        assertEquals("Back from the rail leaves Live TV", 1, backPresses)
    }

    @Test
    fun `Back with the rail scrolled away from the selected row does not crash`() {
        // F-40's shape: the rail is a LazyColumn that drops rows it scrolled
        // past. Fanning out scrolls the selected row into view first, so Back
        // always finds it.
        startScreen()
        fanOutWithLeft()
        walkRailTo("Category 30")
        pressFocused(Key.DirectionCenter)
        awaitFocus(row("Channel 30"), "the first channel")

        pressBack()

        awaitFocus(row("Category 30"), "the selected category, scrolled back into view")
        assertEquals(0, backPresses)
    }

    @Test
    fun `one Back from the first channel fans the rail out and a node holds focus`() {
        // QA on device: the first Back left no node focused, the rail folded,
        // and only a second Back fanned out. One Back must do it, from the entry
        // list too, and the D-pad must always have somewhere to be afterwards.
        startScreen()
        awaitFocus(row("Channel 00"), "the first favorite channel")

        pressBack()

        awaitFocus(row("Favorites"), "the selected category after a single Back")
        assertTrue("a node holds focus", nodeExists(isFocused()))
        compose.onNode(row("Category 05")).assertExists()
        assertEquals("one Back must not leave Live TV", 0, backPresses)
        // And the D-pad works straight away.
        pressFocused(Key.DirectionDown)
        assertTrue(nodeExists(isFocused()))
    }

    @Test
    fun `one Back from a channel with Keep categories open focuses the selected category`() {
        startScreen(prefs = listPrefs(keepCategoriesOpen = true))
        awaitFocus(row("Channel 00"), "the first favorite channel")

        pressBack()

        awaitFocus(row("Favorites"), "the selected category")
        assertEquals(0, backPresses)
    }

    @Test
    fun `Keep categories open - the rail stays and selecting moves focus to the list`() {
        startScreen(prefs = listPrefs(keepCategoriesOpen = true))

        awaitFocus(row("Channel 00"), "the first favorite channel")
        compose.onNode(row("Category 05")).assertExists()

        pressFocused(Key.DirectionLeft)
        awaitFocus(row("Favorites"), "the selected row of the permanent rail")

        walkRailTo("Category 02")
        pressFocused(Key.DirectionRight)

        awaitFocus(row("Channel 02"), "the first channel of the category")
        compose.onNode(row("Category 05")).assertExists()
        assertEquals(0, backPresses)
    }

    @Test
    fun `long-press on a category offers Hide category and hides it`() {
        startScreen()
        fanOutWithLeft()
        walkRailTo("Category 02")

        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter, LONG_PRESS_MILLIS) }
        compose.waitForIdle()

        compose.waitUntil(WAIT_MILLIS) { nodeExists(hasText("Hide category")) }
        compose.onNode(hasText("Hide category") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        compose.waitUntil(WAIT_MILLIS) {
            vm.ui.value.categories.none { it is LiveCategoryItem.ProviderCategory && it.id == "cat2" }
        }
        assertFalse(nodeExists(row("Category 02")))
        // #114: focus lands on the category that took its place, not back on the first row.
        awaitFocus(row("Category 03"), "the next category")
    }

    /** Long-press the focused rail row and choose Hide category, then wait for the row to leave. */
    private fun hideFocusedCategory(id: String) {
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter, LONG_PRESS_MILLIS) }
        compose.waitForIdle()
        compose.waitUntil(WAIT_MILLIS) { nodeExists(hasText("Hide category")) }
        compose.onNode(hasText("Hide category") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        compose.waitUntil(WAIT_MILLIS) {
            vm.ui.value.categories.none { it is LiveCategoryItem.ProviderCategory && it.id == id }
        }
    }

    /** #114: deep in a scrolled rail the hide keeps the scroll position and lands on the neighbour. */
    @Test
    fun `hiding a category deep in the rail keeps focus on its neighbour and the scroll position`() {
        startScreen()
        fanOutWithLeft()
        walkRailTo("Category 30")

        hideFocusedCategory("cat30")

        awaitFocus(row("Category 31"), "the next category")
        assertFalse(nodeExists(row("Category 30")))
        // No jump to the top: the rail is still scrolled far past its first rows.
        assertFalse("the rail must not have scrolled back to the top", nodeExists(row("Favorites")))
        // And it keeps working: Down moves on to the following row.
        pressFocused(Key.DirectionDown)
        awaitFocus(row("Category 32"), "the row after the neighbour")
    }

    /** #114: hiding the last category lands on the row now above the end of the list, never the top. */
    @Test
    fun `hiding the last category moves focus to the row that takes its place`() {
        startScreen()
        fanOutWithLeft()
        walkRailTo("Category 39")

        hideFocusedCategory("cat39")

        awaitFocus(row("All channels"), "the row after the last category")
        assertFalse(nodeExists(row("Favorites")))
    }

    @Test
    fun `a five digit channel number stays on one line`() {
        startScreen(firstNumber = 12_300)

        awaitFocus(row("Channel 00"), "the first favorite channel")
        val number = compose.onNode(hasText("12300"), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        number.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertEquals("the number column must fit five digits on one line", 1, results.single().lineCount)
    }

    /** Opens the header menu from the "More options" button and walks Down to its second item. */
    private fun openMoreMenuAndMoveDown() {
        val more = androidx.compose.ui.test.hasContentDescription("More options")
        takeFocus(more)
        pressFocused(Key.DirectionCenter)
        compose.waitUntil(WAIT_MILLIS) { nodeExists(hasText("Hide empty categories")) }
        takeFocus(hasText("Show hidden channels") and hasClickAction())
        pressFocused(Key.DirectionDown)
        awaitFocus(hasText("Hide empty categories") and hasClickAction(), "the menu's second item")
    }

    /** #59, #168: with the rail fanned out, Back from the header menu returns focus to the button that opened it. */
    @Test
    fun `Back from the header menu with the rail open hands focus back to More options`() {
        startScreen()
        fanOutWithLeft()

        openMoreMenuAndMoveDown()
        pressBack()

        awaitGone(hasText("Hide empty categories"))
        awaitFocus(androidx.compose.ui.test.hasContentDescription("More options"), "the More options button")
        assertEquals("Back closes the menu, it does not leave Live TV", 0, backPresses)
        compose.onNode(row("Category 05")).assertExists()
    }

    /** #59: with the rail closed Back from the menu keeps today's behaviour (focus is not lost). */
    @Test
    fun `Back from the header menu with the rail closed keeps focus on screen`() {
        startScreen()
        awaitFocus(row("Channel 00"), "the first favorite channel")

        openMoreMenuAndMoveDown()
        pressBack()

        awaitGone(hasText("Hide empty categories"))
        compose.waitUntil(WAIT_MILLIS) { nodeExists(isFocused()) }
        assertFalse("the rail stays folded away", nodeExists(row("Category 05")))
        assertEquals(0, backPresses)
    }

    private companion object {
        /** Wide enough that the rail scrolls long before its bottom is reached. */
        const val RAIL_TEST_CATEGORY_COUNT = 40
        const val WAIT_MILLIS = 10_000L
        const val LONG_PRESS_MILLIS = 800L
    }
}
