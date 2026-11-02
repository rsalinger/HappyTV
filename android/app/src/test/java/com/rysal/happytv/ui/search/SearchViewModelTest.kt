package com.rysal.happytv.ui.search

import com.rysal.happytv.data.dao.SearchChannelRow
import com.rysal.happytv.data.dao.SearchPosterRow
import com.rysal.happytv.perf.HtvPerf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [SearchViewModel] against the design spec §"Screens: search" artboards:
 * the 300 ms pause, the per-type rows with their counts, the scope control,
 * "No matches", and the row caps (four channels, eight posters).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    private val channels = MutableStateFlow(CHANNELS)
    private val movies = MutableStateFlow(MOVIES)
    private val shows = MutableStateFlow(SHOWS)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(scope: SearchScope = SearchScope.ALL) =
        SearchViewModel(
            channels, movies, shows, scope,
            matchDispatcher = StandardTestDispatcher(scheduler),
        )

    /** Type [text], wait out the pause, read the settled state. */
    private suspend fun kotlinx.coroutines.test.TestScope.settled(
        vm: SearchViewModel,
        text: String,
    ): SearchUiState {
        val next = async { vm.ui.first { it.settledQuery == text } }
        vm.onQueryChange(text)
        advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        return next.await()
    }

    @Test
    fun `empty query stays in the ready-to-type state`() = runTest(scheduler) {
        val state = viewModel().ui.first { it.phase == SearchPhase.TYPING }
        assertEquals(SearchPhase.TYPING, state.phase)
        assertEquals("", state.settledQuery)
        assertTrue(state.channels.isEmpty() && state.movies.isEmpty() && state.shows.isEmpty())
    }

    @Test
    fun `co finds the artboard's channels movies and shows`() = runTest(scheduler) {
        val state = settled(viewModel(), "co")
        assertEquals(SearchPhase.RESULTS, state.phase)
        // Catalog order (by channel number), which is the order the row draws.
        assertEquals(listOf("Courtside", "County Weather", "Little Comets"), state.channels.map { it.name })
        assertEquals(3, state.channelCount)
        assertEquals(listOf("The Long Coast", "Copper Sky"), state.movies.map { it.name })
        assertEquals(2, state.movieCount)
        assertEquals(listOf("Comet Crew"), state.shows.map { it.name })
        assertEquals(1, state.showCount)
    }

    @Test
    fun `co sk narrows to Copper Sky only`() = runTest(scheduler) {
        val state = settled(viewModel(), "co sk")
        assertEquals(SearchPhase.RESULTS, state.phase)
        assertEquals(listOf("Copper Sky"), state.movies.map { it.name })
        assertTrue(state.channels.isEmpty())
        assertTrue(state.shows.isEmpty())
    }

    @Test
    fun `channel cards carry the Channel number line`() = runTest(scheduler) {
        val state = settled(viewModel(), "court")
        assertEquals(listOf("Channel 101"), state.channels.map { it.numberLabel })
    }

    @Test
    fun `movies show a year when the catalog has one`() = runTest(scheduler) {
        val state = settled(viewModel(), "long")
        assertEquals(listOf("2023"), state.movies.map { it.year })
    }

    @Test
    fun `scope hides the other two rows but keeps the counts honest`() = runTest(scheduler) {
        val state = settled(viewModel(SearchScope.MOVIES), "co")
        assertTrue(state.channels.isEmpty() && state.shows.isEmpty())
        assertEquals(0, state.channelCount)
        assertEquals(0, state.showCount)
        assertEquals(2, state.movieCount)
        assertEquals(SearchScope.MOVIES, state.scope)
    }

    @Test
    fun `switching scope re-runs the settled query immediately`() = runTest(scheduler) {
        val vm = viewModel()
        val state = settled(vm, "co")
        assertEquals(3, state.channelCount)

        val scoped = async { vm.ui.first { it.scope == SearchScope.CHANNELS } }
        vm.selectScope(SearchScope.CHANNELS)
        advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        val narrowed = scoped.await()
        assertEquals(3, narrowed.channelCount)
        assertEquals(0, narrowed.movieCount)
        assertEquals(SearchPhase.RESULTS, narrowed.phase)
    }

    @Test
    fun `zebra is the no-matches state naming the query`() = runTest(scheduler) {
        val state = settled(viewModel(), "zebra")
        assertEquals(SearchPhase.NO_MATCHES, state.phase)
        assertEquals("zebra", state.settledQuery)
    }

    @Test
    fun `a query over an empty library reuses the signed-out wording`() = runTest(scheduler) {
        channels.value = emptyList()
        movies.value = emptyList()
        shows.value = emptyList()
        val state = settled(viewModel(), "co")
        assertEquals(SearchPhase.EMPTY_LIBRARY, state.phase)
    }

    @Test
    fun `rows are drawn four channels and eight posters wide but count every match`() = runTest(scheduler) {
        channels.value = (1..9).map { SearchChannelRow(id = "$it", name = "Co Channel $it", num = it) }
        movies.value = (1..12).map { SearchPosterRow(id = "$it", name = "Co Movie $it", year = "2020") }

        val state = settled(viewModel(), "co")
        // All nine match, so all nine are returned: the overlay renders them
        // as three rows of four (SearchViewModel.CHANNELS_PER_ROW).
        assertEquals(9, state.channels.size)
        assertEquals(9, state.channelCount)
        assertEquals(12, state.movies.size)
        assertEquals(12, state.movieCount)
        assertEquals(3, state.channels.chunked(SearchViewModel.CHANNELS_PER_ROW).size)
        assertEquals(2, state.movies.chunked(SearchViewModel.POSTERS_PER_ROW).size)
    }

    @Test
    fun `the render ceiling keeps a one-letter query from composing the world`() = runTest(scheduler) {
        channels.value = (1..500).map { SearchChannelRow(id = "$it", name = "Channel $it", num = it) }
        val state = settled(viewModel(), "cha")
        assertEquals(SearchViewModel.MAX_RESULTS_ALL, state.channels.size)
        assertEquals(500, state.channelCount)
    }

    @Test
    fun `a catalog refresh while the overlay is open re-runs the query`() = runTest(scheduler) {
        val vm = viewModel()
        val first = settled(vm, "co")
        assertEquals(2, first.movieCount)

        movies.value = MOVIES + SearchPosterRow(id = "99", name = "Cold Harbour", year = "2021")
        val refreshed = async { vm.ui.first { it.movieCount == 3 } }
        advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        assertEquals(3, refreshed.await().movieCount)
    }

    @Test
    fun `a single type shows far more than the all view, and the true count stays`() = runTest(scheduler) {
        movies.value = (1..400).map { SearchPosterRow(id = "$it", name = "Cold Movie $it", year = null) }
        val all = settled(viewModel(), "cold")
        assertEquals(SearchViewModel.MAX_RESULTS_ALL, all.movies.size)
        assertEquals(400, all.movieCount)

        val scoped = settled(viewModel(SearchScope.MOVIES), "cold")
        assertEquals(SearchViewModel.MAX_RESULTS_SCOPED, scoped.movies.size)
        assertEquals(400, scoped.movieCount)
    }

    @Test
    fun `poster urls are looked up for the drawn ids only`() = runTest(scheduler) {
        val asked = mutableListOf<List<String>>()
        movies.value = (1..100).map { SearchPosterRow(id = "m$it", name = "Cold Movie $it", year = null) }
        val vm = SearchViewModel(
            channels, movies, shows,
            moviePosterUrls = { ids ->
                asked += ids
                ids.associateWith { if (it == "m1") "https://img.example/m1.jpg" else null }
            },
            matchDispatcher = StandardTestDispatcher(scheduler),
        )
        val state = settled(vm, "cold")
        assertEquals(SearchViewModel.MAX_RESULTS_ALL, asked.last().size)
        assertEquals("https://img.example/m1.jpg", state.movies.first { it.id == "m1" }.posterUrl)
        assertEquals(null, state.movies.first { it.id == "m2" }.posterUrl)
    }

    @Test
    fun `channel logo urls are looked up for the drawn ids only`() = runTest(scheduler) {
        val asked = mutableListOf<List<String>>()
        channels.value = (1..100).map { SearchChannelRow(id = "c$it", name = "Cold Channel $it", num = it) }
        val vm = SearchViewModel(
            channels, movies, shows,
            channelLogoUrls = { ids ->
                asked += ids
                ids.associateWith { if (it == "c1") "https://img.example/c1.png" else null }
            },
            matchDispatcher = StandardTestDispatcher(scheduler),
        )
        val state = settled(vm, "cold")
        assertEquals(SearchViewModel.MAX_RESULTS_ALL, asked.last().size)
        assertEquals("https://img.example/c1.png", state.channels.first { it.id == "c1" }.logoUrl)
        assertEquals(null, state.channels.first { it.id == "c2" }.logoUrl)
    }

    @Test
    fun `search results event reports keystroke to results with counts only`() = runTest(scheduler) {
        val lines = mutableListOf<String>()
        HtvPerf.resetForTest()
        HtvPerf.sink = { lines += it }
        try {
            settled(viewModel(), "co")
        } finally {
            HtvPerf.resetForTest()
        }
        val line = lines.single { it.startsWith(HtvPerf.SEARCH_RESULTS) }
        assertTrue(line, Regex("""search\.results \d+ms match=\d+ms len=2 ch=3 mv=2 sh=1""").matches(line))
    }

    companion object {
        /** The design spec search artboard's mock library. */
        private val CHANNELS = listOf(
            SearchChannelRow(id = "101", name = "Courtside", num = 101),
            SearchChannelRow(id = "102", name = "County Weather", num = 102),
            SearchChannelRow(id = "103", name = "Little Comets", num = 103),
            SearchChannelRow(id = "104", name = "Nightly News", num = 104),
        )
        private val MOVIES = listOf(
            SearchPosterRow(id = "m1", name = "The Long Coast", year = "2023"),
            SearchPosterRow(id = "m2", name = "Copper Sky", year = null),
            SearchPosterRow(id = "m3", name = "Harbour Lights", year = "2019"),
        )
        private val SHOWS = listOf(
            SearchPosterRow(id = "s1", name = "Comet Crew", year = "2022"),
            SearchPosterRow(id = "s2", name = "Morning Roundup", year = "2021"),
        )
    }
}
