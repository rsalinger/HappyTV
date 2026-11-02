package com.rysal.happytv.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.dao.CategoryDao
import com.rysal.happytv.data.dao.FavoriteDao
import com.rysal.happytv.data.dao.PosterGridRow
import com.rysal.happytv.data.dao.ContinueRow
import com.rysal.happytv.data.dao.WatchProgressDao
import com.rysal.happytv.data.entity.WatchProgressPolicy
import com.rysal.happytv.data.dao.SeriesStreamDao
import com.rysal.happytv.data.dao.VodStreamDao
import com.rysal.happytv.data.dao.toCountMap
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.SeriesStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import com.rysal.happytv.data.network.JsonCoerce
import com.rysal.happytv.data.network.RefreshState
import com.rysal.happytv.data.network.SeriesInfoDto
import com.rysal.happytv.data.network.XtreamRepository
import kotlinx.coroutines.withContext
import com.rysal.happytv.data.network.backdropPaths
import com.rysal.happytv.data.network.resolvePosterUrl
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.data.network.toDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Movies and Shows — one ViewModel for the shared
 * "categories -> posters -> details" flow (design spec "Screens: movies and
 * shows"). Shows use the same class with [StreamType.SERIES].
 *
 * The three screens take different constructor arguments of the same class:
 *  - categories screen: `categoryId = null, streamId = null`
 *  - poster grid: `categoryId = "<id>", or null for "All movies"`
 *  - details: `streamId = "<id>"`
 *
 * Everything it exposes comes from the schema's DAOs:
 * [CategoryDao.observeVisible] (filtered by `isHidden = 0`),
 * [VodStreamDao.observeGridWindow] / [SeriesStreamDao.observeGridWindow] (the
 * poster grid's paged window, widened by [loadMorePosters]), and
 * [FavoriteDao] for the star button and the poster badges.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(
    private val categoryDao: CategoryDao,
    private val vodStreamDao: VodStreamDao,
    private val seriesStreamDao: SeriesStreamDao,
    private val favoriteDao: FavoriteDao,
    private val streamType: StreamType,
    private val categoryId: String? = null,
    private val streamId: String? = null,
    lastRefresh: Flow<Long> = flow { emit(0L) },
    /**
     * This type's refresh lifecycle, observed from
     * [com.rysal.happytv.data.network.RefreshCoordinator.progress] —
     * `Idle` before any refresh has ever run, `Loading(n/3)` while the
     * tile is in flight, `Done(channels, ms)` when the network call + Room
     * write both settled, or `Error(msg)` if anything failed. Default is a
     * never-Loading flow so unit tests that don't care about refresh state
     * still construct the ViewModel cleanly.
     */
    refreshProgress: Flow<RefreshState> = flow { emit(RefreshState.Idle) },
    /**
     * Session accessor, same shape as
     * [com.rysal.happytv.data.guide.GuideRepository.session]. Null before
     * sign-in or in tests without a panel: the detail page still renders
     * with the list-row fields it has, just without `get_vod_info`'s rich
     * metadata (F-50). A failure in the call is treated the same way — the
     * detail page must never crash because a panel flaked.
     */
    private val session: suspend () -> XtreamRepository? = { null },
    /**
     * The one-screen Movies / Shows rail (#72): when set, the screen opens on the last category
     * used here (the flow's first value), else Favorites if it has items, else Recently added.
     * Null keeps the constructor's [categoryId] (details, and the unit tests).
     */
    private val lastCategory: Flow<String?>? = null,
    private val saveLastCategory: suspend (String) -> Unit = {},
    /** Settings "Keep categories open" - the rail is then a permanent pane. */
    keepCategoriesOpenFlow: Flow<Boolean> = flow { emit(false) },
    /**
     * Where the opening work (which category to open on, the grid's mapping) runs. The screen is
     * composing on the main thread at the same moment, so every hop back to it costs a frame
     * or more: this keeps the work off it. Tests leave the default and stay deterministic.
     */
    private val workDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    /**
     * Watch progress (#78): the Continue watching row, and Resume / Start over on details.
     * Null (tests that do not care) means nothing was ever watched.
     */
    private val watchProgressDao: WatchProgressDao? = null,
) : ViewModel() {

    private val isMovies: Boolean get() = streamType == StreamType.VOD

    /** Visible categories for this catalog (`isHidden = 0`). */
    val categories: StateFlow<List<Category>> = categoryDao.observeVisible(streamType)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every category including hidden ones, (the selection label reads names from it). */
    val allCategories: StateFlow<List<Category>> = categoryDao.observeAll(streamType)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** `{categoryId → item count}` — the cells' "1,204 movies" line. */
    val categoryCounts: StateFlow<Map<String, Int>> =
        (if (isMovies) vodStreamDao.observeCounts() else seriesStreamDao.observeCounts())
            .toCountMap()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Total for the headers ("8,214 movies · 63 categories"). */
    val totalItems: StateFlow<Int> =
        (if (isMovies) vodStreamDao.observeCount() else seriesStreamDao.observeCount())
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Titles in progress; null before the first read tells "none" from "not known yet". */
    private val continueCountOrNull: StateFlow<Int?> = continueRows(CONTINUE_CAP)
        .map<List<ContinueRow>, Int?> { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** How many titles Continue watching holds; the rail lists the row only when this is above 0. */
    val continueCount: StateFlow<Int> = continueCountOrNull
        .map { it ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** `{id → favourited}` for the poster badges and the details star. */
    val favoriteIds: StateFlow<Set<String>> = favoriteDao.observeForType(streamType)
        .map { rows -> rows.map { it.streamId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** The category shown and how many of its rows are paged in so far (F-55), as one value. */
    private data class Window(val id: String, val limit: Int)

    private val window = MutableStateFlow(
        Window(id = categoryId ?: ALL_CATEGORY_ID, limit = PAGE_SIZE),
    )

    /** The selected rail row: a provider category id or one of the pseudo ids. */
    val selectedCategory: StateFlow<String> = window
        .map { it.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, categoryId ?: ALL_CATEGORY_ID)

    /** Settings "Keep categories open". */
    val keepCategoriesOpen: StateFlow<Boolean> = keepCategoriesOpenFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True while the screen is still working out which category to open on. */
    private val restoring = MutableStateFlow(lastCategory != null)
    val restoringSelection: StateFlow<Boolean> = restoring.asStateFlow()

    init {
        if (lastCategory != null) {
            viewModelScope.launch(workDispatcher) {
                // Three independent reads, asked for together.
                val (last, visible, hasFavorites) = kotlinx.coroutines.coroutineScope {
                    val lastRead = async { lastCategory.first() }
                    val visibleRead = async { categoryDao.observeVisible(streamType).first() }
                    val favoritesRead = async { favoriteDao.observeForType(streamType).first().isNotEmpty() }
                    Triple(lastRead.await(), visibleRead.await(), favoritesRead.await())
                }
                // Only asked when it matters: the remembered row was Continue watching.
                val hasContinue = last == CONTINUE_CATEGORY_ID && continueRows(1).first().isNotEmpty()
                var opening = initialCategoryId(last, visible.map { it.id }, hasFavorites, hasContinue)
                // A provider that stamps nothing has no "recently added": open on the whole catalog.
                if (opening == RECENT_CATEGORY_ID) {
                    val stamped = if (isMovies) vodStreamDao.hasAddedStamps() else seriesStreamDao.hasModifiedStamps()
                    if (!stamped) opening = ALL_CATEGORY_ID
                }
                select(opening, remember = false)
                restoring.value = false
            }
        }
    }

    init {
        // Continue watching empties itself (the last title finished or was removed): leave its
        // grid for the whole catalog instead of sitting on an empty row that is no longer listed.
        viewModelScope.launch(workDispatcher) {
            continueCountOrNull.filterNotNull().collect { count ->
                if (count == 0 && window.value.id == CONTINUE_CATEGORY_ID) select(ALL_CATEGORY_ID, remember = true)
            }
        }
    }

    /**
     * Rail selection: show [id]'s rows from the first page, and remember it for the next visit.
     * `catalog.category` times this call to the first poster row drawn.
     */
    fun selectCategory(id: String) = select(id, remember = true)

    private fun select(id: String, remember: Boolean) {
        if (window.value.id != id) {
            if (remember) HtvPerf.start(HtvPerf.CATALOG_CATEGORY)
            window.value = Window(id = id, limit = PAGE_SIZE)
        }
        if (remember && id != PICK_CATEGORY_ID) {
            viewModelScope.launch { saveLastCategory(id) }
        }
    }

    /**
     * How many rows this grid has in total — the header's "31,598 movies",
     * the same number the category cell shows. Never the paged-in window
     * size (F-55's wrong-data half).
     */
    val itemsTotal: StateFlow<Int> = selectedCategory.flatMapLatest { id ->
        when (id) {
            PICK_CATEGORY_ID -> flow { emit(0) }
            FAVORITES_CATEGORY_ID -> favoriteIds.map { it.size }
            ALL_CATEGORY_ID -> totalItems
            RECENT_CATEGORY_ID -> totalItems.map { minOf(it, RECENT_CAP) }
            CONTINUE_CATEGORY_ID -> continueCount
            else -> categoryCounts.map { it[id] ?: 0 }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * The poster grid: the rows, which category they belong to, and whether that is the category
     * now selected. One value, so a screen can never draw the old category's rows under the new
     * selection (or call them loaded).
     */
    val grid: StateFlow<CatalogGrid> =
        windowRows()
            // The favorites flow itself, not [favoriteIds]: that one starts as "none", and a
            // grid built from it would first draw every star off.
            .combine(favoriteDao.observeForType(streamType).map { rows -> rows.map { it.streamId }.toSet() }) { (id, rows), favorites ->
                id to rows.map { toItem(it, favorites) }
            }
            .combine(selectedCategory) { (id, items), selected -> CatalogGrid(id, items, loaded = id == selected) }
            .flowOn(workDispatcher)
            .stateIn(viewModelScope, SharingStarted.Eagerly, CatalogGrid(null, emptyList(), loaded = false))

    /** The poster grid's rows for the selected category. */
    val items: StateFlow<List<CatalogItem>> = grid
        .map { it.items }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Drives the poster grid's placeholder cards until the selected category's rows arrive. */
    val itemsLoaded: StateFlow<Boolean> = grid
        .map { it.loaded }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * F-60 (Movies / Shows): the categories screen's cold-start loading
     * gate. Latches to `true` the first time the refresh for this type
     * settles, i.e. its [RefreshState] becomes [RefreshState.Done] or
     * [RefreshState.Error] — anything except the in-flight `Loading` and
     * the not-yet-started `Idle`. The skeleton cells stay up until the
     * network call + Room write have both finished; once they have, the
     * real "0 movies · 0 categories" (or populated) state paints.
     *
     * F-64: the previous version required a **non-empty** emission from
     * the categories flow to latch, but a panel that legitimately has no
     * VOD (e.g. a provider returning `[]` for `get_vod_categories` today)
     * never satisfies that — the Movies/Shows header stayed on "Loading…"
     * forever even though the refresh completed and stored 0 rows.
     * Latching on the [RefreshState] keeps the F-60 no-flash guarantee
     * (the latch holds while `Loading` is in flight) and lets a real empty
     * catalog reach the empty state.
     */
    val categoriesLoaded: StateFlow<Boolean> = refreshProgress
        .map { it is RefreshState.Done || it is RefreshState.Error }
        .scan(false) { ever, current -> ever || current }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** One `get_series_info` call, shared by the details page's art and its episode list (#74). */
    private sealed interface InfoLoad {
        data object Idle : InfoLoad
        data object Loading : InfoLoad
        data object Failed : InfoLoad
        data class Done(val info: SeriesInfoDto, val seasons: List<SeasonItem>) : InfoLoad
    }

    private val infoRetry = MutableStateFlow(0)

    private val infoLoad: StateFlow<InfoLoad> = run {
        val id = streamId
        if (isMovies || id == null) {
            MutableStateFlow<InfoLoad>(InfoLoad.Idle)
        } else {
            // Details open → episodes painted (HtvPerf show.episodes): the screen ends the mark.
            HtvPerf.start(HtvPerf.SHOW_EPISODES)
            infoRetry.flatMapLatest {
                flow<InfoLoad> {
                    emit(InfoLoad.Loading)
                    val info = runCatching { session()?.getSeriesInfo(id) }.getOrNull()
                    if (info == null) {
                        HtvPerf.cancel(HtvPerf.SHOW_EPISODES)
                        emit(InfoLoad.Failed)
                    } else {
                        // Flattening a long show's JSON is not main-thread work.
                        val seasons = withContext(workDispatcher) {
                            // The provider's name (often "Name (2023)") and the grid's display name.
                            val gridName = runCatching { seriesStreamDao.getById(id)?.name }.getOrNull()
                            info.toSeasons(listOf(info.info?.name, gridName, gridName?.let { splitTitleYear(it).first }))
                        }
                        emit(InfoLoad.Done(info, seasons))
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, InfoLoad.Loading)
        }
    }

    /** The show's seasons and episodes (#74): Loading until `get_series_info` lands, Failed with a retry. */
    val episodes: StateFlow<EpisodesState> = infoLoad
        .map { load ->
            when (load) {
                InfoLoad.Idle -> EpisodesState.Idle
                InfoLoad.Loading -> EpisodesState.Loading
                InfoLoad.Failed -> EpisodesState.Failed
                is InfoLoad.Done -> EpisodesState.Loaded(load.seasons)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, if (isMovies || streamId == null) EpisodesState.Idle else EpisodesState.Loading)

    /** Asks for the episodes again (the error state's retry). */
    fun retryEpisodes() {
        infoRetry.value += 1
    }

    /** `{episode id → progress mark}` for the episode list: watched, or how far through (#78 rows). */
    val episodeMarks: StateFlow<Map<String, EpisodeMark>> = run {
        val dao = watchProgressDao
        val id = streamId
        if (dao == null || id == null || isMovies) flowOf(emptyMap())
        else dao.observeEpisodes(id).map { marksOf(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The details page's row, or null while it loads (or if it is gone). */
    val detail: StateFlow<CatalogDetail?> =
        flatMapState(streamId)
            .flatMapLatest { id ->
                if (id == null) flow<CatalogDetail?> { emit(null) } else detailFor(id)
            }
            .combine(favoriteIds) { detail, favorites ->
                detail?.copy(isFavorite = detail.id in favorites)
            }
            .combine(resumePoint()) { detail, resume -> detail?.copy(resume = resume) }
            .combine(episodes) { detail, state ->
                if (detail == null || !detail.isSeries || state !is EpisodesState.Loaded) return@combine detail
                // A show whose call succeeded with no episodes has nothing to play; otherwise the
                // primary action is Resume (saved) or Play of the first episode, "Play S1 E1".
                val first = firstEpisode(state.seasons)
                when {
                    first == null -> detail.copy(hasEpisodes = false, resume = null)
                    detail.resume == null -> detail.copy(resume = ResumePoint(0L, first.id, first.label))
                    else -> detail
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Where this title's details can pick up from, or null when nothing (or not enough) was watched. */
    private fun resumePoint(): Flow<ResumePoint?> {
        val dao = watchProgressDao
        val id = streamId
        if (dao == null || id == null) return flowOf(null)
        return if (isMovies) {
            dao.observeMovie(id).map { row -> row?.let { ResumePoint(it.positionMillis, null, null) } }
        } else {
            dao.observeLatestEpisode(id).map { row ->
                row?.let { ResumePoint(it.positionMillis, it.streamId, episodeLabel(it.seasonNumber, it.episodeNumber)) }
            }
        }
    }

    /** When this catalog was last refreshed — "About this category" needs it. */
    val lastRefreshMillis: StateFlow<Long> = lastRefresh
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** The star on a poster or the details page — persisted in Room. */
    fun toggleFavorite(id: String) {
        viewModelScope.launch { favoriteDao.toggle(id, streamType) }
    }

    /**
     * The poster grid is nearing the end of its paged-in window (F-55) —
     * widen it by one more [PAGE_SIZE] page. No-op when the window came back
     * short (already at the end of the data) and on the favorites grid, which
     * is bounded by the favorites table and reads whole rows.
     */
    fun loadMorePosters() {
        val current = window.value
        if (current.id == FAVORITES_CATEGORY_ID || current.id == PICK_CATEGORY_ID) return
        // Recently added is one fixed page of the newest RECENT_CAP: nothing more to ask for.
        if (current.id == RECENT_CATEGORY_ID || current.id == CONTINUE_CATEGORY_ID) return
        if (items.value.size < current.limit) return
        window.compareAndSet(current, current.copy(limit = current.limit + PAGE_SIZE))
    }

    /** Long-press a category cell: "Hide category" / "Show category". */
    fun toggleCategoryHidden(id: String) {
        viewModelScope.launch { categoryDao.toggleHidden(id, streamType) }
    }

    private fun <T> flatMapState(value: T): StateFlow<T> =
        kotlinx.coroutines.flow.MutableStateFlow(value)

    /** The rows paged in so far — one window query, re-run as the window grows. */
    private fun windowRows(): Flow<Pair<String, List<Any>>> =
        window.flatMapLatest { (id, limit) -> streamsFor(id, limit).map { id to it } }

    private fun streamsFor(id: String?, limit: Int): Flow<List<Any>> = when {
        id == PICK_CATEGORY_ID -> flow { emit(emptyList<Any>()) }
        id == RECENT_CATEGORY_ID -> recentRows(RECENT_CAP)
        id == CONTINUE_CATEGORY_ID -> continueRows(CONTINUE_CAP)
        id == FAVORITES_CATEGORY_ID -> favoriteIds.flatMapLatest { favorites ->
            if (favorites.isEmpty()) {
                flow { emit(emptyList<Any>()) }
            } else if (isMovies) {
                vodStreamDao.observeByIds(favorites.toList())
            } else {
                seriesStreamDao.observeByIds(favorites.toList())
            }
        }
        isMovies && id == null -> vodStreamDao.observeGridWindow(limit)
        isMovies && id == ALL_CATEGORY_ID -> vodStreamDao.observeGridWindow(limit)
        isMovies && id != null -> vodStreamDao.observeGridWindowByCategory(id, limit)
        id == null -> seriesStreamDao.observeGridWindow(limit)
        id == ALL_CATEGORY_ID -> seriesStreamDao.observeGridWindow(limit)
        else -> seriesStreamDao.observeGridWindowByCategory(id, limit)
    }

    /**
     * "Recently added": the newest [limit] by `added` (shows: `lastModified`), straight off the
     * `added` / `lastModified` index (v5), so only [limit] rows are read however big the catalog.
     */
    private fun recentRows(limit: Int): Flow<List<Any>> =
        if (isMovies) vodStreamDao.observeRecentlyAdded(limit) else seriesStreamDao.observeRecentlyAdded(limit)

    /** Titles in progress, most recent first; shows are one row each, at their latest episode. */
    private fun continueRows(limit: Int): Flow<List<ContinueRow>> = when {
        watchProgressDao == null -> flowOf(emptyList())
        isMovies -> watchProgressDao.observeContinueMovies(limit)
        else -> watchProgressDao.observeContinueShows(limit)
    }

    /** Long-press "Remove from Continue watching": forget the movie, or every episode of the show. */
    fun removeFromContinue(id: String) {
        val dao = watchProgressDao ?: return
        viewModelScope.launch { if (isMovies) dao.deleteMovie(id) else dao.deleteSeries(id) }
    }

    private fun toItem(row: Any, favorites: Set<String>): CatalogItem = when (row) {
        is ContinueRow -> posterItem(row.id, row.name, row.year, row.id in favorites, row.posterUrl?.takeIf { it.isNotBlank() })
            .copy(
                progressFraction = if (row.durationMillis > 0L && row.positionMillis > 0L) {
                    (row.positionMillis.toFloat() / row.durationMillis).coerceIn(0f, 1f)
                } else {
                    null
                },
                episodeLabel = episodeLabel(row.seasonNumber, row.episodeNumber)
                    ?.let { if (row.positionMillis <= 0L) "$it · Up next" else it },
            )
        is PosterGridRow -> posterItem(row.id, row.name, row.year, row.id in favorites, row.posterUrl?.takeIf { it.isNotBlank() })
        is VodStream -> posterItem(row.id, row.name, null, row.id in favorites, row.posterUrl)
        is SeriesStream -> posterItem(
            row.id, row.name, row.releaseDate?.take(4)?.takeIf { it.length == 4 }, row.id in favorites, row.posterUrl,
        )
        else -> CatalogItem(id = "", name = "")
    }

    /**
     * A grid card. The year is shown once: a provider title ending "(2004)" gives that year to the
     * second line (when the data had none) and loses it from the title line.
     */
    private fun posterItem(id: String, name: String, year: String?, favorite: Boolean, posterUrl: String?): CatalogItem {
        val shown = displayTitle(name, year)
        return CatalogItem(
            id = id, name = shown.title, year = shown.year, isFavorite = favorite, posterUrl = posterUrl, quality = shown.quality,
        )
    }

    private fun detailFor(id: String): Flow<CatalogDetail?> = flow {
        val row: Any? = if (isMovies) vodStreamDao.getById(id) else seriesStreamDao.getById(id)
        val base = when (row) {
            is VodStream -> CatalogDetail(
                id = row.id,
                name = splitTitleYear(splitQuality(row.name).second).first,
                isSeries = false,
                year = splitTitleYear(splitQuality(row.name).second).second,
                runtimeLabel = null,
                ageLabel = null,
                ratingLabel = row.rating?.let(::ratingText),
                genres = emptyList(),
                plot = null,
                cast = emptyList(),
                // Fallback only: `get_vod_streams`' `stream_icon` is usually a
                // poster but `get_vod_info`'s `info.movie_image` is the real
                // one (and the only source when `stream_icon` is missing).
                posterUrl = row.streamIcon,
                hasEpisodes = false,
            )
            is SeriesStream -> CatalogDetail(
                id = row.id,
                // The year is shown once, in the details line: not also in the title.
                name = splitTitleYear(splitQuality(row.name).second).first,
                isSeries = true,
                year = row.releaseDate?.take(4)?.takeIf { it.length == 4 } ?: splitTitleYear(splitQuality(row.name).second).second,
                runtimeLabel = null,
                ageLabel = null,
                ratingLabel = row.rating?.let(::ratingText),
                genres = row.genre.orEmpty().split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                plot = row.plot?.takeIf { it.isNotBlank() },
                cast = row.cast.orEmpty().split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                // Until get_series_info says otherwise (#74), a show we can see has episodes.
                hasEpisodes = true,
                // #68: the same `cover` the grid card paints, plus the stored
                // `backdrop_path`. The row used to be mapped without either.
                posterUrl = row.posterUrl,
                backdropUrl = row.backdropUrl,
            )
            else -> null
        }
        if (base == null) {
            emit(null)
            return@flow
        }
        // F-50 (rework): the movie list endpoint carries no plot, genre or cast
        // — those live in the `info` block of `get_vod_info` (NOT `movie_data`,
        // which only repeats stream ids + `container_extension`). Enrich the
        // row with the detail call; on a network failure or an `info`-less
        // response, the row itself is what the user sees (the handoff's
        // designed "missing info" state), never a crash.
        if (isMovies) {
            // `info` is captured here so the log line below reflects the actual
            // panel payload, not whether `toDetail()` returned a `VodDetail`.
            // An `info`-less response ("{}") is a panel-acceptable empty state
            // and falls through; a network failure is silently caught by
            // `runCatching`. Either way, F-50 must degrade gracefully.
            val (detail, hadInfo) = runCatching {
                val res = session()?.getVodInfo(id)
                res?.toDetail() to (res?.info != null)
            }.getOrElse { null to false } ?: (null to false)
            if (detail != null) {
                emit(
                    base.copy(
                        year = detail.releasedate?.take(4) ?: base.year,
                        runtimeLabel = formatRuntime(detail.duration) ?: base.runtimeLabel,
                        ageLabel = detail.age ?: base.ageLabel,
                        ratingLabel = JsonCoerce.toDoubleOrNull(detail.rating)?.let(::ratingText)
                            ?: base.ratingLabel,
                        genres = detail.genres.ifEmpty { base.genres },
                        plot = detail.overview ?: base.plot,
                        cast = detail.cast.ifEmpty { base.cast },
                        posterUrl = detail.poster ?: base.posterUrl,
                    ),
                )
                return@flow
            }
            // Visibility: a future "F-50 regressed" report should land with a
            // logcat trace showing why the enrich ran on the row fallback.
            android.util.Log.i(
                "CatalogF50",
                "vod_id=$id enriched=${if (hadInfo) "info-block" else "no-info"}; falling back to row",
            )
        }
        if (!isMovies) {
            // Paint the row's art immediately, then upgrade from `get_series_info`.
            emit(base)
            val info = (infoLoad.first { it is InfoLoad.Done || it is InfoLoad.Failed } as? InfoLoad.Done)?.info?.info
            if (info != null) {
                val enriched = base.copy(
                    // The grid's cover wins so details match the card; the
                    // detail call only fills a gap.
                    posterUrl = base.posterUrl ?: info.cover.resolvePosterUrl(),
                    backdropUrl = info.backdropPaths().firstOrNull() ?: base.backdropUrl,
                )
                if (enriched != base) emit(enriched)
            }
            return@flow
        }
        emit(base)
    }

    class Factory(
        private val streamType: StreamType,
        private val categoryId: String? = null,
        private val streamId: String? = null,
        /** True for the one-screen grid with its rail (#72): restores the last category. */
        private val withRail: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == CatalogViewModel::class.java) {
                "Factory only knows about CatalogViewModel; got $modelClass"
            }
            val db = Graph.database
            return CatalogViewModel(
                categoryDao = db.categoryDao(),
                vodStreamDao = db.vodStreamDao(),
                seriesStreamDao = db.seriesStreamDao(),
                favoriteDao = db.favoriteDao(),
                streamType = streamType,
                categoryId = categoryId,
                streamId = streamId,
                lastRefresh = Graph.settings.settings.map { settings ->
                    when (streamType) {
                        StreamType.LIVE -> settings.lastLiveRefreshUtcMillis
                        StreamType.VOD -> settings.lastVodRefreshUtcMillis
                        StreamType.SERIES -> settings.lastSeriesRefreshUtcMillis
                    }
                },
                // F-64: the categories-screen loading gate flips to true
                // when this type's refresh has settled (Done or Error) —
                // see [CatalogViewModel.categoriesLoaded] for why a Room
                // first-emission latch was wrong (it latched on the empty
                // initial contents even before any refresh had run).
                refreshProgress = Graph.refreshCoordinator.progress
                    .map { progress -> progress[streamType] },
                // F-50: the movie detail page reads `get_vod_info`'s `info`
                // block (plot/genre/cast/poster/etc). Session lookup goes
                // through RefreshCoordinator, same as GuideRepository — the
                // detail page never builds its own client wiring.
                session = { Graph.refreshCoordinator.currentRepository() },
                lastCategory = if (withRail) Graph.settings.lastCatalogCategory(streamType) else null,
                saveLastCategory = { Graph.settings.setLastCatalogCategory(streamType, it) },
                keepCategoriesOpenFlow = Graph.settings.settings.map { it.keepCategoriesOpen },
                workDispatcher = kotlinx.coroutines.Dispatchers.Default,
                watchProgressDao = db.watchProgressDao(),
            ) as T
        }
    }

    companion object {
        /**
         * Rows per paging step — 40 poster rows on the 6-across grid (F-55).
         * The window grows by one page each time the grid approaches its end,
         * so a 31 598-movie panel costs one page of memory until the user
         * actually browses deeper. The lazy grid itself only composes the
         * visible rows.
         */
        const val PAGE_SIZE = 240

        /** Pseudo category id for the favorites poster grid. */
        const val FAVORITES_CATEGORY_ID = "__favorites__"

        /** Pseudo category id for "All movies" / "All shows". */
        const val ALL_CATEGORY_ID = "__all__"

        /** Pseudo category id for "Recently added". */
        const val RECENT_CATEGORY_ID = "__recent__"

        /** Pseudo category id for "Continue watching" (#78). */
        const val CONTINUE_CATEGORY_ID = "__continue__"

        /** Continue watching holds at most this many titles. */
        const val CONTINUE_CAP = 60

        /** Nothing picked: the grid draws nothing. */
        const val PICK_CATEGORY_ID = "__pick__"

        /** "Recently added" shows at most this many of the newest items. */
        const val RECENT_CAP = 480


        /**
         * The category the screen opens on (#72): the remembered one if it still exists, else
         * Favorites when it has items, else Recently added.
         */
        fun initialCategoryId(
            last: String?,
            visibleCategoryIds: List<String>,
            hasFavorites: Boolean,
            hasContinue: Boolean = false,
        ): String = when {
            last == CONTINUE_CATEGORY_ID && hasContinue -> CONTINUE_CATEGORY_ID
            last == CONTINUE_CATEGORY_ID -> if (hasFavorites) FAVORITES_CATEGORY_ID else RECENT_CATEGORY_ID
            last == FAVORITES_CATEGORY_ID && hasFavorites -> FAVORITES_CATEGORY_ID
            last == ALL_CATEGORY_ID || last == RECENT_CATEGORY_ID -> last
            last != null && last in visibleCategoryIds -> last
            hasFavorites -> FAVORITES_CATEGORY_ID
            else -> RECENT_CATEGORY_ID
        }

        /** "S2 E3", or null when either number is unknown. */
        fun episodeLabel(season: Int?, episode: Int?): String? =
            if (season != null && episode != null) "S$season E$episode" else null

        /** The unit word for a catalog: "movies" / "shows". */
        fun unitFor(type: StreamType): String =
            if (type == StreamType.VOD) "movies" else "shows"
    }
}

/** The poster grid's state (see [CatalogViewModel.grid]). */
data class CatalogGrid(val categoryId: String?, val items: List<CatalogItem>, val loaded: Boolean)

/** One poster-grid row, whichever catalog it came from. */
data class CatalogItem(
    val id: String,
    val name: String,
    val year: String? = null,
    val isFavorite: Boolean = false,
    /** "S2 E5" — only once the show has progress to show (v1.1 data). */
    val episodeLabel: String? = null,
    /** How far through, 0..1, on a Continue watching card; null elsewhere. */
    val progressFraction: Float? = null,
    /**
     * Poster artwork URL — `stream_icon`/`cover` via [PosterGridRow]. Wired
     * here for F-49's poster renderer; blank URLs collapse to null so callers
     * can paint the placeholder art instead of an empty image.
     */
    val posterUrl: String? = null,
    /** A quality tag the provider put in front of the title ("4K"), shown as a badge on the poster. */
    val quality: String? = null,
)

/** The details page's row, whichever catalog it came from. */
data class CatalogDetail(
    val id: String,
    val name: String,
    val isSeries: Boolean,
    val year: String?,
    val runtimeLabel: String?,
    val ageLabel: String?,
    val ratingLabel: String?,
    val genres: List<String>,
    val plot: String?,
    val cast: List<String>,
    val isFavorite: Boolean = false,
    /** False only for a show with no episodes yet (design spec "Show details · no episodes yet"). */
    val hasEpisodes: Boolean = true,
    /**
     * Poster URL, from `get_vod_info`'s `info.movie_image` (F-50) — the
     * `get_vod_streams` row only ever carries `stream_icon`, and only as a
     * best-effort fallback when the detail call has not landed yet.
     */
    val posterUrl: String? = null,
    /**
     * Wide backdrop art (shows: first `backdrop_path` URL). Null when the
     * provider sent none; the screen then reuses [posterUrl] as before.
     */
    val backdropUrl: String? = null,
    /** Set when watch progress exists (#78): details then offers Resume and Start over. */
    val resume: ResumePoint? = null,
)

/**
 * Where to pick a title up. For a show, [episodeId] is the episode in progress and [label] its
 * "S2 E3"; for a movie both are null.
 */
data class ResumePoint(
    val positionMillis: Long,
    val episodeId: String?,
    val label: String?,
    /** True on the Start over press: the saved row is deleted when playback starts. */
    val startOver: Boolean = false,
) {
    /** An episode queued behind a finished one: nothing watched of it yet. */
    val isUpNext: Boolean get() = positionMillis <= 0L && episodeId != null

    /** "Resume from 41:20" for a movie, "Resume S2 E3" for a show, "Play S2 E4" for an up-next episode. */
    val buttonLabel: String
        get() = when {
            isUpNext -> "Play ${label ?: "next episode"}"
            // A show: say which episode ("Resume S2 E3"); its row carries the minutes.
            episodeId != null && label != null -> "Resume $label"
            else -> "Resume from ${WatchProgressPolicy.clock(positionMillis)}"
        }

    /** The same spot, played from 0:00 with the saved row cleared. */
    fun asStartOver(): ResumePoint = copy(positionMillis = 0L, startOver = true)
}
