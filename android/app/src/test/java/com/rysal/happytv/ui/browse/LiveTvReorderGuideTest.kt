package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * #221 in the guide: the channel column reorders in place, program cells take no focus, and Left
 * and Right jump the picked-up channel instead of moving through time.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class LiveTvReorderGuideTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private var browse: BrowseViewModel? = null
    private var guide: GuideViewModel? = null
    private var played = 0

    private val now = System.currentTimeMillis()
    private val minute = 60_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            db.categoryDao().replaceAll(StreamType.LIVE, listOf(Category(id = "news", type = StreamType.LIVE, name = "News")))
            db.liveStreamDao().replaceAll(
                listOf(
                    LiveStream(id = "100", categoryId = "news", num = 100, name = "Northbay News"),
                    LiveStream(id = "101", categoryId = "news", num = 101, name = "Capital Desk"),
                    LiveStream(id = "102", categoryId = "news", num = 102, name = "Harbor Live"),
                ),
            )
            listOf("100", "101", "102").forEach { db.favoriteDao().toggle(it, StreamType.LIVE) }
            db.epgProgramDao().replaceAll(
                listOf(
                    EpgProgram(channelId = "100", title = "Headline report", startUtcMillis = now - 10 * minute, endUtcMillis = now + 20 * minute),
                    EpgProgram(channelId = "100", title = "Weather watch", startUtcMillis = now + 20 * minute, endUtcMillis = now + 50 * minute),
                    EpgProgram(channelId = "101", title = "Capital tonight", startUtcMillis = now - 10 * minute, endUtcMillis = now + 20 * minute),
                    EpgProgram(channelId = "102", title = "Harbor evening", startUtcMillis = now - 10 * minute, endUtcMillis = now + 20 * minute),
                ),
            )
        }
        val vm = BrowseViewModel(
            categoryDao = db.categoryDao(), liveStreamDao = db.liveStreamDao(), favoriteDao = db.favoriteDao(),
            recentChannelDao = db.recentChannelDao(), hiddenChannelDao = db.hiddenChannelDao(),
            epgProgramDao = db.epgProgramDao(), xmltvMode = flowOf(false), clock = { now },
            liveTvPrefs = flowOf(LiveTvPrefs(openOn = com.rysal.happytv.data.LiveTvOpenOn.FAVORITES, view = LiveTvView.GUIDE)),
            writeDispatcher = Dispatchers.Unconfined,
        )
        browse = vm
        val gvm = testGuideViewModel(context, db) { now }
        guide = gvm
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    LiveTvScreen(
                        viewModelFactory = factoryOf(vm),
                        guideViewModelFactory = factoryOf(gvm),
                        onPlayChannel = { played++ },
                    )
                }
            }
        }
        compose.waitUntil(WAIT_MILLIS) { !vm.ui.value.isLoading }
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        RoomTestTeardown.shutDown(listOf(browse?.viewModelScope?.coroutineContext?.job, guide?.viewModelScope?.coroutineContext?.job), db)
    }

    private fun cell(prefix: String) = SemanticsMatcher("channel cell starting with \"$prefix\"") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(WAIT_MILLIS) { exists(matcher) }

    private fun focus(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus) { it?.invoke() }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun enterReorderMode() {
        val northbay = cell("Northbay News, channel 1")
        focus(northbay)
        compose.onNode(northbay).performKeyInput { pressKey(Key.DirectionCenter, 800) }
        compose.waitForIdle()
        waitFor(hasText("Reorder channels"))
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        waitFor(hasText("Reordering favorites"))
    }

    @Test
    fun `the guide stays the guide, numbers its channels 1 to N, and program cells take no focus`() {
        waitFor(cell("Capital Desk, channel 2"))
        assertTrue("programs are clickable before the mode", exists(hasText("Headline report", substring = true) and hasClickAction()))

        enterReorderMode()

        waitFor(hasText("Headline report", substring = true))
        assertFalse("program cells are not focus stops in the mode", exists(hasText("Headline report", substring = true) and hasClickAction()))
        assertFalse(exists(hasText("Weather watch", substring = true) and hasClickAction()))
        assertTrue("still the guide: the corner cell names the list", exists(hasText("Favorites", substring = true)))
        // The channel cell kept the D-pad.
        assertTrue(exists(cell("Northbay News") and isFocused()))
    }

    @Test
    fun `Down moves the picked-up channel in place, Left and Right jump it and never move the window in time`() = runBlocking<Unit> {
        enterReorderMode()
        press(Key.DirectionCenter) // pick up Northbay News
        waitFor(hasText("Moving Northbay News"))

        press(Key.DirectionDown)
        waitFor(cell("Northbay News, position 2, selected"))
        waitFor(cell("Capital Desk, position 1"))
        assertTrue("the channel column kept the D-pad", exists(cell("Northbay News") and isFocused()))

        press(Key.DirectionRight)
        waitFor(cell("Northbay News, position 3, selected"))
        press(Key.DirectionLeft)
        waitFor(cell("Northbay News, position 1, selected"))
        assertFalse("Left and Right did not scroll time", guide!!.ui.value.shifted)
        assertEquals("nothing played", 0, played)

        press(Key.DirectionCenter) // place at the top
        waitFor(hasText("Placed Northbay News at 1"))
        // Back out of the mode with nothing selected.
        press(Key.Back)
        compose.waitUntil(WAIT_MILLIS) { !exists(hasText("Reordering favorites")) }
        db.favoriteDao().observeOrdered(StreamType.LIVE).first { rows -> rows.map { it.streamId } == listOf("100", "101", "102") }
    }

    private companion object {
        const val WAIT_MILLIS = 15_000L
    }
}
