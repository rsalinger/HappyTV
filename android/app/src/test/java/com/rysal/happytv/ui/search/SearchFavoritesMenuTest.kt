package com.rysal.happytv.ui.search

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.dao.SearchChannelRow
import com.rysal.happytv.data.dao.SearchPosterRow
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * #232: hold OK (or the Menu key) on a Search result opens a menu that adds or removes the
 * favorite, through the real [com.rysal.happytv.data.dao.FavoriteDao.toggle]. Focus comes back to
 * the same result either way; a hold never plays or opens.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SearchFavoritesMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private lateinit var db: HappyTvDatabase
    private val played = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun channel(name: String) = hasText(name) and hasClickAction()
    private fun poster(name: String) = hasContentDescription(name) and hasClickAction()
    private fun item(label: String) = hasText(label) and hasClickAction()
    private val field = SemanticsMatcher("search field") { it.config.contains(SemanticsActions.SetText) }
    private val star = hasContentDescription("In favorites")

    private fun start() {
        val dao = db.favoriteDao()
        val vm = SearchViewModel(
            channels = flowOf(listOf(SearchChannelRow(id = "101", name = "Courtside", num = 101))),
            movies = flowOf(listOf(SearchPosterRow(id = "m1", name = "The Long Coast", year = "2023"))),
            shows = flowOf(listOf(SearchPosterRow(id = "s1", name = "Comet Crew", year = "2022"))),
            favorites = dao.observeAll(),
            toggleFavoriteRow = { id, type -> dao.toggle(id, type) },
            matchDispatcher = UnconfinedTestDispatcher(scheduler),
        )
        compose.setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    SearchOverlay(
                        onBack = {},
                        onPlayChannel = { played += it },
                        onOpenDetails = { id, _ -> opened += id },
                        viewModel = vm,
                    )
                }
            }
        }
        compose.waitForIdle()
        vm.onQueryChange("co")
        scheduler.advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        scheduler.runCurrent()
        compose.waitUntil(10_000) { exists(channel("Courtside")) }
        compose.waitUntil(10_000) { exists(field.and(isFocused())) }
    }

    private fun exists(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Down from the field lands on the first card; further Downs walk to the next group. */
    private fun focus(m: SemanticsMatcher) {
        press(Key.DirectionDown)
        var guard = 0
        while (!exists(m.and(isFocused())) && guard++ < 6) press(Key.DirectionDown)
        assertTrue("target should hold focus", exists(m.and(isFocused())))
    }

    private fun assertFocused(m: SemanticsMatcher) {
        compose.waitUntil(5_000) { exists(m.and(isFocused())) }
    }

    private fun positions(type: StreamType) =
        runBlocking { db.favoriteDao().getOrdered(type) }.map { it.streamId to it.position }

    @Test
    fun menuKeyOnChannel_addsFavoriteLast_starAppears_focusReturns() {
        runBlocking { db.favoriteDao().insert(Favorite("999", StreamType.LIVE, position = 4)) }
        start()
        focus(channel("Courtside"))

        press(Key.Menu)
        compose.waitUntil(5_000) { exists(item("Add to favorites")) }
        assertFocused(item("Add to favorites"))
        assertFalse(exists(star))

        press(Key.DirectionCenter)
        compose.waitUntil(5_000) { exists(star) }
        compose.waitUntil(5_000) { !exists(item("Add to favorites")) }
        assertFocused(channel("Courtside"))
        assertEquals(listOf("999" to 4, "101" to 5), positions(StreamType.LIVE))
        assertTrue(played.isEmpty())
    }

    @Test
    fun menuOnFavoritedMovie_saysRemove_andRemoves() {
        runBlocking { db.favoriteDao().insert(Favorite("m1", StreamType.VOD, position = 1)) }
        start()
        compose.waitUntil(5_000) { exists(star) }
        focus(poster("The Long Coast"))

        press(Key.Menu)
        compose.waitUntil(5_000) { exists(item("Remove from favorites")) }
        press(Key.DirectionCenter)
        compose.waitUntil(5_000) { !exists(star) }
        assertFocused(poster("The Long Coast"))
        assertTrue(positions(StreamType.VOD).isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun menuOnShow_togglesTheSeriesCatalogOnly() {
        // The same id as a movie must not collide: ids are per catalog.
        runBlocking { db.favoriteDao().insert(Favorite("s1", StreamType.VOD, position = 1)) }
        start()
        focus(poster("Comet Crew"))
        press(Key.Menu)
        compose.waitUntil(5_000) { exists(item("Add to favorites")) }
        press(Key.DirectionCenter)
        compose.waitUntil(5_000) { positions(StreamType.SERIES).isNotEmpty() }
        assertEquals(listOf("s1" to 1), positions(StreamType.SERIES))
        assertEquals(listOf("s1" to 1), positions(StreamType.VOD))
        assertFocused(poster("Comet Crew"))
    }

    @Test
    fun back_closesTheMenuWithNoChange_andFocusReturns() {
        start()
        focus(channel("Courtside"))
        press(Key.Menu)
        compose.waitUntil(5_000) { exists(item("Add to favorites")) }

        press(Key.Back)
        compose.waitUntil(5_000) { !exists(item("Add to favorites")) }
        assertFocused(channel("Courtside"))
        assertTrue(positions(StreamType.LIVE).isEmpty())
        assertTrue(played.isEmpty())
    }

    @Test
    fun holdingOk_opensTheMenu_andDoesNotPlayOnRelease() {
        start()
        focus(channel("Courtside"))
        compose.mainClock.autoAdvance = false
        compose.onNode(isFocused()).performKeyInput { keyDown(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(700)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { exists(item("Add to favorites")) }
        // The release lands on the card or the menu's item: neither may act.
        compose.onNode(isFocused()).performKeyInput { keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue("a hold must not play the channel", played.isEmpty())
        assertTrue("the release must not pick the menu item", positions(StreamType.LIVE).isEmpty())
    }
}
