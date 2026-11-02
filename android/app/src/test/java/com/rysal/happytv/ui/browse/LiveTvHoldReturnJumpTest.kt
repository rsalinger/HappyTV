package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.activity.ComponentActivity
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
import com.rysal.happytv.data.LiveTvOpenOn
import com.rysal.happytv.data.LiveTvView
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.guide.GuideViewModel
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
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
 * #138: Back out of full screen after a 2 s hold-return lands real focus on the
 * playing channel's row, from every origin of the hold. The rail origin was the
 * failure: a list under an open rail takes no focus, so focus stayed on the rail
 * (or nowhere). Also the channel that sits beyond the first 240-row window.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class LiveTvHoldReturnJumpTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private var viewModel: BrowseViewModel? = null
    private var guideViewModel: GuideViewModel? = null
    private lateinit var playback: LivePlayback
    private var playingId: String? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        RoomTestTeardown.shutDown(
            listOf(viewModel?.viewModelScope?.coroutineContext?.job, guideViewModel?.viewModelScope?.coroutineContext?.job),
            db,
        )
    }

    /** "Small" (cat0): 5 channels. "Deep" (cat1): 300, past the first 240-row window. */
    private fun seed() = runBlocking {
        db.categoryDao().replaceAll(
            StreamType.LIVE,
            listOf(
                Category(id = "cat0", type = StreamType.LIVE, name = "Small"),
                Category(id = "cat1", type = StreamType.LIVE, name = "Deep"),
            ),
        )
        val small = (0 until 5).map { LiveStream(id = "s$it", categoryId = "cat0", num = 10 + it, name = "Small %03d".format(it)) }
        val deep = (0 until 300).map { LiveStream(id = "d$it", categoryId = "cat1", num = 1000 + it, name = "Deep %03d".format(it)) }
        db.liveStreamDao().replaceAll(small + deep)
        db.favoriteDao().toggle("s0", StreamType.LIVE)
    }

    private fun start(keepOpen: Boolean = false) {
        seed()
        val vm = BrowseViewModel(
            categoryDao = db.categoryDao(),
            liveStreamDao = db.liveStreamDao(),
            favoriteDao = db.favoriteDao(),
            recentChannelDao = db.recentChannelDao(),
            hiddenChannelDao = db.hiddenChannelDao(),
            epgProgramDao = db.epgProgramDao(),
            xmltvMode = flowOf(false),
            clock = { 0L },
            liveTvPrefs = flowOf(
                LiveTvPrefs(openOn = LiveTvOpenOn.FAVORITES, keepCategoriesOpen = keepOpen, view = LiveTvView.LIST),
            ),
        )
        viewModel = vm
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        val gvm = testGuideViewModel(ApplicationProvider.getApplicationContext<Application>(), db)
        guideViewModel = gvm
        playback = LivePlayback(
            playingStreamIdProvider = { playingId },
            onPlayChannel = {},
            locate = { id ->
                val dao = db.liveStreamDao()
                dao.getById(id)?.let { ChannelLocation(it.categoryId, dao.rowsBefore(it.categoryId, it.num, it.name)) }
            },
        )
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LiveTvScreen(
                        viewModelFactory = factory,
                        guideViewModelFactory = factoryOf(gvm),
                        onBack = {},
                        onPlayChannel = {},
                        playback = playback,
                    )
                }
            }
        }
        compose.waitUntil(WAIT_MILLIS) { !vm.ui.value.isLoading }
        compose.waitForIdle()
    }

    private fun row(label: String): SemanticsMatcher = hasText(label, substring = true) and hasClickAction()

    private fun exists(m: SemanticsMatcher): Boolean = try {
        compose.onNode(m).assertExists(); true
    } catch (_: AssertionError) {
        false
    }

    private fun awaitFocus(m: SemanticsMatcher, what: String) {
        compose.waitUntil(WAIT_MILLIS) { exists(m.and(isFocused())) }
        assertTrue("$what should hold focus", exists(m.and(isFocused())))
    }

    private fun focus(m: SemanticsMatcher) {
        compose.waitUntil(WAIT_MILLIS) { exists(m) }
        compose.onNode(m).performSemanticsAction(SemanticsActions.RequestFocus) { it?.invoke() }
        awaitFocus(m, "the requested node")
    }

    private fun fanOutFromRow(label: String) {
        focus(row(label))
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        awaitFocus(row("Favorites").or(row("Small")).or(row("Deep")), "a rail row")
    }

    /** What the host does when Back leaves full screen after a hold-return. */
    private fun backOutAfterHold(streamId: String) {
        playingId = streamId
        compose.runOnIdle {
            playback.jumpToPlayingOnBack = true
            playback.requestRestore(streamId)
        }
        compose.waitForIdle()
    }

    @Test
    fun `hold started on the rail, playing channel in the open category, folds the rail and focuses its row`() {
        start()
        // Open Small via the view model, as a rail OK would, then fan the rail out again.
        compose.runOnIdle { viewModel!!.selectCategory(LiveCategorySelection.Category("cat0", "Small")) }
        compose.waitUntil(WAIT_MILLIS) { exists(row("Small 003")) }
        fanOutFromRow("Small 000")
        assertTrue("the rail is out", exists(row("Deep")))

        backOutAfterHold("s3")

        awaitFocus(row("Small 003"), "the playing channel's row")
        compose.waitUntil(WAIT_MILLIS) { !exists(row("Deep")) }
        assertFalse("the rail is folded", exists(row("Deep")))
    }

    @Test
    fun `hold started on the rail, playing channel beyond the first page of another category, lands on its row`() {
        start()
        fanOutFromRow("Small 000")
        assertTrue("the rail is out", exists(row("Deep")))

        // Row 262 of Deep: past the first 240-row window.
        backOutAfterHold("d262")

        awaitFocus(row("Deep 262"), "the playing channel's row, beyond the first page")
        assertEquals("Deep", (viewModel!!.selected.value as LiveCategorySelection.Category).label)
        assertTrue("the window grew to reach it", viewModel!!.ui.value.channels.size > 262)
    }

    @Test
    fun `hold started on a list row in another category jumps to the playing channel`() {
        start()
        focus(row("Small 000"))

        backOutAfterHold("d5")

        awaitFocus(row("Deep 005"), "the playing channel's row")
    }

    @Test
    fun `a plain back out does not leave the open category`() {
        start()
        focus(row("Small 000"))
        playingId = "d5"
        compose.runOnIdle { playback.requestRestore("d5") }
        compose.waitForIdle()

        assertTrue(viewModel!!.selected.value is LiveCategorySelection.Favorites)
    }

    @Test
    fun `rows before counts the channels that sort ahead of one in its category`() = runBlocking {
        seed()
        val dao = db.liveStreamDao()
        assertEquals(0, dao.rowsBefore("cat1", 1000, "Deep 000"))
        assertEquals(262, dao.rowsBefore("cat1", 1262, "Deep 262"))
        // A missing num sorts first; ties on num fall back to the name.
        assertEquals(0, dao.rowsBefore("cat0", null, "Anything"))
        assertEquals(1, dao.rowsBefore("cat0", 10, "Small 001"))
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
