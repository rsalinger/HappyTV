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
import com.rysal.happytv.data.entity.Favorite
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
 * #232: hold OK (or Menu) on a Movies poster opens the favorites menu, not a silent toggle;
 * the menu uses the real favorites DAO and focus after a remove from Favorites follows #228 / #230.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
class CatalogFavoritesMenuTest {

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
    private var favorites = listOf<String>()

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

    private fun start(last: String?) {
        kotlinx.coroutines.runBlocking {
            favorites.forEachIndexed { i, id -> db.favoriteDao().insert(Favorite(id, StreamType.VOD, position = i + 1)) }
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

    private fun stored() = kotlinx.coroutines.runBlocking {
        db.favoriteDao().getOrdered(StreamType.VOD).map { it.streamId }
    }

    private fun waitStored(expected: List<String>) {
        compose.waitUntil(10_000) { stored() == expected }
        assertEquals(expected, stored())
    }

    @Test
    fun `Menu key on a poster opens the add item, choosing it adds the favorite last and keeps focus`() {
        favorites = listOf("m5")
        start(CatalogViewModel.ALL_CATEGORY_ID)
        assertFocused(poster("Movie 01"), "the first poster")
        press(Key.Menu)
        assertFocused(hasText("Add to favorites"), "the menu item")
        assertTrue("the menu is titled with the movie", exists(hasText("Movie 01")))
        press(Key.Enter)
        waitStored(listOf("m5", "m0"))
        assertFocused(poster("Movie 01"), "the same poster")
        assertTrue("the menu is gone", !exists(hasText("Add to favorites")))
        assertEquals("the hold never opens details", null, opened)
    }

    @Test
    fun `a favorite shows Remove from favorites and removing it outside Favorites keeps focus on the poster`() {
        favorites = listOf("m0")
        start(CatalogViewModel.ALL_CATEGORY_ID)
        assertFocused(poster("Movie 01"), "the first poster")
        press(Key.Menu)
        assertFocused(hasText("Remove from favorites"), "the menu item")
        press(Key.Enter)
        waitStored(emptyList())
        assertFocused(poster("Movie 01"), "the same poster")
    }

    @Test
    fun `holding OK opens the menu and the release does not open details`() {
        start(CatalogViewModel.ALL_CATEGORY_ID)
        assertFocused(poster("Movie 01"), "the first poster")
        compose.onNode(isFocused()).performKeyInput { keyDown(Key.DirectionCenter) }
        compose.waitUntil(5_000) { exists(hasText("Add to favorites")) }
        compose.onNode(isFocused()).performKeyInput { keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue("the menu stays open after release", exists(hasText("Add to favorites")))
        assertEquals(null, opened)
        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `Back closes the menu with no change and focus returns to the poster`() {
        start(CatalogViewModel.ALL_CATEGORY_ID)
        press(Key.DirectionRight)
        assertFocused(poster("Movie 02"), "the second poster")
        press(Key.Menu)
        assertFocused(hasText("Add to favorites"), "the menu item")
        press(Key.Back)
        assertFocused(poster("Movie 02"), "the same poster")
        assertTrue("the menu is gone", !exists(hasText("Add to favorites")))
        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `removing from Favorites focuses the poster that takes its place`() {
        favorites = listOf("m0", "m1", "m2")
        start(CatalogViewModel.FAVORITES_CATEGORY_ID)
        assertFocused(poster("Movie 01"), "the first favorite")
        press(Key.DirectionRight)
        assertFocused(poster("Movie 02"), "the second favorite")
        press(Key.Menu)
        press(Key.Enter)
        waitStored(listOf("m0", "m2"))
        assertFocused(poster("Movie 03"), "the poster that took its place")
        assertTrue("the removed poster left the grid", !exists(poster("Movie 02")))
    }

    @Test
    fun `removing the last poster of Favorites focuses the one before it`() {
        favorites = listOf("m0", "m1", "m2")
        start(CatalogViewModel.FAVORITES_CATEGORY_ID)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertFocused(poster("Movie 03"), "the last favorite")
        press(Key.Menu)
        press(Key.Enter)
        waitStored(listOf("m0", "m1"))
        assertFocused(poster("Movie 02"), "the poster before it")
    }

    @Test
    fun `removing the only favorite focuses Favorites in the rail`() {
        favorites = listOf("m0")
        start(CatalogViewModel.FAVORITES_CATEGORY_ID)
        assertFocused(poster("Movie 01"), "the only favorite")
        press(Key.Menu)
        press(Key.Enter)
        waitStored(emptyList())
        assertFocused(hasText("Favorites"), "Favorites in the rail")
        assertTrue("the empty state says how to add one", exists(hasText("Hold OK on a poster to add it to favorites.")))
    }

    @Test
    fun `Continue watching menu has the favorites item and Remove from Continue watching`() {
        seedProgress = 2
        start(CatalogViewModel.CONTINUE_CATEGORY_ID)
        assertFocused(poster("Movie 01"), "the first Continue watching poster")
        press(Key.Menu)
        assertFocused(hasText("Add to favorites"), "the favorites item")
        assertTrue("and the continue item", exists(hasText("Remove from Continue watching")))
        press(Key.Enter)
        waitStored(listOf("m0"))
        assertFocused(poster("Movie 01"), "the same poster")
    }
}
