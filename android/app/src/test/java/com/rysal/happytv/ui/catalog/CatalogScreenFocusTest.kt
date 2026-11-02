package com.rysal.happytv.ui.catalog

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
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
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.network.RefreshState
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * #72 acceptance, D-pad half: the one-screen Movies opens with focus on the first poster (never
 * the Back button), Left from the first column fans the rail out onto the selected row, and OK on
 * a rail row folds it away again with the first poster of that category focused.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
class CatalogScreenFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: HappyTvDatabase
    private val viewModels = mutableListOf<CatalogViewModel>()
    private var opened: String? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        kotlinx.coroutines.runBlocking {
            db.categoryDao().replaceAll(
                StreamType.VOD,
                listOf(
                    Category(id = "drama", type = StreamType.VOD, name = "Drama"),
                    Category(id = "comedy", type = StreamType.VOD, name = "Comedy"),
                ),
            )
            db.vodStreamDao().replaceAll(
                List(16) { i ->
                    VodStream(
                        id = "m$i",
                        categoryId = if (i < 10) "drama" else "comedy",
                        num = i + 1,
                        name = "Movie %02d".format(i + 1),
                        // Movie 01 is the newest.
                        added = 1_000L - i,
                    )
                },
            )
        }
    }

    @After
    fun tearDown() {
        val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
        viewModels.clear()
        RoomTestTeardown.shutDown(jobs, db)
    }

    private var seedProgress = 0

    private fun factory(last: String?) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CatalogViewModel(
            categoryDao = db.categoryDao(),
            vodStreamDao = db.vodStreamDao(),
            seriesStreamDao = db.seriesStreamDao(),
            favoriteDao = db.favoriteDao(),
            streamType = StreamType.VOD,
            refreshProgress = flowOf(RefreshState.Done(channels = 1, ms = 1L)),
            lastCategory = flowOf(last),
            watchProgressDao = db.watchProgressDao(),
        ).also { viewModels += it } as T
    }

    private fun start(last: String? = null) {
        kotlinx.coroutines.runBlocking {
            repeat(seedProgress) { i ->
                db.watchProgressDao().upsert(
                    com.rysal.happytv.data.entity.WatchProgress(
                        streamType = StreamType.VOD,
                        streamId = "m$i",
                        positionMillis = 600_000L,
                        durationMillis = 6_000_000L,
                        updatedUtcMillis = 10_000L - i,
                    ),
                )
            }
        }
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    CatalogScreen(
                        streamType = StreamType.VOD,
                        viewModelFactory = factory(last),
                        onItemClick = { opened = it.id },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // The caption sits under the art, outside the focus target: the poster is found by its description.
    private fun poster(name: String) = hasContentDescription(name) and hasClickAction()
    private fun exists(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun focused(m: SemanticsMatcher) = exists(m and isFocused())

    private fun assertFocused(m: SemanticsMatcher, what: String) {
        compose.waitUntil(10_000) { focused(m) }
        assertTrue("$what should hold focus", focused(m))
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `focus starts on the first poster and never on a Back button`() {
        start()
        assertFocused(poster("Movie 01"), "the first poster")
        assertTrue("Back is not focused", !focused(hasContentDescription("Back")))
    }

    @Test
    fun `Left from the first column fans the rail out onto the selected row, OK on All folds it away`() {
        start()
        assertFocused(poster("Movie 01"), "the first poster")

        press(Key.DirectionLeft)
        assertFocused(hasText("Recently added"), "the selected rail row")

        // Recently added -> All movies (two rows down: All follows Recently added).
        press(Key.DirectionDown)
        assertFocused(hasText("All movies"), "All movies")
        press(Key.Enter)
        assertFocused(poster("Movie 01"), "the first poster of All movies")
    }

    @Test
    fun `OK on a poster opens its details`() {
        start()
        assertFocused(poster("Movie 01"), "the first poster")
        press(Key.Enter)
        compose.waitUntil(5_000) { opened != null }
        assertEquals("m0", opened)
    }

    @Test
    fun `the screen opens on the last category used there`() {
        start(last = "comedy")
        assertFocused(poster("Movie 11"), "the first comedy poster")
        assertTrue("a drama poster is not on screen", !exists(poster("Movie 01")))
    }

    private fun anyFocused() = exists(isFocused())

    private fun continueWatching(count: Int) {
        seedProgress = count
        start(last = CatalogViewModel.CONTINUE_CATEGORY_ID)
        assertFocused(poster("Movie 01"), "the first Continue watching poster")
    }

    /** #109: from the header, Left (repeated) never leaves the screen without a focused node. */
    private fun headerLeftNeverLosesFocus(count: Int) {
        continueWatching(count)
        press(Key.DirectionUp)
        assertTrue("Up from the poster lands on the header", anyFocused())
        repeat(6) { n ->
            press(Key.DirectionLeft)
            compose.waitForIdle()
            assertTrue("a node holds focus after Left #${n + 1} from the header ($count posters)", anyFocused())
            assertTrue("Back keeps focus: nothing lies left of it", focused(hasContentDescription("Back")))
        }
    }

    @Test
    fun `Left from the header keeps a focused node with one Continue watching poster`() = headerLeftNeverLosesFocus(1)

    @Test
    fun `Left from the header keeps a focused node with two Continue watching posters`() = headerLeftNeverLosesFocus(2)

    @Test
    fun `Up and Down between the header and a one-poster Continue watching grid keep focus`() {
        continueWatching(1)
        repeat(3) {
            press(Key.DirectionUp)
            assertTrue("header holds focus", anyFocused() && !focused(poster("Movie 01")))
            press(Key.DirectionDown)
            assertFocused(poster("Movie 01"), "the poster again")
        }
    }

    @Test
    fun `Left from the only Continue watching poster fans the rail out`() {
        continueWatching(1)
        press(Key.DirectionLeft)
        assertFocused(hasText("Continue watching"), "the Continue watching rail row")
    }
}
