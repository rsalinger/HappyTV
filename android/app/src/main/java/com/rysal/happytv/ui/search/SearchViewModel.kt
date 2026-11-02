package com.rysal.happytv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.dao.SearchChannelRow
import com.rysal.happytv.data.dao.SearchPosterRow
import com.rysal.happytv.ui.catalog.displayTitle
import com.rysal.happytv.data.dao.SearchPosterUrlRow
import com.rysal.happytv.perf.HtvPerf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** What the overlay is searching; it also names the field's placeholder. */
enum class SearchScope(val label: String, val placeholder: String) {
    ALL("All", "Search channels, movies and shows"),
    CHANNELS("Channels", "Search channels"),
    MOVIES("Movies", "Search movies"),
    SHOWS("Shows", "Search shows"),
    ;

    /** True when this scope excludes [scope]'s row. */
    fun hides(scope: SearchScope): Boolean = this != ALL && this != scope
}

/** One channel result card — "Northbay News" over "Channel 101". */
data class SearchChannelResult(
    val id: String,
    val name: String,
    val numberLabel: String,
    val logoUrl: String? = null,
)

/** One movie or show result poster: title over its year (when it has one). */
data class SearchPosterResult(
    val id: String,
    val name: String,
    val year: String?,
    /** Poster art URL (`stream_icon` / `cover`); null paints only the placeholder. */
    val posterUrl: String? = null,
    /** A leading quality tag taken off the title ("4K"), shown as a badge on the poster. */
    val quality: String? = null,
)

/** design spec §"Screens: search > States". */
enum class SearchPhase { TYPING, RESULTS, NO_MATCHES, EMPTY_LIBRARY }

data class SearchUiState(
    val scope: SearchScope = SearchScope.ALL,
    /** What the results reflect — the query after the 300 ms pause. */
    val settledQuery: String = "",
    val phase: SearchPhase = SearchPhase.TYPING,
    val channels: List<SearchChannelResult> = emptyList(),
    val movies: List<SearchPosterResult> = emptyList(),
    val shows: List<SearchPosterResult> = emptyList(),
    /** True match totals, even when a row only draws the first few. */
    val channelCount: Int = 0,
    val movieCount: Int = 0,
    val showCount: Int = 0,
)

/**
 * The Search overlay's state machine.
 *
 * The field echoes every keystroke locally (it must not wait for a round
 * trip), while the *search* waits out the handoff's 300 ms pause before it
 * matches — [SearchUiState.settledQuery] is what the results answer to.
 *
 * The three catalogs are Room flows, so a refresh that lands while the overlay
 * is open re-runs the current query for free.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    channels: Flow<List<SearchChannelRow>>,
    movies: Flow<List<SearchPosterRow>>,
    shows: Flow<List<SearchPosterRow>>,
    startScope: SearchScope = SearchScope.ALL,
    /** Poster URLs for the movie ids a search draws; looked up, not indexed. */
    private val moviePosterUrls: suspend (List<String>) -> Map<String, String?> = { emptyMap() },
    private val showPosterUrls: suspend (List<String>) -> Map<String, String?> = { emptyMap() },
    /** Logo URLs for the channel ids a search draws (#139). */
    private val channelLogoUrls: suspend (List<String>) -> Map<String, String?> = { emptyMap() },
    /** Matching walks the whole catalog, so it never runs on Main. Tests pass their own. */
    private val matchDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val scope = MutableStateFlow(startScope)

    private class Inputs(
        val settled: String,
        val scope: SearchScope,
        val channels: List<SearchChannelRow>,
        val movies: List<SearchPosterRow>,
        val shows: List<SearchPosterRow>,
    )

    val ui = combine(
        query.debounce(SEARCH_PAUSE_MS),
        scope,
        channels,
        movies,
        shows,
        ::Inputs,
    ).mapLatest { inputs -> withContext(matchDispatcher) { search(inputs) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), SearchUiState())

    private suspend fun search(inputs: Inputs): SearchUiState {
        val startedNs = System.nanoTime()
        val settled = inputs.settled
        val currentScope = inputs.scope
        val terms = SearchMatcher.terms(settled)
        val channelMatches = if (currentScope.hides(SearchScope.CHANNELS)) {
            emptyList()
        } else {
            SearchMatcher.filter(inputs.channels, terms) { it.name }
        }
        val movieMatches = if (currentScope.hides(SearchScope.MOVIES)) {
            emptyList()
        } else {
            SearchMatcher.filter(inputs.movies, terms) { it.name }
        }
        val showMatches = if (currentScope.hides(SearchScope.SHOWS)) {
            emptyList()
        } else {
            SearchMatcher.filter(inputs.shows, terms) { it.name }
        }

        // All three groups share one screen, so each is cut short; a single type
        // has the whole screen and gets far more (the "see all" path).
        val cap = if (currentScope == SearchScope.ALL) MAX_RESULTS_ALL else MAX_RESULTS_SCOPED
        val shownChannels = channelMatches.take(cap)
        val shownMovies = movieMatches.take(cap)
        val shownShows = showMatches.take(cap)
        val channelLogos = if (shownChannels.isEmpty()) emptyMap() else channelLogoUrls(shownChannels.map { it.id })
        val moviePosters = if (shownMovies.isEmpty()) emptyMap() else moviePosterUrls(shownMovies.map { it.id })
        val showPosters = if (shownShows.isEmpty()) emptyMap() else showPosterUrls(shownShows.map { it.id })

        val libraryEmpty = inputs.channels.isEmpty() && inputs.movies.isEmpty() && inputs.shows.isEmpty()
        val total = channelMatches.size + movieMatches.size + showMatches.size

        val state = SearchUiState(
            scope = currentScope,
            settledQuery = settled,
            phase = when {
                terms.isEmpty() -> SearchPhase.TYPING
                libraryEmpty -> SearchPhase.EMPTY_LIBRARY
                total == 0 -> SearchPhase.NO_MATCHES
                else -> SearchPhase.RESULTS
            },
            channels = shownChannels.map { it.toResult(channelLogos[it.id]) },
            movies = shownMovies.map { it.toResult(moviePosters[it.id]) },
            shows = shownShows.map { it.toResult(showPosters[it.id]) },
            channelCount = channelMatches.size,
            movieCount = movieMatches.size,
            showCount = showMatches.size,
        )
        if (terms.isEmpty()) {
            HtvPerf.cancel(HtvPerf.SEARCH_RESULTS)
        } else {
            // Keystroke -> results (includes the 300 ms pause); match= is the work itself.
            val matchMs = (System.nanoTime() - startedNs) / 1_000_000L
            HtvPerf.end(
                HtvPerf.SEARCH_RESULTS,
                "match=${matchMs}ms len=${terms.sumOf { it.length }} " +
                    "ch=${channelMatches.size} mv=${movieMatches.size} sh=${showMatches.size}",
            )
        }
        return state
    }

    /** Every keystroke — the field echoes immediately, the search waits. */
    fun onQueryChange(value: String) {
        if (value.isNotBlank()) HtvPerf.start(HtvPerf.SEARCH_RESULTS) else HtvPerf.cancel(HtvPerf.SEARCH_RESULTS)
        query.value = value
    }

    fun selectScope(newScope: SearchScope) {
        scope.value = newScope
    }

    companion object {
        /** design spec §"Screens: search > Behavior": 300 ms pause before searching. */
        const val SEARCH_PAUSE_MS = 300L

        /**
         * Rows drawn per type while the scope is All (three groups share the
         * screen): 24 is six rows of four channel cards or three rows of eight
         * posters, so a short list is never cut mid-row. Beyond it the group
         * offers "See all", which switches the scope to that type.
         */
        const val MAX_RESULTS_ALL = 24

        /**
         * Per-type ceiling once the scope is a single type: 240 = 60 channel
         * rows or 30 poster rows, drawn lazily. Keeps a one-letter query from
         * holding thousands of result objects; the heading's count stays true.
         */
        const val MAX_RESULTS_SCOPED = 240

        /** Four channel cards per row (design spec §"Screens: search > Layout"). */
        const val CHANNELS_PER_ROW = 4

        /** Eight posters per row (design spec §"Screens: search > Layout"). */
        const val POSTERS_PER_ROW = 8

        private const val STATE_STOP_TIMEOUT_MS = 5_000L

        /** Production wiring: the three catalog index flows from Room. */
        fun factory(startScope: SearchScope = SearchScope.ALL): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(
                    channels = Graph.database.liveStreamDao().observeSearchRows(),
                    movies = Graph.database.vodStreamDao().observeSearchRows(),
                    shows = Graph.database.seriesStreamDao().observeSearchRows(),
                    startScope = startScope,
                    moviePosterUrls = { ids -> Graph.database.vodStreamDao().searchPosterUrls(ids).toUrlMap() },
                    showPosterUrls = { ids -> Graph.database.seriesStreamDao().searchPosterUrls(ids).toUrlMap() },
                    channelLogoUrls = { ids ->
                        Graph.database.liveStreamDao().searchLogoUrls(ids).associate { it.id to it.logoUrl }
                    },
                ) as T
            }
    }
}

private fun SearchChannelRow.toResult(logoUrl: String?) = SearchChannelResult(
    id = id,
    name = name,
    numberLabel = if (num != null) "Channel $num" else "Channel",
    logoUrl = logoUrl,
)

/** Display only: the quality tag moves to a badge; the row keeps its raw name for matching. */
private fun SearchPosterRow.toResult(posterUrl: String?): SearchPosterResult {
    val shown = displayTitle(name, year)
    return SearchPosterResult(id = id, name = shown.title, year = shown.year, posterUrl = posterUrl, quality = shown.quality)
}

private fun List<SearchPosterUrlRow>.toUrlMap(): Map<String, String?> = associate { it.id to it.posterUrl }
