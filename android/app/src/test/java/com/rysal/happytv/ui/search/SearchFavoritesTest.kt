package com.rysal.happytv.ui.search

import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #232: the menu label by favorite state, and the toggle per stream type. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFavoritesTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the menu item says add or remove by favorite state`() {
        assertEquals("Add to favorites", favoriteMenuLabel(isFavorite = false))
        assertEquals("Remove from favorites", favoriteMenuLabel(isFavorite = true))
    }

    @Test
    fun `favorite state is kept per catalog so equal ids do not collide`() {
        val state = SearchFavorites.of(
            listOf(
                Favorite("7", StreamType.LIVE),
                Favorite("8", StreamType.VOD),
                Favorite("7", StreamType.SERIES),
            ),
        )
        assertTrue(state.contains(StreamType.LIVE, "7"))
        assertFalse(state.contains(StreamType.VOD, "7"))
        assertTrue(state.contains(StreamType.VOD, "8"))
        assertTrue(state.contains(StreamType.SERIES, "7"))
        assertFalse(state.contains(StreamType.SERIES, "8"))
    }

    @Test
    fun `toggle goes to the shared path with the result's own stream type`() = runTest {
        val calls = mutableListOf<Pair<String, StreamType>>()
        val vm = SearchViewModel(
            channels = flowOf(emptyList()),
            movies = flowOf(emptyList()),
            shows = flowOf(emptyList()),
            toggleFavoriteRow = { id, type -> calls += id to type },
        )
        vm.toggleFavorite("1", StreamType.LIVE)
        vm.toggleFavorite("2", StreamType.VOD)
        vm.toggleFavorite("3", StreamType.SERIES)
        assertEquals(
            listOf("1" to StreamType.LIVE, "2" to StreamType.VOD, "3" to StreamType.SERIES),
            calls,
        )
    }

    @Test
    fun `stars follow the favorites flow`() = runTest {
        val rows = MutableStateFlow(emptyList<Favorite>())
        val vm = SearchViewModel(
            channels = flowOf(emptyList()),
            movies = flowOf(emptyList()),
            shows = flowOf(emptyList()),
            favorites = rows,
        )
        backgroundScope.launch { vm.favoriteState.collect { } }
        advanceUntilIdle()
        assertFalse(vm.favoriteState.value.contains(StreamType.VOD, "m1"))
        rows.value = listOf(Favorite("m1", StreamType.VOD))
        advanceUntilIdle()
        assertTrue(vm.favoriteState.value.contains(StreamType.VOD, "m1"))
        rows.value = emptyList()
        advanceUntilIdle()
        assertFalse(vm.favoriteState.value.contains(StreamType.VOD, "m1"))
    }
}
