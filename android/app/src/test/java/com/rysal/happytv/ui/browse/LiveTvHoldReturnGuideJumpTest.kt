package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
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
import com.rysal.happytv.data.entity.EpgProgram
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #138 QA r2: in the guide, a hold-return started on the rail with another category selected,
 * the playing channel deep in its own category (past the first 240-row page and the guide's
 * first 120 rows), must land focus on that channel's cell, not on row 0.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class LiveTvHoldReturnGuideJumpTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private var viewModel: BrowseViewModel? = null
    private var guideViewModel: GuideViewModel? = null
    private lateinit var playback: LivePlayback
    private var playingId: String? = null

    /** Mirrors the host: while Full the combined screen is composed but takes no focus. */
    private var full by androidx.compose.runtime.mutableStateOf(false)
    private val now = System.currentTimeMillis()
    private val minute = 60_000L

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
        db.epgProgramDao().replaceAll(
            (small + deep).map {
                EpgProgram(channelId = it.id, title = "Show ${it.id}", startUtcMillis = now - 10 * minute, endUtcMillis = now + 50 * minute)
            },
        )
    }

    private fun start() {
        seed()
        val vm = BrowseViewModel(
            categoryDao = db.categoryDao(),
            liveStreamDao = db.liveStreamDao(),
            favoriteDao = db.favoriteDao(),
            recentChannelDao = db.recentChannelDao(),
            hiddenChannelDao = db.hiddenChannelDao(),
            epgProgramDao = db.epgProgramDao(),
            xmltvMode = flowOf(false),
            clock = { now },
            liveTvPrefs = flowOf(LiveTvPrefs(openOn = LiveTvOpenOn.FAVORITES, keepCategoriesOpen = false, view = LiveTvView.GUIDE)),
        )
        viewModel = vm
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        val gvm = testGuideViewModel(ApplicationProvider.getApplicationContext<Application>(), db) { now }
        guideViewModel = gvm
        playback = LivePlayback(
            playingStreamIdProvider = { playingId },
            onPlayChannel = {},
            fullActiveProvider = { full },
            locate = { id ->
                val dao = db.liveStreamDao()
                dao.getById(id)?.let { ChannelLocation(it.categoryId, dao.rowsBefore(it.categoryId, it.num, it.name)) }
            },
        )
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.focusProperties { canFocus = !full },
                    ) {
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
        }
        compose.waitUntil(WAIT_MILLIS) { !vm.ui.value.isLoading }
        compose.waitForIdle()
    }

    private fun cell(label: String): SemanticsMatcher = hasText(label, substring = true) and hasClickAction()

    private fun exists(m: SemanticsMatcher): Boolean = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    private fun awaitFocus(m: SemanticsMatcher, what: String) {
        compose.waitUntil(WAIT_MILLIS) { exists(m.and(isFocused())) }
        assertTrue("$what should hold focus", exists(m.and(isFocused())))
    }

    private fun focus(m: SemanticsMatcher) {
        compose.waitUntil(WAIT_MILLIS) { exists(m) }
        compose.onAllNodes(m)[0].performSemanticsAction(SemanticsActions.RequestFocus) { it?.invoke() }
        awaitFocus(m, "the requested node")
    }

    @Test
    fun `guide, hold from the rail with another category open, playing channel deep in its own category`() {
        start()
        // Small is the open category (Favorites opens on s0); fan the rail out with Left from its cell.
        compose.waitUntil(WAIT_MILLIS) { exists(cell("Small 000")) }
        focus(cell("Small 000"))
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.waitUntil(WAIT_MILLIS) { exists(cell("Deep")) }
        awaitFocus(cell("Favorites").or(cell("Small")).or(cell("Deep")), "a rail row")

        // Back out of full screen after the hold: the playing channel is row 262 of Deep.
        playingId = "d262"
        // The hold returns to Full: the screen loses focus. Back out: Preview, restore asked.
        compose.runOnIdle { full = true; playback.jumpToPlayingOnBack = true }
        compose.waitForIdle()
        compose.runOnIdle {
            full = false
            playback.requestRestore("d262")
        }
        compose.waitForIdle()

        awaitFocus(cell("Deep 262"), "the playing channel's cell")
    }


    @Test
    fun `guide, category picked with OK on the rail just before the hold, playing channel deep in another category`() {
        start()
        compose.waitUntil(WAIT_MILLIS) { exists(cell("Small 000")) }
        focus(cell("Small 000"))
        // Rail out, pick Small with OK (the pending select), rail out again, as QA did.
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitUntil(WAIT_MILLIS) { exists(cell("Deep")) }
        focus(cell("Small").and(hasClickAction()).and(androidx.compose.ui.test.hasText("Small", substring = true)).and(androidx.compose.ui.test.hasText("5", substring = true)))
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.waitUntil(WAIT_MILLIS) { exists(cell("Small 000").and(isFocused())) || exists(cell("Show s0")) }
        focus(cell("Small 000"))
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.waitUntil(WAIT_MILLIS) { exists(cell("Deep")) }

        playingId = "d262"
        compose.runOnIdle { full = true; playback.jumpToPlayingOnBack = true }
        compose.waitForIdle()
        compose.runOnIdle {
            full = false
            playback.requestRestore("d262")
        }
        compose.waitForIdle()

        awaitFocus(cell("Deep 262"), "the playing channel's cell")
    }

    private companion object {
        const val WAIT_MILLIS = 15_000L
    }
}
