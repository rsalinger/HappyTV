package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.RoomTestTeardown
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.LiveTvView
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.guide.GuideRepository
import com.rysal.happytv.data.guide.HttpXmlTvFetcher
import com.rysal.happytv.ui.guide.GuideViewModel
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Live TV's Guide view (spec #7 section 3.4 / 3.6, issue #15), driven through the
 * real [LiveTvScreen] with a real [BrowseViewModel] and [GuideViewModel] on
 * in-memory Room.
 *
 * Pinned here:
 *  - the guide is the default view, and the toggle and the start-view override pick the other;
 *  - the issue's two pre-existing bugs: **Left from the channel cell fans the rail
 *    out** and **the first OK on a program opens the dialog**;
 *  - Right / Down rules on the real focus tree, ended programs focusable only with replay;
 *  - the info band shows the focused program and its description (single-row read);
 *  - the dialog's Watch now / Watch from the start reach the player callbacks, Back
 *    closes it and focus returns to the program;
 *  - no-data rows stay at row height.
 *
 * Focus is taken through the semantics RequestFocus action and keys go through the
 * compose root, so nothing depends on Robolectric granting window focus. Nothing
 * sleeps: every wait is a node, a focus, or a state.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class LiveTvGuideViewTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private var browse: BrowseViewModel? = null
    private var guide: GuideViewModel? = null

    private val now = System.currentTimeMillis()
    private val minute = 60_000L

    private val playedChannels = mutableListOf<String>()
    private val replays = mutableListOf<Triple<String, Long, Int>>()
    private val savedViews = mutableListOf<LiveTvView>()
    private var guideSettingsOpened = 0
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
        RoomTestTeardown.shutDown(
            listOf(browse?.viewModelScope?.coroutineContext?.job, guide?.viewModelScope?.coroutineContext?.job),
            db,
        )
    }

    /**
     * Two news channels (both favorites, so Live TV opens on them) and one sports
     * channel with no guide at all. 100 has replay and an ended / live / future
     * programme; 101 only a live one.
     */
    private fun seed(firstChannelReplay: Boolean = true, withPrograms: Boolean = true, thirdFavorite: Boolean = false) = runBlocking {
        db.categoryDao().replaceAll(
            StreamType.LIVE,
            listOf(
                Category(id = "news", type = StreamType.LIVE, name = "News"),
                Category(id = "sports", type = StreamType.LIVE, name = "Sports"),
            ),
        )
        db.liveStreamDao().replaceAll(
            listOf(
                LiveStream(id = "100", categoryId = "news", num = 100, name = "Northbay News", tvArchive = firstChannelReplay),
                LiveStream(id = "101", categoryId = "news", num = 101, name = "Capital Desk", tvArchive = false),
                LiveStream(id = "300", categoryId = "sports", num = 300, name = "Fieldhouse Sports", tvArchive = false),
            ),
        )
        db.favoriteDao().toggle("100", StreamType.LIVE)
        db.favoriteDao().toggle("101", StreamType.LIVE)
        if (thirdFavorite) {
            db.liveStreamDao().replaceAll(
                db.liveStreamDao().getAll() + LiveStream(id = "102", categoryId = "news", num = 102, name = "Harbor Live", tvArchive = false),
            )
            db.favoriteDao().toggle("102", StreamType.LIVE)
            db.epgProgramDao().replaceAll(
                listOf(EpgProgram(channelId = "102", title = "Harbor evening", startUtcMillis = now - 10 * minute, endUtcMillis = now + 20 * minute)),
            )
        }
        if (!withPrograms) return@runBlocking
        db.epgProgramDao().replaceAll(
            listOf(
                EpgProgram(channelId = "100", title = "Early report", startUtcMillis = now - 40 * minute, endUtcMillis = now - 10 * minute),
                EpgProgram(
                    channelId = "100",
                    title = "Headline report",
                    description = "The top stories of the hour.",
                    startUtcMillis = now - 10 * minute,
                    endUtcMillis = now + 20 * minute,
                ),
                EpgProgram(channelId = "100", title = "Weather watch", startUtcMillis = now + 20 * minute, endUtcMillis = now + 50 * minute),
                EpgProgram(channelId = "101", title = "Capital tonight", startUtcMillis = now - 10 * minute, endUtcMillis = now + 20 * minute),
            ),
        )
    }

    private fun startScreen(
        view: LiveTvView = LiveTvView.GUIDE,
        startView: LiveTvView? = null,
        openOnChoose: Boolean = false,
        firstChannelReplay: Boolean = true,
        withPrograms: Boolean = true,
        thirdFavorite: Boolean = false,
    ) {
        seed(firstChannelReplay, withPrograms, thirdFavorite)
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Built when the screen asks for it, like the app: a view model whose first state is
        // already out before the screen applies the start view would not be the real sequence.
        val vm by lazy { BrowseViewModel(
            categoryDao = db.categoryDao(),
            liveStreamDao = db.liveStreamDao(),
            favoriteDao = db.favoriteDao(),
            recentChannelDao = db.recentChannelDao(),
            hiddenChannelDao = db.hiddenChannelDao(),
            epgProgramDao = db.epgProgramDao(),
            xmltvMode = flowOf(false),
            clock = { now },
            liveTvPrefs = flowOf(
                LiveTvPrefs(
                    openOn = if (openOnChoose) com.rysal.happytv.data.LiveTvOpenOn.CHOOSE else com.rysal.happytv.data.LiveTvOpenOn.FAVORITES,
                    view = view,
                ),
            ),
            saveView = { savedViews += it },
        ).also { browse = it } }
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "guide-view-${dataStoreFiles++}.preferences_pb")
        }
        val gvm = GuideViewModel(
            GuideRepository(
                epgDao = db.epgProgramDao(),
                liveDao = db.liveStreamDao(),
                categoryDao = db.categoryDao(),
                favoriteDao = db.favoriteDao(),
                recentDao = db.recentChannelDao(),
                settings = SettingsRepository(dataStore),
                session = { null },
                xmlTvFetcher = HttpXmlTvFetcher(context),
            ),
            ZoneId.systemDefault(),
        ) { now }
        guide = gvm
        val browseFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        val guideFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = gvm as T
        }
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LiveTvScreen(
                        viewModelFactory = browseFactory,
                        guideViewModelFactory = guideFactory,
                        startView = startView,
                        onBack = { backPresses++ },
                        onPlayChannel = { playedChannels += it.id },
                        onPlayReplay = { id, start, minutes -> replays += Triple(id, start, minutes) },
                        onOpenGuideSettings = { guideSettingsOpened++ },
                    )
                }
            }
        }
        waitFor { !vm.ui.value.isLoading }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------- helpers

    private fun clickable(label: String): SemanticsMatcher = hasText(label, substring = true) and hasClickAction()

    private val headline get() = clickable("Headline report")
    private val capital get() = clickable("Capital tonight")
    private val earlyReport get() = clickable("Early report")
    private val northbayCell get() = clickable("Northbay News")
    private val capitalCell get() = clickable("Capital Desk")

    /** True when at least one node matches (the same text can be in the info band and a cell). */
    private fun nodeExists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** [androidx.compose.ui.test.junit4.ComposeTestRule.waitUntil] that prints the tree when it times out. */
    private fun waitFor(what: String = "condition", condition: () -> Boolean) {
        try {
            compose.waitUntil(WAIT_MILLIS, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            println("TIMEOUT waiting for $what; tree:\n" + compose.onRoot().printToString(maxDepth = 40).take(9000))
            throw e
        }
    }

    private fun focused(matcher: SemanticsMatcher): Boolean = nodeExists(matcher.and(isFocused()))

    private fun awaitNode(matcher: SemanticsMatcher, what: String) {
        waitFor { nodeExists(matcher) }
        assertTrue("$what should be on screen", nodeExists(matcher))
    }

    private fun awaitFocus(matcher: SemanticsMatcher, what: String) {
        waitFor { focused(matcher) }
        assertTrue("$what should hold focus", focused(matcher))
    }

    private fun takeFocus(matcher: SemanticsMatcher) {
        awaitNode(matcher, "the node to focus")
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus) { it?.invoke() }
        awaitFocus(matcher, "the requested node")
    }

    private fun pressFocused(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `the guide is the default view - cells, program titles and a ruler`() {
        startScreen()

        awaitNode(headline, "the on-now program")
        awaitNode(northbayCell, "the first channel cell")
        awaitNode(clickable("Weather watch"), "the next program")
        // A channel with no guide would read "No guide info"; both of these have guide.
        // The corner cell names the category and its size.
        awaitNode(hasText("Favorites", substring = true), "the corner cell")
        // The list view's "until" lines are not drawn in the guide.
        assertFalse(nodeExists(hasText("until", substring = true) and hasClickAction()))
    }

    @Test
    fun `entry focus lands on the first row's program on now`() {
        startScreen()

        awaitFocus(headline, "the program on now of the first row")
    }

    @Test
    fun `the first OK on a program opens the details dialog`() {
        startScreen()
        takeFocus(headline)

        // One press, not two (issue #15, comment 2).
        pressFocused(Key.DirectionCenter)

        awaitNode(hasText("Watch now") and hasClickAction(), "the dialog's primary button")
        awaitNode(hasText("Watch from the start", substring = true), "the replay button (replay channel)")
        // Title, channel and the progress line are in the dialog.
        awaitNode(hasText("100 · Northbay News"), "the dialog's channel line")
    }

    @Test
    fun `the dialog fills in the description with one single-row read`() {
        startScreen()
        takeFocus(headline)
        pressFocused(Key.DirectionCenter)

        // The info band behind it also shows it; either way it arrives.
        waitFor { nodeExists(hasText("The top stories of the hour.")) }
    }

    @Test
    fun `Back closes the dialog and focus returns to the program that opened it`() {
        startScreen()
        takeFocus(headline)
        pressFocused(Key.DirectionCenter)
        awaitNode(hasText("Watch now") and hasClickAction(), "the dialog")

        pressFocused(Key.Back)

        waitFor { !nodeExists(hasText("Watch now")) }
        awaitFocus(headline, "the program that opened the dialog")
        assertEquals("Back closed the dialog, not Live TV", 0, backPresses)
    }

    @Test
    fun `Watch now plays the channel and Watch from the start replays the program`() {
        startScreen()
        takeFocus(headline)
        pressFocused(Key.DirectionCenter)
        awaitNode(hasText("Watch now") and hasClickAction(), "the dialog")

        pressFocused(Key.DirectionCenter) // Watch now is focused first

        waitFor { playedChannels.isNotEmpty() }
        assertEquals(listOf("100"), playedChannels)

        // Again, this time the replay button.
        takeFocus(headline)
        pressFocused(Key.DirectionCenter)
        awaitNode(hasText("Watch from the start", substring = true) and hasClickAction(), "the replay button")
        takeFocus(hasText("Watch from the start", substring = true) and hasClickAction())
        pressFocused(Key.DirectionCenter)

        waitFor { replays.isNotEmpty() }
        val (id, start, minutes) = replays.single()
        assertEquals("100", id)
        assertEquals(now - 10 * minute, start)
        assertEquals(30, minutes)
    }

    @Test
    fun `Left from the channel cell fans the category rail out`() {
        startScreen()
        takeFocus(northbayCell)

        pressFocused(Key.DirectionLeft)

        // The rail fans out with focus on the selected category (issue #15, comment 1).
        awaitFocus(clickable("Favorites"), "the selected Favorites row")
        assertTrue("the rail lists the other categories", nodeExists(clickable("Sports")))
    }

    @Test
    fun `Left from a program stops at the channel cell first`() {
        startScreen()
        takeFocus(headline)

        pressFocused(Key.DirectionLeft)

        // Row 0's earlier program is ended and the channel has replay, so it is a stop.
        awaitFocus(earlyReport, "the ended program (replay channel)")
        pressFocused(Key.DirectionLeft)
        awaitFocus(northbayCell, "the channel cell")
    }

    @Test
    fun `Right from the channel cell enters the program on now`() {
        startScreen()
        takeFocus(northbayCell)

        pressFocused(Key.DirectionRight)

        awaitFocus(headline, "the program on now")
    }

    @Test
    fun `Down changes channel and keeps the time`() {
        startScreen()
        takeFocus(headline)

        pressFocused(Key.DirectionDown)

        awaitFocus(capital, "the same time slot on the next channel")
        pressFocused(Key.DirectionUp)
        awaitFocus(headline, "back on the first channel")
    }

    @Test
    fun `an ended program without replay is not a focus stop`() {
        startScreen(firstChannelReplay = false)

        awaitNode(headline, "the program on now")
        // The ended block is drawn but is not clickable (no focus stop).
        assertFalse("ended x no replay is not a focus stop", nodeExists(earlyReport))
        assertTrue(nodeExists(hasText("Early report", substring = true)))
    }

    @Test
    fun `the info band shows the focused program with its description`() {
        startScreen()
        takeFocus(headline)

        // Band: channel line, title, time and status. The description arrives from the
        // single-row read after the debounce.
        awaitNode(hasText("100 · Northbay News"), "the channel line")
        awaitNode(hasText("On now", substring = true) and !hasClickAction(), "the status line")
        waitFor { nodeExists(hasText("The top stories of the hour.")) }
    }

    @Test
    fun `a channel with no guide draws a No guide info row at row height`() {
        startScreen(openOnChoose = false)
        // Pick Sports (no guide for its one channel) through the rail.
        takeFocus(northbayCell)
        pressFocused(Key.DirectionLeft)
        awaitFocus(clickable("Favorites"), "the rail")
        var presses = 0
        while (presses < 20 && !focused(clickable("Sports"))) {
            pressFocused(Key.DirectionDown)
            presses++
        }
        awaitFocus(clickable("Sports"), "the Sports row")
        pressFocused(Key.DirectionCenter)

        awaitNode(clickable("Fieldhouse Sports"), "the Sports channel's cell")
        awaitNode(hasText("No guide info", substring = true), "the striped no-guide span")
    }

    @Test
    fun `the Guide and List toggle switches the body and remembers the choice`() {
        startScreen()
        awaitNode(headline, "the guide")

        compose.onNode(clickable("List")).performClick()

        // List view: the now line is "Headline report · until ..." inside the row.
        waitFor { nodeExists(hasText("until", substring = true) and hasClickAction()) }
        assertEquals(listOf(LiveTvView.LIST), savedViews)
        assertEquals(LiveTvView.LIST, browse!!.ui.value.view)

        compose.onNode(clickable("Guide")).performClick()
        waitFor { nodeExists(headline) && !nodeExists(hasText("until", substring = true) and hasClickAction()) }
        assertEquals(listOf(LiveTvView.LIST, LiveTvView.GUIDE), savedViews)
    }

    @Test
    fun `the day picker is only in the guide`() {
        startScreen()
        awaitNode(clickable("Tomorrow"), "the day picker")
        compose.onNode(clickable("List")).performClick()
        waitFor { !nodeExists(clickable("Tomorrow")) }
    }

    @Test
    fun `the stored list view opens the list, and the TV guide tile's start view overrides it`() {
        startScreen(view = LiveTvView.LIST)
        waitFor { nodeExists(hasText("until", substring = true) and hasClickAction()) }
        assertFalse("the list view has no day picker", nodeExists(clickable("Tomorrow")))
    }

    @Test
    fun `the TV guide tile's start view opens the guide even when the list was last used`() {
        startScreen(view = LiveTvView.LIST, startView = LiveTvView.GUIDE)

        awaitNode(headline, "the guide")
        assertEquals(LiveTvView.GUIDE, browse!!.ui.value.view)
        assertTrue("a start view is not a remembered choice", savedViews.isEmpty())
    }

    @Test
    fun `long-press on a channel cell opens the channel menu with favorites and hide`() {
        startScreen()
        takeFocus(capitalCell)

        compose.onNode(isFocused()).performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()

        awaitNode(hasText("Remove from favorites") and hasClickAction(), "the favorites item")
        awaitNode(hasText("Hide channel") and hasClickAction(), "the hide item")
    }

    @Test
    fun `a hidden channel's menu says Unhide channel and OK unhides it`() {
        startScreen()
        awaitNode(capitalCell, "the channel cell")
        runBlocking { db.hiddenChannelDao().insert(HiddenChannel("101")) }
        // Hidden channels leave the list; "Show hidden channels" brings the row back.
        browse!!.setShowHiddenChannels(true)
        takeFocus(capitalCell)

        compose.onNode(isFocused()).performKeyInput { pressKey(Key.Menu) }
        awaitNode(hasText("Unhide channel") and hasClickAction(), "the unhide item")
        assertFalse(nodeExists(hasText("Hide channel")))

        takeFocus(hasText("Unhide channel") and hasClickAction())
        pressFocused(Key.DirectionCenter)

        waitFor("the channel to leave the hidden list") {
            !runBlocking { db.hiddenChannelDao().isHidden("101", StreamType.LIVE) }
        }
        waitFor("the menu to close") { !nodeExists(hasText("Unhide channel")) }
    }

    @Test
    fun `a visible channel's menu still says Hide channel`() {
        startScreen()
        takeFocus(capitalCell)
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.Menu) }
        awaitNode(hasText("Hide channel") and hasClickAction(), "the hide item")
        assertFalse(nodeExists(hasText("Unhide channel")))
        assertEquals("Hide channel", hideChannelLabel(hidden = false))
        assertEquals("Unhide channel", hideChannelLabel(hidden = true))
    }

    @Test
    fun `Back from the channel menu hands focus back to the channel cell`() {
        startScreen()
        takeFocus(capitalCell)
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.Menu) }
        awaitNode(hasText("Hide channel") and hasClickAction(), "the menu")

        pressFocused(Key.Back)

        waitFor("the menu to close") { !nodeExists(hasText("Hide channel")) }
        awaitFocus(capitalCell, "the cell that opened the menu")
        assertEquals(0, backPresses)
    }

    @Test
    fun `Down from every header control lands on the grid, never on nothing`() {
        startScreen()
        awaitFocus(headline, "entry focus")
        for (control in listOf(clickable("Tomorrow"), clickable("Today"), clickable("List"), clickable("Guide"), hasContentDescription("Back"), hasContentDescription("More options"))) {
            takeFocus(control)

            pressFocused(Key.DirectionDown)

            awaitFocus(headline, "the grid's program on now after Down from a header control")
        }
    }

    @Test
    fun `Down from the header returns to the program that was focused last`() {
        startScreen()
        takeFocus(headline)
        pressFocused(Key.DirectionDown)
        awaitFocus(capital, "the second row")
        takeFocus(clickable("Today"))

        pressFocused(Key.DirectionDown)

        awaitFocus(capital, "the last focused program")
    }

    @Test
    fun `Down from the header in the list view lands on the first channel row`() {
        startScreen(view = LiveTvView.LIST)
        awaitFocus(northbayCell, "the first row")
        takeFocus(clickable("Guide"))

        pressFocused(Key.DirectionDown)

        awaitFocus(northbayCell, "the first channel row")
    }

    /** #117: Left from the header's Back button keeps focus on Back, in the guide and in the list. */
    private fun leftFromBackKeepsFocus(view: LiveTvView, firstBody: SemanticsMatcher) {
        startScreen(view = view)
        awaitFocus(firstBody, "entry focus")
        val back = hasContentDescription("Back") and hasClickAction()
        takeFocus(clickable("Guide"))
        repeat(8) { pressFocused(Key.DirectionLeft) }
        awaitFocus(back, "Back after walking Left along the header")
        repeat(3) { n ->
            pressFocused(Key.DirectionLeft)
            assertTrue("a node holds focus after Left #${n + 1} from Back", nodeExists(isFocused()))
            assertTrue("Back keeps focus: nothing lies left of it", focused(back))
        }
        pressFocused(Key.DirectionDown)
        awaitFocus(firstBody, "the body after Down from Back")
    }

    @Test
    fun `Left from the header Back button keeps focus on Back in the guide`() =
        leftFromBackKeepsFocus(LiveTvView.GUIDE, headline)

    @Test
    fun `Left from the header Back button keeps focus on Back in the channel list`() =
        leftFromBackKeepsFocus(LiveTvView.LIST, northbayCell)

    @Test
    fun `choosing a category focuses its first channel, not the row index of the last focus`() {
        startScreen()
        // Move down to the second row, then pick another category through the rail.
        takeFocus(headline)
        pressFocused(Key.DirectionDown)
        awaitFocus(capital, "the second row")
        pressFocused(Key.DirectionLeft)
        awaitFocus(capitalCell, "the channel cell")
        pressFocused(Key.DirectionLeft)
        awaitFocus(clickable("Favorites"), "the rail")
        var presses = 0
        while (presses < 20 && !focused(clickable("News"))) {
            pressFocused(Key.DirectionDown)
            presses++
        }
        awaitFocus(clickable("News"), "the News row")

        pressFocused(Key.DirectionCenter)

        // News holds both channels in order: focus is on the first channel's program on now.
        awaitFocus(headline, "the first channel of the new category")
    }

    @Test
    fun `OK on a channel cell plays the channel`() {
        startScreen()
        takeFocus(capitalCell)

        pressFocused(Key.DirectionCenter)

        waitFor { playedChannels.isNotEmpty() }
        assertEquals(listOf("101"), playedChannels)
    }

    @Test
    fun `Back from the guide fans the rail out and Back again leaves Live TV`() {
        startScreen()
        takeFocus(headline)

        pressBack()
        awaitFocus(clickable("Favorites"), "the rail")
        assertEquals(0, backPresses)

        pressBack()
        assertEquals(1, backPresses)
    }

    @Test
    fun `an empty guide store says No guide yet and Open guide settings goes to Settings`() {
        startScreen(withPrograms = false)

        awaitNode(hasText("No guide yet"), "the empty state")
        awaitFocus(clickable("Open guide settings"), "the empty state's action")
        pressFocused(Key.DirectionCenter)

        assertEquals(1, guideSettingsOpened)
    }

    @Test
    fun `the list view's info band reads the focused channel's description with one row query`() {
        startScreen(view = LiveTvView.LIST)
        awaitFocus(northbayCell, "the first list row")

        // The batched now lookup carries no description (#32); the band asks for it.
        waitFor("the description") { nodeExists(hasText("The top stories of the hour.")) }
        waitFor("the exact time line") { nodeExists(hasText("On now", substring = true) and !hasClickAction()) }
    }

    @Test
    fun `focus is taken back when the focused program stops being a stop`() {
        startScreen()
        takeFocus(headline)
        pressFocused(Key.DirectionLeft)
        awaitFocus(earlyReport, "the ended program (replay channel)")

        // The channel loses replay: the ended block is no longer a focus stop, so the
        // focused node leaves the tree. The grid must not leave the D-pad on nothing.
        runBlocking {
            db.liveStreamDao().replaceAll(
                listOf(
                    LiveStream(id = "100", categoryId = "news", num = 100, name = "Northbay News", tvArchive = false),
                    LiveStream(id = "101", categoryId = "news", num = 101, name = "Capital Desk", tvArchive = false),
                    LiveStream(id = "300", categoryId = "sports", num = 300, name = "Fieldhouse Sports", tvArchive = false),
                ),
            )
        }

        awaitFocus(headline, "the same row's program on now")
    }

    @Test
    fun `details dialog open and close are timed once each`() {
        val lines = mutableListOf<String>()
        com.rysal.happytv.perf.HtvPerf.resetForTest()
        com.rysal.happytv.perf.HtvPerf.sink = { lines += it }
        try {
            startScreen()
            takeFocus(headline)
            pressFocused(Key.DirectionCenter)
            awaitNode(hasText("Watch now") and hasClickAction(), "the dialog")
            waitFor("the open line") { lines.any { it.startsWith("guide.details.open ") } }

            pressFocused(Key.Back)
            waitFor("the close line") { lines.any { it.startsWith("guide.details.close ") } }

            assertEquals(1, lines.count { it.startsWith("guide.details.open ") })
            assertEquals(1, lines.count { it.startsWith("guide.details.close ") })
        } finally {
            com.rysal.happytv.perf.HtvPerf.resetForTest()
        }
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }


    // ----------------------------------------------- v1 Live TV focus fixes

    /** #168: the More options menu opens on its first item and Back returns to the button. */
    @Test
    fun `More options - OK focuses the first menu item and Back returns to the button`() {
        startScreen()
        val more = hasContentDescription("More options") and hasClickAction()
        takeFocus(more)

        pressFocused(Key.DirectionCenter)

        awaitNode(hasText("Show hidden channels") and hasClickAction(), "the menu")
        awaitFocus(hasText("Show hidden channels") and hasClickAction(), "the menu's first item")

        // Moving into the menu first is what used to send focus to the rail on Back.
        pressFocused(Key.DirectionDown)
        awaitFocus(hasText("Hide empty categories") and hasClickAction(), "the second item")
        pressFocused(Key.Back)

        waitFor("the menu to close") { !nodeExists(hasText("Show hidden channels")) }
        awaitFocus(more, "the More options button that opened the menu")
        assertEquals(0, backPresses)
    }

    /** #168: an item that closes the menu also hands focus back to the button. */
    @Test
    fun `More options - choosing an item returns focus to the button`() {
        startScreen()
        val more = hasContentDescription("More options") and hasClickAction()
        takeFocus(more)
        pressFocused(Key.DirectionCenter)
        awaitFocus(hasText("Show hidden channels") and hasClickAction(), "the menu's first item")

        pressFocused(Key.DirectionCenter)

        waitFor("the menu to close") { !nodeExists(hasText("Hide empty categories")) }
        awaitFocus(more, "the More options button after choosing an item")
    }

    /** #155: after Hide channel the band and the rail return follow the row that took focus. */
    @Test
    fun `Hide channel in the guide - the info band and the rail return describe the focused row`() {
        startScreen(thirdFavorite = true)
        takeFocus(northbayCell)
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.Menu) }
        awaitNode(hasText("Hide channel") and hasClickAction(), "the hide item")
        takeFocus(hasText("Hide channel") and hasClickAction())

        pressFocused(Key.DirectionCenter)

        waitFor("the hidden channel to leave the guide") { !nodeExists(northbayCell) }
        awaitFocus(capitalCell, "the row that took the hidden one's place")
        // The band describes the focused row (101), not the one after it (102).
        awaitNode(hasText("101 · Capital Desk"), "the band's channel line")
        assertFalse("the band must not describe the row after the focused one", nodeExists(hasText("102 · Harbor Live")))

        // Left into the rail and Right returns to the focused row, not the band's old index.
        pressFocused(Key.DirectionLeft)
        awaitFocus(clickable("Favorites"), "the rail")
        pressFocused(Key.DirectionRight)
        awaitFocus(capitalCell.or(capital), "the row that was focused before the rail")
    }

    /** #171: Left from the empty Favorites action fans the rail out. */
    @Test
    fun `empty Favorites - Left from Browse all channels fans the rail out`() {
        startScreen()
        runBlocking {
            db.favoriteDao().toggle("100", StreamType.LIVE)
            db.favoriteDao().toggle("101", StreamType.LIVE)
        }
        val browseAll = hasText("Browse all channels") and hasClickAction()
        awaitNode(browseAll, "the empty Favorites action")
        takeFocus(browseAll)

        pressFocused(Key.DirectionLeft)

        awaitFocus(clickable("Favorites"), "the rail's Favorites row")
        assertTrue("a node holds focus", nodeExists(isFocused()))
    }

    /** #171: removing the last favorite leaves the D-pad on the empty state's action. */
    @Test
    fun `removing the last favorite leaves focus on the empty state's action`() {
        startScreen(view = LiveTvView.LIST)
        runBlocking { db.favoriteDao().toggle("101", StreamType.LIVE) }
        waitFor("one favorite left") { !nodeExists(capitalCell) }
        takeFocus(northbayCell)
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.Menu) }
        awaitNode(hasText("Remove from favorites") and hasClickAction(), "the favorites item")
        takeFocus(hasText("Remove from favorites") and hasClickAction())

        pressFocused(Key.DirectionCenter)

        awaitNode(hasText("No favorites yet"), "the empty state")
        awaitFocus(hasText("Browse all channels") and hasClickAction(), "the empty state's action")
    }

    @Test
    fun `removesLastFavorite only fires for the only row of the Favorites pane`() {
        assertTrue(removesLastFavorite(LiveCategorySelection.Favorites, rowCount = 1, isFavorite = true))
        assertFalse(removesLastFavorite(LiveCategorySelection.Favorites, rowCount = 2, isFavorite = true))
        assertFalse(removesLastFavorite(LiveCategorySelection.Favorites, rowCount = 1, isFavorite = false))
        assertFalse(removesLastFavorite(LiveCategorySelection.AllChannels, rowCount = 1, isFavorite = true))
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        var dataStoreFiles = 0
    }
}
