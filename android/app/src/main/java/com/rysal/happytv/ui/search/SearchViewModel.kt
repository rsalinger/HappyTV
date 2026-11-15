package com.rysal.happytv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.dao.SearchChannelRow
import com.rysal.happytv.data.dao.SearchPosterRow
import com.rysal.happytv.ui.catalog.displayTitle
import com.rysal.happytv.data.dao.SearchPosterUrlRow
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.StreamType
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the overlay is searching; it also names the field's placeholder. */
enum class SearchScope(val label: String, val placeholder: String) {
    ALL("All", "Search channels, movies and shows"),
    CHANNELS("Channels", "Search channels"),
    MOVIES("Movies", "Search movies"),
    SHOWS("Shows", "Search shows"),
    ;

    /** #257: what the result area says while a settled query waits for its results. */
    val searchingText: String get() = when (this) {
        ALL -> "Searching channels, movies and shows"
        CHANNELS -> "Searching channels"
        MOVIES -> "Searching movies"
        SHOWS -> "Searching shows"
    }

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

/** #232: the starred ids per catalog (ids are only unique within one [StreamType]). */
data class SearchFavorites(
    val live: Set<String> = emptySet(),
    val vod: Set<String> = emptySet(),
    val series: Set<String> = emptySet(),
) {
    fun contains(type: StreamType, id: String): Boolean = when (type) {
        StreamType.LIVE -> id in live
        StreamType.VOD -> id in vod
        StreamType.SERIES -> id in series
    }

    companion object {
        fun of(rows: List<Favorite>) = SearchFavorites(
            live = rows.filter { it.streamType == StreamType.LIVE }.mapTo(HashSet()) { it.streamId },
            vod = rows.filter { it.streamType == StreamType.VOD }.mapTo(HashSet()) { it.streamId },
            series = rows.filter { it.streamType == StreamType.SERIES }.mapTo(HashSet()) { it.streamId },
        )
    }
}

/** #232: the one item of a result's hold-OK menu. */
internal fun favoriteMenuLabel(isFavorite: Boolean): String =
    if (isFavorite) "Remove from favorites" else "Add to favorites"

/** design spec §"Screens: search > States". */
enum class SearchPhase {
    TYPING, RESULTS, NO_MATCHES, EMPTY_LIBRARY,

    /** #257: a settled query is still waiting for its results past the grace period: draw the searching text. */
    SEARCHING,

    /** #257: the same wait inside the grace period (or a scope switch whose old results must go): draw nothing. */
    WAITING,
    ;

    /** True while no answer for the current query and scope exists yet. */
    val isPending: Boolean get() = this == SEARCHING || this == WAITING
}

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
    /** #232: every favorite, for the stars on results. */
    favorites: Flow<List<Favorite>> = flowOf(emptyList()),
    /** #232: the shared toggle (`FavoriteDao.toggle`), so a new favorite lands last in its list. */
    private val toggleFavoriteRow: suspend (String, StreamType) -> Unit = { _, _ -> },
    /**
     * Whether every table the [SearchScope] does not show is empty. Called only for a scoped search whose own
     * table is empty and matched nothing; a one-shot check, not a collection.
     */
    private val otherTablesEmpty: suspend (SearchScope) -> Boolean = { true },
    /** Matching walks the whole catalog, so it never runs on Main. Tests pass their own. */
    private val matchDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val scope = MutableStateFlow(startScope)

    /** The rows of the tables [scope] shows; the others stay empty and are never collected. */
    private class Rows(
        val scope: SearchScope,
        val channels: List<SearchChannelRow>,
        val movies: List<SearchPosterRow>,
        val shows: List<SearchPosterRow>,
    )

    private class Inputs(val settled: String, val rows: Rows)

    // #241: a search matches over the tables its scope shows, so the first results never wait for the others.
    // The shared copies are what the other tables are warmed through (see [warmingHiddenTables]): once the first
    // results are out, the hidden tables load in the background and stay loaded while Search is collected, so a
    // later scope switch finds its table ready. When Search stops being collected they drop their rows too
    // (replayExpirationMillis = 0), so the heap is released and a returning search reloads fresh rows.
    private val channelRows = channels.shareIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_WARM_MS, replayExpirationMillis = 0), replay = 1)
    private val movieRows = movies.shareIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_WARM_MS, replayExpirationMillis = 0), replay = 1)
    private val showRows = shows.shareIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_WARM_MS, replayExpirationMillis = 0), replay = 1)

    private val rows: Flow<Rows> = scope.flatMapLatest { s ->
        combine(
            if (s.hides(SearchScope.CHANNELS)) flowOf(emptyList()) else channelRows,
            if (s.hides(SearchScope.MOVIES)) flowOf(emptyList()) else movieRows,
            if (s.hides(SearchScope.SHOWS)) flowOf(emptyList()) else showRows,
        ) { c, m, sh -> Rows(s, c, m, sh) }
    }

    /** The latest finished match; it can lag the query and scope, which [ui] accounts for. */
    private val results = combine(query.debounce(SEARCH_PAUSE_MS), rows, ::Inputs)
        .mapLatest { inputs -> withContext(matchDispatcher) { search(inputs) } }
        .warmingHiddenTables()
        // No timeout of its own: [ui] already waits STATE_STOP_TIMEOUT_MS after the last collector.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SearchUiState(scope = startScope))

    private class Wait(val query: String, val scope: SearchScope, val graceOver: Boolean)

    /**
     * #257: the settled query and scope, and whether the grace period since either changed has run out. The
     * timer restarts on every change, so a fast answer never reaches the searching text.
     */
    private val wait: Flow<Wait> = combine(query.debounce(SEARCH_PAUSE_MS), scope) { q, s -> q to s }
        .transformLatest { (q, s) ->
            emit(Wait(q, s, graceOver = false))
            if (q.isNotBlank()) {
                delay(SEARCHING_GRACE_MS)
                emit(Wait(q, s, graceOver = true))
            }
        }

    /**
     * What the overlay draws. The scope is the selected one at once; results are drawn only while they answer the
     * current scope. A different query within the same scope keeps the previous results until the new ones land
     * (as before), and once the grace period is over without an answer the searching text takes the area.
     */
    val ui = combine(results, wait) { r, w ->
        when {
            r.settledQuery == w.query && r.scope == w.scope -> r
            w.query.isBlank() -> SearchUiState(scope = w.scope, settledQuery = w.query)
            w.graceOver -> SearchUiState(scope = w.scope, settledQuery = w.query, phase = SearchPhase.SEARCHING)
            r.scope == w.scope && r.settledQuery.isNotBlank() -> r
            else -> SearchUiState(scope = w.scope, settledQuery = w.query, phase = SearchPhase.WAITING)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), SearchUiState(scope = startScope))

    /**
     * After the first state is out, collects all three tables in the background for as long as this flow is
     * collected. Nothing here feeds [rows]: a hidden table loading or changing starts no match and no emission.
     */
    private fun Flow<SearchUiState>.warmingHiddenTables(): Flow<SearchUiState> = channelFlow {
        var warming: Job? = null
        collect { state ->
            send(state)
            if (warming == null) {
                warming = launch {
                    launch { channelRows.collect { } }
                    launch { movieRows.collect { } }
                    launch { showRows.collect { } }
                }
            }
        }
    }

    /** Stars on results; kept apart from [ui] so a toggle never re-runs the match. */
    val favoriteState = favorites
        .map { SearchFavorites.of(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), SearchFavorites())

    /** Hold-OK menu choice: adds the favorite, or removes it when it already is one. */
    fun toggleFavorite(id: String, type: StreamType) {
        viewModelScope.launch { toggleFavoriteRow(id, type) }
    }

    private suspend fun search(inputs: Inputs): SearchUiState {
        val startedNs = System.nanoTime()
        val settled = inputs.settled
        val rows = inputs.rows
        val currentScope = rows.scope
        val terms = SearchMatcher.terms(settled)
        val channelMatches = if (currentScope.hides(SearchScope.CHANNELS)) {
            emptyList()
        } else {
            SearchMatcher.filter(rows.channels, terms) { it.name }.sortedWith(SearchOrder.channels)
        }
        val movieMatches = if (currentScope.hides(SearchScope.MOVIES)) {
            emptyList()
        } else {
            SearchMatcher.filter(rows.movies, terms) { it.name }.sortedWith(SearchOrder.posters)
        }
        val showMatches = if (currentScope.hides(SearchScope.SHOWS)) {
            emptyList()
        } else {
            SearchMatcher.filter(rows.shows, terms) { it.name }.sortedWith(SearchOrder.posters)
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

        val total = channelMatches.size + movieMatches.size + showMatches.size
        // "Empty library" is about the whole catalog, but a scoped search only holds its own table: when
        // that is empty and nothing matched, ask once whether the other tables are empty too.
        val libraryEmpty = terms.isNotEmpty() && total == 0 &&
            rows.channels.isEmpty() && rows.movies.isEmpty() && rows.shows.isEmpty() &&
            (currentScope == SearchScope.ALL || otherTablesEmpty(currentScope))

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

        /** #257: how long a settled query may wait before the searching text appears (no flicker on fast answers). */
        const val SEARCHING_GRACE_MS = 300L

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

        /** How long the tables stay subscribed after Search stops being collected (a quick return reuses them). */
        private const val KEEP_WARM_MS = 1_000L

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
                    favorites = Graph.database.favoriteDao().observeAll(),
                    otherTablesEmpty = { scope ->
                        val db = Graph.database
                        (scope == SearchScope.CHANNELS || db.liveStreamDao().count() == 0) &&
                            (scope == SearchScope.MOVIES || db.vodStreamDao().count() == 0) &&
                            (scope == SearchScope.SHOWS || db.seriesStreamDao().count() == 0)
                    },
                    toggleFavoriteRow = { id, type -> Graph.database.favoriteDao().toggle(id, type) },
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
