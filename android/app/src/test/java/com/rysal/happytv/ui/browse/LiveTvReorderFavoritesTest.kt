package com.rysal.happytv.ui.browse

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
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
import com.rysal.happytv.data.LiveTvView
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.guide.GuideViewModel
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #221 end to end on the JVM: the real Live TV list over a real Room-backed [BrowseViewModel], driven
 * with the keys of a remote. Menu entry only in Favorites, the mode panel, select / move / place,
 * Back (undo, then exit), the jumps, and Left and Right resting with nothing selected.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LiveTvReorderFavoritesTest {

    @get:Rule
    val compose = createComposeRule()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: HappyTvDatabase
    private lateinit var viewModel: BrowseViewModel
    private lateinit var guideViewModel: GuideViewModel
    private var played = 0

    private fun rowStarting(prefix: String) = SemanticsMatcher("content description starts with \"$prefix\"") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        db.categoryDao().replaceAll(StreamType.LIVE, listOf(Category(id = "news", type = StreamType.LIVE, name = "News")))
        db.liveStreamDao().replaceAll(
            listOf(
                LiveStream(id = "101", categoryId = "news", num = 101, name = "Northbay News"),
                LiveStream(id = "102", categoryId = "news", num = 102, name = "Capital Desk"),
                LiveStream(id = "103", categoryId = "news", num = 103, name = "Harbor Weather"),
            ),
        )
        listOf("101", "102", "103").forEach { db.favoriteDao().toggle(it, StreamType.LIVE) }
        guideViewModel = testGuideViewModel(context, db)
        viewModel = BrowseViewModel(
            categoryDao = db.categoryDao(), liveStreamDao = db.liveStreamDao(), favoriteDao = db.favoriteDao(),
            recentChannelDao = db.recentChannelDao(), hiddenChannelDao = db.hiddenChannelDao(),
            epgProgramDao = db.epgProgramDao(), xmltvMode = flowOf(false), clock = { 0L },
            liveTvPrefs = flowOf(LiveTvPrefs(view = LiveTvView.LIST)),
            writeDispatcher = Dispatchers.Unconfined,
        )
        viewModel.selectCategory(LiveCategorySelection.Favorites)
        advanceUntilIdle()
        played = 0
    }

    @After
    fun tearDown() {
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
                            override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
                        },
                        guideViewModelFactory = factoryOf(guideViewModel),
                        onPlayChannel = { played++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 10_000L) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

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
        val first = rowStarting("Northbay News")
        focus(first)
        compose.onNode(first).performKeyInput { pressKey(Key.DirectionCenter, 800) }
        compose.waitForIdle()
        waitFor(hasText("Reorder channels"))
        press(Key.DirectionDown) // Remove from favorites -> Reorder channels
        press(Key.DirectionCenter)
        waitFor(hasText("Reordering favorites"))
    }

    private suspend fun savedOrder() = db.favoriteDao().getOrdered(StreamType.LIVE).map { it.streamId }

    @Test
    fun `Favorites rows show their position, not the provider's number`() {
        setScreen()
        waitFor(rowStarting("Northbay News, position 1"))
        waitFor(rowStarting("Harbor Weather, position 3"))
    }

    @Test
    fun `the menu offers Reorder channels in Favorites only`() {
        setScreen()
        val first = rowStarting("Northbay News")
        focus(first)
        compose.onNode(first).performKeyInput { pressKey(Key.DirectionCenter, 800) }
        compose.waitForIdle()
        waitFor(hasText("Reorder channels"))
        press(Key.Back)

        viewModel.selectCategory(LiveCategorySelection.AllChannels)
        val inAll = rowStarting("Northbay News, channel 101")
        focus(inAll)
        compose.onNode(inAll).performKeyInput { pressKey(Key.DirectionCenter, 800) }
        compose.waitForIdle()
        waitFor(hasText("Remove from favorites"))
        compose.onNode(hasText("Reorder channels")).assertDoesNotExist()
    }

    @Test
    fun `entering the mode shows the panel with the copy from the issue and hides Search and More`() {
        setScreen()
        enterReorderMode()
        compose.onNode(hasText("Choose a channel to move")).assertExists()
        compose.onNode(hasText("to select channel")).assertExists()
        compose.onNode(hasText("to finish")).assertExists()
        compose.onNode(hasText("Each move saves right away")).assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("More options")).assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasText("Search")).assertDoesNotExist()
    }

    @Test
    fun `OK selects, Down moves, OK places and the place is saved`() = runBlocking {
        setScreen()
        enterReorderMode()
        press(Key.DirectionCenter) // select Northbay News
        waitFor(hasText("Moving Northbay News"))
        compose.onNode(hasText("to place")).assertExists()
        compose.onNode(hasText("to cancel")).assertExists()
        assertEquals("selecting plays nothing", 0, played)

        press(Key.DirectionDown)
        waitFor(hasText("Position 2 of 3 · was 1"))
        waitFor(rowStarting("Northbay News, position 2, selected"))
        press(Key.DirectionCenter) // place
        waitFor(hasText("Placed Northbay News at 2"))
        db.favoriteDao().observeOrdered(StreamType.LIVE).first { rows -> rows.map { it.streamId } == listOf("102", "101", "103") }
        assertEquals(listOf("102", "101", "103"), savedOrder())
        assertEquals(0, played)
    }

    @Test
    fun `Back with a channel selected undoes the move and stays, then Back exits`() = runBlocking {
        setScreen()
        enterReorderMode()
        press(Key.DirectionCenter)
        waitFor(hasText("Moving Northbay News")) // the screen has the selection before the next key
        press(Key.DirectionDown)
        waitFor(hasText("Position 2 of 3 · was 1"))

        press(Key.Back)
        waitFor(hasText("Northbay News is back at 1"))
        compose.onNode(hasText("Reordering favorites")).assertExists()
        assertEquals(listOf("101", "102", "103"), savedOrder())

        press(Key.Back)
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodes(hasText("Reordering favorites")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `Right and Left jump the selected channel to the bottom and top, and rest with nothing selected`() {
        setScreen()
        enterReorderMode()
        press(Key.DirectionRight) // nothing selected: rests
        compose.onNode(hasText("Choose a channel to move")).assertExists()
        press(Key.DirectionCenter)
        waitFor(hasText("Moving Northbay News")) // the screen has the selection before the next key
        press(Key.DirectionRight)
        waitFor(hasText("Position 3 of 3 · was 1"))
        press(Key.DirectionLeft)
        waitFor(hasText("Position 1 of 3"))
        press(Key.MediaFastForward)
        waitFor(hasText("Position 3 of 3 · was 1"))
        press(Key.MediaRewind)
        waitFor(hasText("Position 1 of 3"))
        compose.onNode(hasText("Moving Northbay News")).assertExists()
    }
}
