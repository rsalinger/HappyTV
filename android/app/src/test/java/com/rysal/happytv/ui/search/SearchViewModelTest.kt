package com.rysal.happytv.ui.search

import com.rysal.happytv.data.dao.SearchChannelRow
import com.rysal.happytv.data.dao.SearchPosterRow
import com.rysal.happytv.perf.HtvPerf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    /** #241: live collectors per table, to see which tables a scope really subscribes to. */
    private val active = mutableMapOf("channels" to 0, "movies" to 0, "shows" to 0)

    /** How many times each table was subscribed since the test began. */
    private val starts = mutableMapOf("channels" to 0, "movies" to 0, "shows" to 0)

    private fun <T> Flow<T>.counted(table: String) =
        onStart {
            active[table] = active.getValue(table) + 1
            starts[table] = starts.getValue(table) + 1
        }
            .onCompletion { active[table] = active.getValue(table) - 1 }

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
            channels.counted("channels"), movies.counted("movies"), shows.counted("shows"), scope,
            otherTablesEmpty = { false },
            matchDispatcher = StandardTestDispatcher(scheduler),
        )

    /** Type [text], wait out the pause, read the settled state. */
    private suspend fun kotlinx.coroutines.test.TestScope.settled(
        vm: SearchViewModel,
        text: String,
    ): SearchUiState {
        val next = async { vm.ui.first { !it.phase.isPending && it.settledQuery == text } }
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

        val scoped = async { vm.ui.first { !it.phase.isPending && it.scope == SearchScope.CHANNELS } }
        vm.selectScope(SearchScope.CHANNELS)
        advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        val narrowed = scoped.await()
        assertEquals(3, narrowed.channelCount)
        assertEquals(0, narrowed.movieCount)
        assertEquals(SearchPhase.RESULTS, narrowed.phase)
    }

    private val all3 = mapOf("channels" to 1, "movies" to 1, "shows" to 1)
    private val none3 = mapOf("channels" to 0, "movies" to 0, "shows" to 0)

    @Test
    fun `a scoped search shows its first results from its own table, then loads the others in the background`() =
        runTest(scheduler) {
            SearchScope.entries.forEach { scope ->
                val vm = viewModel(scope)
                val own = mapOf(
                    "channels" to if (scope.hides(SearchScope.CHANNELS)) 0 else 1,
                    "movies" to if (scope.hides(SearchScope.MOVIES)) 0 else 1,
                    "shows" to if (scope.hides(SearchScope.SHOWS)) 0 else 1,
                )
                val job = backgroundScope.launch { vm.ui.collect { } }
                advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS - 100)
                // Before the first results state exists, only the scope's own table is subscribed.
                assertEquals("scope $scope before first results", own, active.toMap())
                advanceTimeBy(200)
                // After it, every table is subscribed (loaded in the background) and stays so.
                assertEquals("scope $scope after first results", all3, active.toMap())
                job.cancelAndJoin()
                advanceTimeBy(7_000)
                assertEquals("scope $scope released", none3, active.toMap())
            }
        }

    @Test
    fun `first results do not wait for a hidden table that has not loaded`() = runTest(scheduler) {
        val neverLoads = kotlinx.coroutines.flow.flow<List<SearchPosterRow>> { kotlinx.coroutines.awaitCancellation() }
        val vm = SearchViewModel(
            channels, neverLoads, neverLoads, SearchScope.CHANNELS,
            matchDispatcher = StandardTestDispatcher(scheduler),
        )
        val state = settled(vm, "co")
        assertEquals(SearchPhase.RESULTS, state.phase)
        assertEquals(3, state.channelCount)
    }

    @Test
    fun `hidden tables stay subscribed across a scope switch and a warm switch needs no new subscription`() =
        runTest(scheduler) {
            val vm = viewModel(SearchScope.CHANNELS)
            val job = backgroundScope.launch { vm.ui.collect { } }
            vm.onQueryChange("co")
            advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
            assertEquals(all3, active.toMap())
            val startsBefore = starts.toMap()

            val moviesState = async { vm.ui.first { it.scope == SearchScope.MOVIES && it.movieCount > 0 } }
            vm.selectScope(SearchScope.MOVIES)
            advanceTimeBy(10)
            assertEquals(2, moviesState.await().movieCount)
            vm.selectScope(SearchScope.SHOWS)
            advanceTimeBy(5_000)
            // Nothing was dropped or resubscribed by switching, however long it sat on another scope.
            assertEquals(all3, active.toMap())
            assertEquals(startsBefore, starts.toMap())
            job.cancelAndJoin()
        }

    @Test
    fun `a hidden table loading or changing causes no new match`() = runTest(scheduler) {
        var logoLookups = 0
        val vm = SearchViewModel(
            channels, movies, shows, SearchScope.CHANNELS,
            channelLogoUrls = { logoLookups++; emptyMap() },
            matchDispatcher = StandardTestDispatcher(scheduler),
        )
        val seen = mutableListOf<SearchUiState>()
        val job = backgroundScope.launch { vm.ui.collect { seen += it } }
        vm.onQueryChange("co")
        advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        val matches = logoLookups
        val emissions = seen.size
        assertTrue(matches > 0)

        movies.value = MOVIES + SearchPosterRow(id = "m8", name = "Co Another", year = null, num = 8)
        shows.value = emptyList()
        advanceTimeBy(1_000)
        assertEquals(matches, logoLookups)
        assertEquals(emissions, seen.size)
        job.cancelAndJoin()
    }

    @Test
    fun `matches are ordered by num with null first, then name ignoring case, before the cap`() = runTest(scheduler) {
        channels.value = listOf(
            SearchChannelRow(id = "1", name = "co zulu", num = 2),
            SearchChannelRow(id = "2", name = "Co beta", num = null),
            SearchChannelRow(id = "3", name = "CO Alpha", num = null),
            SearchChannelRow(id = "4", name = "co alpha", num = 1),
            SearchChannelRow(id = "5", name = "Co Alpha", num = 1),
        )
        val state = settled(viewModel(SearchScope.CHANNELS), "co")
        // Null num first (Alpha, beta), then num 1 (the two Alphas tie and keep table order), then num 2.
        assertEquals(listOf("3", "2", "4", "5", "1"), state.channels.map { it.id })
    }

    @Test
    fun `the cap keeps the first matches in sort order, not the first rows of the table`() = runTest(scheduler) {
        channels.value = (1..100).map { SearchChannelRow(id = "$it", name = "Co Channel $it", num = 101 - it) }
        val state = settled(viewModel(), "co")
        assertEquals(SearchViewModel.MAX_RESULTS_ALL, state.channels.size)
        assertEquals((100 downTo 77).map { "$it" }, state.channels.map { it.id })
        assertEquals(100, state.channelCount)
    }

    @Test
    fun `a scoped search over an empty table checks the other tables before saying the library is empty`() = runTest(scheduler) {
        channels.value = emptyList()
        val withOthers = SearchViewModel(
            channels, movies, shows, SearchScope.CHANNELS,
            otherTablesEmpty = { false },
            matchDispatcher = StandardTestDispatcher(scheduler),
        )
        assertEquals(SearchPhase.NO_MATCHES, settled(withOthers, "co").phase)

        val allEmpty = SearchViewModel(
            channels, movies, shows, SearchScope.CHANNELS,
            otherTablesEmpty = { true },
            matchDispatcher = StandardTestDispatcher(scheduler),
        )
        assertEquals(SearchPhase.EMPTY_LIBRARY, settled(allEmpty, "co").phase)
    }

    /** Runs matches without a dispatcher hop, so a match over a stale list cannot be cancelled before it asks. */
    private fun recordingViewModel(asked: MutableList<List<String>>) = SearchViewModel(
        channels, movies, shows,
        moviePosterUrls = { ids -> asked += ids; emptyMap() },
        matchDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(scheduler),
    )

    private val freshMovies get() = MOVIES + SearchPosterRow(id = "m9", name = "Co Fresh", year = null, num = 9)

    @Test
    fun `a table that changed while its scope was hidden is current when the scope returns`() = runTest(scheduler) {
        val asked = mutableListOf<List<String>>()
        val vm = recordingViewModel(asked)
        val job = backgroundScope.launch { vm.ui.collect { } }
        vm.onQueryChange("co")
        advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        assertTrue(asked.isNotEmpty())

        vm.selectScope(SearchScope.CHANNELS)
        advanceTimeBy(5_000)
        movies.value = freshMovies
        asked.clear()

        vm.selectScope(SearchScope.ALL)
        advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        assertTrue(asked.isNotEmpty())
        // No match over the list loaded before the change: every lookup already includes the new row.
        assertTrue(asked.toString(), asked.all { "m9" in it })
        job.cancelAndJoin()
    }

    @Test
    fun `after the overlay stops collecting no table list is replayed on return`() = runTest(scheduler) {
        val asked = mutableListOf<List<String>>()
        var subscriptions = 0
        // First subscription answers at once; the second one is slow, so a replayed old list would be matched first.
        val movieTable = kotlinx.coroutines.flow.flow<List<SearchPosterRow>> {
            if (++subscriptions == 1) {
                emit(MOVIES)
            } else {
                kotlinx.coroutines.delay(500)
                emit(freshMovies)
            }
            kotlinx.coroutines.awaitCancellation()
        }
        val vm = SearchViewModel(
            channels, movieTable, shows,
            moviePosterUrls = { ids -> asked += ids; emptyMap() },
            matchDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(scheduler),
        )
        val first = backgroundScope.launch { vm.ui.collect { } }
        vm.onQueryChange("co")
        advanceTimeBy(SearchViewModel.SEARCH_PAUSE_MS + 50)
        assertTrue(asked.isNotEmpty())
        first.cancelAndJoin()
        // Past the screen state's stop timeout and the keep-warm time: nothing is collected or cached.
        advanceTimeBy(7_000)
        asked.clear()

        val second = backgroundScope.launch { vm.ui.collect { } }
        advanceTimeBy(1_000)
        assertEquals(2, subscriptions)
        assertTrue(asked.isNotEmpty())
        assertTrue(asked.toString(), asked.all { "m9" in it })
        second.cancelAndJoin()
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
        movies.value = (1..12).map { SearchPosterRow(id = "$it", name = "Co Movie $it", year = "2020", num = it) }

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
        movies.value = (1..400).map { SearchPosterRow(id = "$it", name = "Cold Movie $it", year = null, num = it) }
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
        movies.value = (1..100).map { SearchPosterRow(id = "m$it", name = "Cold Movie $it", year = null, num = it) }
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

    // ---- #257: the searching state ----

    private val pause = SearchViewModel.SEARCH_PAUSE_MS
    private val grace = SearchViewModel.SEARCHING_GRACE_MS

    /** A view model whose three tables only load when the test emits into [gate]. */
    private fun slowViewModel(
        scope: SearchScope = SearchScope.ALL,
        gate: kotlinx.coroutines.flow.MutableSharedFlow<Unit>,
    ): SearchViewModel {
        return SearchViewModel(
            kotlinx.coroutines.flow.flow { gate.first(); emit(CHANNELS) },
            kotlinx.coroutines.flow.flow { gate.first(); emit(MOVIES) },
            kotlinx.coroutines.flow.flow { gate.first(); emit(SHOWS) },
            scope,
            otherTablesEmpty = { false },
            matchDispatcher = StandardTestDispatcher(scheduler),
        )
    }

    @Test
    fun `an empty query never shows the searching state even with tables still loading`() = runTest(scheduler) {
        val gate = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val vm = slowViewModel(gate = gate)
        val seen = mutableListOf<SearchUiState>()
        backgroundScope.launch { vm.ui.collect { seen += it } }
        advanceTimeBy(pause + grace + 2_000)
        assertTrue(seen.all { it.phase == SearchPhase.TYPING })
    }

    @Test
    fun `a settled query whose tables have not loaded shows searching after the grace period, then results`() =
        runTest(scheduler) {
            val gate = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
            val vm = slowViewModel(gate = gate)
            val seen = mutableListOf<SearchUiState>()
            backgroundScope.launch { vm.ui.collect { seen += it } }
            runCurrent()
            vm.onQueryChange("co")
            advanceTimeBy(pause + grace - 50)
            assertTrue("no searching text inside the grace period", seen.none { it.phase == SearchPhase.SEARCHING })
            assertEquals(SearchPhase.WAITING, seen.last().phase)
            advanceTimeBy(100)
            assertEquals(SearchPhase.SEARCHING, seen.last().phase)
            assertEquals("co", seen.last().settledQuery)
            assertTrue(seen.last().channels.isEmpty())
            advanceTimeBy(5_000)
            assertEquals(SearchPhase.SEARCHING, seen.last().phase)

            gate.emit(Unit)
            advanceTimeBy(10)
            assertEquals(SearchPhase.RESULTS, seen.last().phase)
            assertEquals(3, seen.last().channelCount)
        }

    @Test
    fun `searching is replaced by no matches and by the empty-library state`() = runTest(scheduler) {
        val gate = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val vm = slowViewModel(gate = gate)
        val seen = mutableListOf<SearchUiState>()
        backgroundScope.launch { vm.ui.collect { seen += it } }
        runCurrent()
        vm.onQueryChange("zebra")
        advanceTimeBy(pause + grace + 100)
        assertEquals(SearchPhase.SEARCHING, seen.last().phase)
        gate.emit(Unit)
        advanceTimeBy(10)
        assertEquals(SearchPhase.NO_MATCHES, seen.last().phase)

        // Empty library: every table loads empty.
        val empty = SearchViewModel(
            kotlinx.coroutines.flow.flow { kotlinx.coroutines.delay(2_000); emit(emptyList()) },
            kotlinx.coroutines.flow.flow { kotlinx.coroutines.delay(2_000); emit(emptyList()) },
            kotlinx.coroutines.flow.flow { kotlinx.coroutines.delay(2_000); emit(emptyList()) },
            matchDispatcher = StandardTestDispatcher(scheduler),
        )
        val seen2 = mutableListOf<SearchUiState>()
        backgroundScope.launch { empty.ui.collect { seen2 += it } }
        runCurrent()
        empty.onQueryChange("co")
        advanceTimeBy(pause + grace + 100)
        assertEquals(SearchPhase.SEARCHING, seen2.last().phase)
        advanceTimeBy(2_500)
        assertEquals(SearchPhase.EMPTY_LIBRARY, seen2.last().phase)
    }

    @Test
    fun `a fast answer never passes through the searching state`() = runTest(scheduler) {
        val vm = viewModel()
        val seen = mutableListOf<SearchUiState>()
        backgroundScope.launch { vm.ui.collect { seen += it } }
        runCurrent()
        vm.onQueryChange("co")
        advanceTimeBy(pause + grace + 1_000)
        assertEquals(SearchPhase.RESULTS, seen.last().phase)
        assertTrue(seen.none { it.phase == SearchPhase.SEARCHING })
        // Typing on within the same scope keeps the previous results meanwhile, never the searching text.
        vm.onQueryChange("cop")
        advanceTimeBy(pause + grace + 1_000)
        assertTrue(seen.none { it.phase == SearchPhase.SEARCHING })
        assertEquals("cop", seen.last().settledQuery)
    }

    @Test
    fun `the reported scope follows a chip press at once, and old results are not shown under it`() =
        runTest(scheduler) {
            val moviesLate = kotlinx.coroutines.flow.MutableSharedFlow<List<SearchPosterRow>>(replay = 1)
            val vm = SearchViewModel(
                channels, moviesLate, shows, SearchScope.CHANNELS,
                matchDispatcher = StandardTestDispatcher(scheduler),
            )
            val seen = mutableListOf<SearchUiState>()
            backgroundScope.launch { vm.ui.collect { seen += it } }
            runCurrent()
            vm.onQueryChange("co")
            advanceTimeBy(pause + 50)
            assertEquals(SearchPhase.RESULTS, seen.last().phase)
            assertEquals(SearchScope.CHANNELS, seen.last().scope)

            vm.selectScope(SearchScope.MOVIES)
            runCurrent()
            val atOnce = seen.last()
            assertEquals(SearchScope.MOVIES, atOnce.scope)
            assertTrue("no channel results under the Movies chip", atOnce.channels.isEmpty())
            assertEquals(SearchPhase.WAITING, atOnce.phase)

            advanceTimeBy(grace + 50)
            assertEquals(SearchPhase.SEARCHING, seen.last().phase)
            assertEquals(SearchScope.MOVIES, seen.last().scope)
            assertEquals("Searching movies", seen.last().scope.searchingText)

            moviesLate.emit(MOVIES)
            advanceTimeBy(10)
            assertEquals(SearchPhase.RESULTS, seen.last().phase)
            assertEquals(2, seen.last().movieCount)
            assertTrue(seen.none { it.scope == SearchScope.MOVIES && it.channels.isNotEmpty() })
        }

    @Test
    fun `before any result the reported scope is the start scope`() = runTest(scheduler) {
        val vm = viewModel(SearchScope.SHOWS)
        assertEquals(SearchScope.SHOWS, vm.ui.first().scope)
    }

    @Test
    fun `searching wording names the scope`() {
        assertEquals("Searching channels, movies and shows", SearchScope.ALL.searchingText)
        assertEquals("Searching channels", SearchScope.CHANNELS.searchingText)
        assertEquals("Searching movies", SearchScope.MOVIES.searchingText)
        assertEquals("Searching shows", SearchScope.SHOWS.searchingText)
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
            SearchPosterRow(id = "m1", name = "The Long Coast", year = "2023", num = 1),
            SearchPosterRow(id = "m2", name = "Copper Sky", year = null, num = 2),
            SearchPosterRow(id = "m3", name = "Harbour Lights", year = "2019", num = 3),
        )
        private val SHOWS = listOf(
            SearchPosterRow(id = "s1", name = "Comet Crew", year = "2022", num = 1),
            SearchPosterRow(id = "s2", name = "Morning Roundup", year = "2021", num = 2),
        )
    }
}
