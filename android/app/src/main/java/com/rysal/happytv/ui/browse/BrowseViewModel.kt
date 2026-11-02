package com.rysal.happytv.ui.browse

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.LiveTvOpenOn
import com.rysal.happytv.data.LiveTvView
import com.rysal.happytv.data.dao.CategoryDao
import com.rysal.happytv.data.dao.EpgProgramDao
import com.rysal.happytv.data.dao.NowProgram
import com.rysal.happytv.data.dao.FavoriteDao
import com.rysal.happytv.data.dao.HiddenChannelDao
import com.rysal.happytv.data.dao.LiveStreamDao
import com.rysal.happytv.data.dao.RecentChannelDao
import com.rysal.happytv.data.dao.toCountMap
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.guide.isXmltvGuideEnabled
import com.rysal.happytv.data.guide.programStoreKey
import com.rysal.happytv.perf.HtvPerf
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live TV — the two-pane "categories left, channels right" screen
 * (design spec §"Live TV").
 *
 * The screen's one non-obvious rule is the D-pad note on the canvas: moving
 * focus up and down the category list **changes the category and the channels
 * follow without pressing OK**. That is why [selected] is a [StateFlow] the
 * list writes to on focus, not a value the screen commits on click.
 *
 * State lives in Room, so everything survives a process kill:
 *  - categories: [CategoryDao.observeVisible] (`isHidden = 0`);
 *  - channels: [LiveStreamDao] per selection;
 *  - favorites: [FavoriteDao] (the star button and the Favorites rail);
 *  - hidden channels: [HiddenChannelDao] (long-press "Hide channel"), never
 *    touched by a catalog refresh;
 *  - recently watched: [RecentChannelDao].
 *
 * The header's two numbers both drop as things are hidden: [LiveTvUiState.totalChannels]
 * and [LiveTvUiState.visibleCategoryCount] are computed from what is actually
 * on screen, not from the raw table sizes.
 *
 * @param clock wall clock in epoch millis; injectable so the "now" line in
 *   each channel row is testable without waiting for real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(
    private val categoryDao: CategoryDao,
    private val liveStreamDao: LiveStreamDao,
    private val favoriteDao: FavoriteDao,
    private val recentChannelDao: RecentChannelDao,
    private val hiddenChannelDao: HiddenChannelDao,
    private val epgProgramDao: EpgProgramDao,
    /**
     * Which guide source wrote the store (Settings → Guide source), as a
     * live signal so a Settings change lands without a restart. The two
     * sources key `epg_programs` differently — provider rows by stream id,
     * XMLTV rows by `epg_channel_id` ([com.rysal.happytv.data.guide.programStoreKey]) —
     * so the now-line has to ask with the same key the guide grid uses
     * (F-36: browse asked with the XMLTV key while the store held provider
     * rows, so every row read "No guide info").
     */
    private val xmltvMode: Flow<Boolean>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Spec #7 section 3.3 / 3.2: "Open Live TV on", "Keep categories open" and
     * the remembered last category, as a live signal from Settings. Replayed
     * with no seed, so the first UI state (and the entry selection) waits for
     * the stored answer instead of flashing the defaults.
     */
    liveTvPrefs: Flow<LiveTvPrefs> = flowOf(LiveTvPrefs()),
    /** Persists the category the user picked, for "Last category". */
    private val saveLastCategory: suspend (String) -> Unit = {},
    /** Persists the Guide | List choice, which the Live TV tile reopens (spec #7 section 3.6). */
    private val saveView: suspend (LiveTvView) -> Unit = {},
) : ViewModel() {

    // #10: these are shared with replay = 1 and *no seed value*, so the first
    // [baseUi] state waits for the database's real answer. A seeded
    // `emptyList()`/`emptySet()` let a state through with an empty rail or a
    // "no favorites" pane before Room had answered.

    /** Visible LIVE categories (`isHidden = 0`). */
    private val categories: Flow<List<Category>> =
        categoryDao.observeVisible(StreamType.LIVE)
            .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val prefs: Flow<LiveTvPrefs> =
        liveTvPrefs.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** Every LIVE category, hidden included, so the rail knows what is hidden. */
    private val allCategories: Flow<List<Category>> =
        categoryDao.observeAll(StreamType.LIVE)
            .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** `{categoryId → channel count}` across the whole LIVE catalog. */
    private val channelCounts: Flow<Map<String, Int>> =
        liveStreamDao.observeCounts().toCountMap()
            .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val favoriteIds: Flow<Set<String>> =
        favoriteDao.observeForType(StreamType.LIVE)
            .map { rows -> rows.map { it.streamId }.toSet() }
            .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val recentIds: Flow<List<String>> =
        recentChannelDao.recent()
            .map { rows -> rows.map { it.streamId } }
            .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** Ids of channels the user hid — filtered out of every list. */
    private val hiddenChannelIds: Flow<Set<String>> =
        hiddenChannelDao.observeIds(StreamType.LIVE)
            .map { it.toSet() }
            .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /**
     * The hidden channels' rows, so their categories' counts can drop too, paired with the ids
     * they were looked up for (#191): the counts are read from one consistent pair, so a count
     * never mixes the new hidden ids with the previous rows while the row query catches up.
     */
    private val hiddenChannelRows: Flow<Pair<Set<String>, List<LiveStream>>> =
        hiddenChannelIds.flatMapLatest { ids ->
            if (ids.isEmpty()) flow { emit(ids to emptyList<LiveStream>()) }
            else liveStreamDao.observeByIds(ids.toList()).map { ids to it }
        }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /**
     * One emission now and one a minute after each. The wait runs on
     * [Dispatchers.Default] so it is real time even under a test `Main`
     * dispatcher, where a virtual-time `delay` plus a handler that does no real
     * I/O would spin `advanceUntilIdle()` forever.
     */
    private fun minuteTicks(): Flow<Long> = flow {
        while (true) {
            emit(clock())
            withContext(Dispatchers.Default) { delay(NOW_LINE_REFRESH_MILLIS) }
        }
    }

    /**
     * The focused rail row — the channel pane follows it. `null` until the entry
     * selection is resolved (#10, category-first): Favorites when the user has
     * any, otherwise [LiveCategorySelection.NothingPicked]. Entering Live TV
     * never selects "All channels", so it never queries the whole channel list.
     */
    private val _selected = MutableStateFlow<LiveCategorySelection?>(null)

    val selected: StateFlow<LiveCategorySelection?> = _selected.asStateFlow()

    init {
        // On Default: resumed on Main, each step waited behind the frame composing the
        // loading screen (#52, ~130 ms of a cold open). The stores are read directly.
        viewModelScope.launch(Dispatchers.Default) {
            val entry = resolveEntrySelection(liveTvPrefs.first())
            // An explicit pick that beat the entry lookup wins.
            if (_selected.compareAndSet(null, entry)) {
                Log.d(LOG_TAG, "entry selection=${entry::class.simpleName} (no full channel list query)")
            }
        }
    }

    /**
     * Spec #7 section 3.3 "Open Live TV on". Favorites (the default) is the
     * user's favorites, or [LiveCategorySelection.NothingPicked] when there are
     * none (rail open, "Pick a category" hint, no channel query); Choose a
     * category is always NothingPicked; Last category restores the remembered
     * pick and falls back to the Favorites rule when there is none (or it is
     * gone).
     */
    private suspend fun resolveEntrySelection(prefs: LiveTvPrefs): LiveCategorySelection {
        if (prefs.openOn == LiveTvOpenOn.CHOOSE) return LiveCategorySelection.NothingPicked
        val hasFavorites = favoriteDao.observeForType(StreamType.LIVE).first().isNotEmpty()
        val favoritesOrNothing =
            if (hasFavorites) LiveCategorySelection.Favorites else LiveCategorySelection.NothingPicked
        if (prefs.openOn != LiveTvOpenOn.LAST) return favoritesOrNothing
        return when (val key = prefs.lastCategory) {
            null -> favoritesOrNothing
            RAIL_FAVORITES -> favoritesOrNothing
            RAIL_RECENT -> LiveCategorySelection.RecentlyWatched
            RAIL_ALL -> LiveCategorySelection.AllChannels
            else -> categoryDao.observeVisible(StreamType.LIVE).first().firstOrNull { it.id == key }
                ?.let { LiveCategorySelection.Category(it.id, it.name) }
                ?: favoritesOrNothing
        }
    }

    private val _showHiddenChannels = MutableStateFlow(false)

    /** "Show hidden channels" (⋮ menu / the everything-hidden empty state). */
    val showHiddenChannels: StateFlow<Boolean> = _showHiddenChannels.asStateFlow()

    private val _hideEmptyCategories = MutableStateFlow(false)

    /**
     * The view the user chose in this session (the header toggle, or the dashboard's
     * TV guide tile asking for the guide); null follows the stored choice.
     */
    private val viewChoice = MutableStateFlow<LiveTvView?>(null)

    /**
     * How many channel rows are paged in so far (F-46) — one window query per
     * value, widened by [loadMoreChannels].
     */
    private val channelWindow = MutableStateFlow(PAGE_SIZE)

    /**
     * True when the last window came back short — end of data, so
     * [loadMoreChannels] stops widening (same rule as F-55's poster grid).
     */
    private val windowShort = MutableStateFlow(false)

    /** Rows the next selection's first window must hold (#138); read once by [channelsForSelection]. */
    @Volatile
    private var windowFloor = 0

    /** F-46 timing probe — wall clock at construction, for [logFirstRows]. */
    private val createdAtMs = System.currentTimeMillis()

    /** Only the first rows emission is interesting; later ones stay quiet. */
    private val firstRowsLogged = AtomicBoolean(false)

    /**
     * One debug line per screen entry — "first channel rows after Nms" — so
     * QA can measure time-to-first-rows on a device (F-46's before/after)
     * without a debugger attached. Row count rides along to show the window.
     */
    private fun logFirstRows(rows: List<LiveStream>) {
        if (!firstRowsLogged.compareAndSet(false, true)) return
        Log.d(
            LOG_TAG,
            "first channel rows after ${System.currentTimeMillis() - createdAtMs}ms " +
                "(rows=${rows.size})",
        )
    }

    private val _channelMenu = MutableStateFlow<LiveStream?>(null)

    /** The channel under a long-press, or null when the menu is closed. */
    val channelMenu: StateFlow<LiveStream?> = _channelMenu.asStateFlow()

    private val _moreMenuOpen = MutableStateFlow(false)

    val moreMenuOpen: StateFlow<Boolean> = _moreMenuOpen.asStateFlow()

    /**
     * Channels for the current selection, **tagged with the selection that
     * produced them**.
     *
     * Pairing them here (instead of combining `_selected` and a bare channel
     * list as two independent flows) is what keeps the UI state consistent.
     * `_selected` changes the instant focus moves, but the DAO query behind it
     * only answers a round-trip later; combining the two separately made every
     * rail focus move emit twice, the first time pairing the *new* selection
     * with the *previous* category's channel list (F-42: a wasted full-screen
     * recomposition that also flashed the wrong channels). Sourcing the
     * selection from the same flow as its channels yields exactly one coherent
     * emission per focus move.
     *
     * F-46: the catalog-backed selections page in from Room one window at a
     * time ([channelWindow], widened by [loadMoreChannels]) — a real panel
     * holds 39 361 channels and hydrating every one of them to fill a
     * six-row viewport is what made first paint take seconds. Favorites and
     * Recently watched read whole id lists (bounded by the user's own rows).
     */
    private val channelsForSelection: Flow<Pair<LiveCategorySelection, List<LiveStream>>> =
        _selected.filterNotNull().flatMapLatest { selection ->
            // A new selection starts on a fresh first page: a window widened while
            // browsing one category must not make the next one hydrate that many rows.
            channelWindow.value = maxOf(PAGE_SIZE, windowFloor)
            windowFloor = 0
            windowShort.value = false
            val streams: Flow<List<LiveStream>> = when (selection) {
                LiveCategorySelection.NothingPicked -> flow { emit(emptyList<LiveStream>()) }
                LiveCategorySelection.AllChannels ->
                    channelWindow.flatMapLatest { limit -> liveStreamDao.observeAllWindow(limit) }
                LiveCategorySelection.Favorites ->
                    // Straight from the DAO, not the seeded `favoriteIds` StateFlow:
                    // its `emptySet()` seed would flash "No favorites yet" before
                    // the first real answer on a category-first entry.
                    favoriteDao.observeForType(StreamType.LIVE)
                        .map { rows -> rows.mapTo(HashSet()) { it.streamId } }
                        .flatMapLatest { ids ->
                        if (ids.isEmpty()) flow { emit(emptyList<LiveStream>()) }
                        // No ordering contract here: `favoriteIds` is a Set, so
                        // it carries no order to preserve, and the pane keeps
                        // `num` order for this selection. The ids are only a
                        // membership filter for the IN-clause.
                        else liveStreamDao.observeByIds(ids.toList())
                    }
                LiveCategorySelection.RecentlyWatched ->
                    recentIds.flatMapLatest { ids ->
                        if (ids.isEmpty()) {
                            flow { emit(emptyList<LiveStream>()) }
                        } else {
                            // F-56: `observeByIds` is `WHERE id IN (:ids)`, which
                            // SQLite answers in rowid order, so the newest-first
                            // contract from [RecentChannelDao.recent] never
                            // survives the join — the pane showed channels in
                            // `num` order instead of most-recent-first. Re-sort by
                            // each id's index in `ids` to carry the DAO's order
                            // through. `sortedBy` is stable, so an id missing from
                            // `ids` keeps its relative position at the end.
                            val order = ids.withIndex().associate { (i, id) -> id to i }
                            liveStreamDao.observeByIds(ids)
                                .map { streams -> streams.sortedBy { order[it.id] ?: Int.MAX_VALUE } }
                        }
                    }
                is LiveCategorySelection.Category ->
                    channelWindow.flatMapLatest { limit ->
                        liveStreamDao.observeByCategoryWindow(selection.categoryId, limit)
                    }
            }
            streams.map { selection to it }.onEach { (_, rows) ->
                // Rows here are pre-hidden-filter, so "short" means the query
                // itself ran out of data — exactly the widening stop signal.
                windowShort.value = rows.size < channelWindow.value
                logFirstRows(rows)
            }
        }

    /**
     * Everything but the now lines: rows draw as soon as the channel query
     * answers. [ui] adds the now lines when their (small, batched) lookup lands.
     */
    private val baseUi: StateFlow<LiveTvUiState> = combine(
        categories,
        allCategories,
        channelCounts,
        favoriteIds,
        recentIds,
        hiddenChannelIds,
        hiddenChannelRows,
        channelsForSelection,
        _showHiddenChannels,
        _hideEmptyCategories,
        prefs,
        viewChoice,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val visibleCategories = values[0] as List<Category>
        @Suppress("UNCHECKED_CAST")
        val everyCategory = values[1] as List<Category>
        @Suppress("UNCHECKED_CAST")
        val counts = values[2] as Map<String, Int>
        @Suppress("UNCHECKED_CAST")
        val favorites = values[3] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val recent = values[4] as List<String>
        @Suppress("UNCHECKED_CAST")
        val hiddenPair = values[6] as Pair<Set<String>, List<LiveStream>>
        val hiddenIds = hiddenPair.first
        val hiddenRows = hiddenPair.second
        // Selection rides with its own channel list (see [channelsForSelection])
        // so the two can never race into a mismatched emission.
        @Suppress("UNCHECKED_CAST")
        val selectionWithChannels = values[7] as Pair<LiveCategorySelection, List<LiveStream>>
        val selection = selectionWithChannels.first
        val channels = selectionWithChannels.second
        @Suppress("UNCHECKED_CAST")
        val showHidden = values[8] as Boolean
        @Suppress("UNCHECKED_CAST")
        val hideEmpty = values[9] as Boolean
        val livePrefs = values[10] as LiveTvPrefs
        val chosenView = values[11] as LiveTvView?

        val hiddenCategoryIds = everyCategory.filter { it.isHidden }.map { it.id }.toSet()
        // #191: with "Show hidden channels" on they are listed, so no row subtracts them.
        val hiddenPerCategory = if (showHidden) emptyMap() else hiddenRows.groupingBy { it.categoryId }.eachCount()
        // #191: hidden categories never count; hidden channels count only when they are shown.
        // Hidden channels that sit in a hidden category are already out of the sum.
        val shownHiddenCount = if (showHidden) {
            0
        } else {
            hiddenRows.count { it.categoryId !in hiddenCategoryIds }
        }
        val totalChannels = (
            counts.filterKeys { it !in hiddenCategoryIds }.values.sum() - shownHiddenCount
            ).coerceAtLeast(0)

        val rail = buildCategoryRail(
            categories = visibleCategories,
            counts = counts,
            hiddenPerCategory = hiddenPerCategory,
            allChannelsCount = totalChannels,
            favorites = if (showHidden) favorites else favorites - hiddenIds,
            recent = if (showHidden) recent else recent.filter { it !in hiddenIds },
            selection = selection,
            hideEmpty = hideEmpty,
        )

        // Each selection arrives in the order its own source established:
        // AllChannels / Category come from a DAO query that already has
        // `ORDER BY num, name`, while RecentlyWatched arrives in the
        // newest-first order `recentIds` carries (F-56 — re-established in
        // [channelsForSelection], because the IN-clause query drops it).
        // Sorting every selection by `num` here would silently clobber the
        // recents order, so the channel-number sort only runs for the two
        // selections that actually mean it. Favorites has no user-facing
        // order contract (its ids are a Set) and stays in channel-number
        // order, matching what the pane showed before.
        val visibleChannels = channels
            .filter { showHidden || it.id !in hiddenIds }
            .let { ordered ->
                when (selection) {
                    LiveCategorySelection.NothingPicked -> ordered
                    LiveCategorySelection.AllChannels,
                    is LiveCategorySelection.Category ->
                        ordered.sortedBy { it.num ?: Int.MAX_VALUE }
                    LiveCategorySelection.Favorites,
                    LiveCategorySelection.RecentlyWatched ->
                        ordered
                }
            }
        val hiddenInSelection = when (val sel = selection) {
            LiveCategorySelection.NothingPicked -> 0
            LiveCategorySelection.AllChannels -> shownHiddenCount
            LiveCategorySelection.Favorites -> 0
            LiveCategorySelection.RecentlyWatched -> 0
            is LiveCategorySelection.Category ->
                if (showHidden) 0 else hiddenPerCategory[sel.categoryId] ?: 0
        }

        // The pane heading's count: the selection's real size, computed from
        // the catalog counts — never the paged-in window size (F-46, the same
        // wrong-data half F-55 fixed on the poster grid). Favorites / Recently
        // watched read whole id lists (never windowed), so their filtered rows
        // are the exact total already.
        val selectionTotal = when (val sel = selection) {
            LiveCategorySelection.NothingPicked -> 0
            LiveCategorySelection.Favorites,
            LiveCategorySelection.RecentlyWatched,
            -> visibleChannels.size
            LiveCategorySelection.AllChannels -> totalChannels
            is LiveCategorySelection.Category ->
                ((counts[sel.categoryId] ?: 0) - hiddenInSelection).coerceAtLeast(0)
        }

        LiveTvUiState(
            categories = rail,
            // Now lines are added by [ui] once their batched lookup lands.
            channels = visibleChannels.map { stream ->
                LiveChannelRow(stream = stream, isFavorite = stream.id in favorites)
            },
            favoriteIds = favorites,
            hiddenChannelIds = hiddenIds,
            selectedSelection = selection,
            selectionChannelCount = selectionTotal,
            totalChannels = totalChannels,
            visibleCategoryCount = visibleCategories.size,
            hiddenChannelCount = hiddenInSelection,
            showHiddenChannels = showHidden,
            hideEmptyCategories = hideEmpty,
            keepCategoriesOpen = livePrefs.keepCategoriesOpen,
            view = chosenView ?: livePrefs.view,
            // Reaching this builder at all means the first channel list landed:
            // `combine` holds [LiveTvUiState]'s initial value (isLoading = true)
            // until every source — including [channelsForSelection] — has
            // emitted once. An empty catalog resolves to a loaded, zero-row
            // pane (the empty state), not an endless skeleton.
            isLoading = false,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvUiState(isLoading = true))

    // ------------------------------------------------------------ now lines
    //
    // #10: the now line used to come from one whole-table scan of the guide
    // (every channel, descriptions included), re-run every minute and after any
    // guide write. Now it is one indexed lookup for the keys of the rows that are
    // loaded, kept between emissions so a favorite toggle or a widened window only
    // asks for the keys it has not seen.

    /** What a lookup found, and which keys it covered (found or not). */
    private data class NowSnapshot(
        val generation: Long,
        val xmltv: Boolean?,
        val byKey: Map<String, NowProgram>,
        val resolved: Set<String>,
    )

    private val nowSnapshot = MutableStateFlow(NowSnapshot(0L, null, emptyMap(), emptySet()))

    /** Bumped by the minute tick and by guide writes: everything cached is stale. */
    private val nowGeneration = MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            // The first emission is the initial load, which generation 0 already covers.
            combine(minuteTicks(), epgProgramDao.observeStoreVersion().distinctUntilChanged()) { _, version -> version }
                .drop(1)
                .collect { nowGeneration.update { it + 1 } }
        }
        viewModelScope.launch {
            combine(
                baseUi.filter { !it.isLoading }.map { state -> state.channels to state.view },
                xmltvMode,
                nowGeneration,
            ) { rowsAndView, xmltv, generation -> Triple(rowsAndView, xmltv, generation) }
                .collectLatest { (rowsAndView, xmltv, generation) ->
                    val (rows, view) = rowsAndView
                    // The guide grid brings its own programmes; the list's now lines are
                    // dead weight (a 300 ms read on the TV) while it is the one on screen.
                    if (view == LiveTvView.GUIDE) return@collectLatest
                    val keys = LinkedHashSet<String>()
                    rows.forEach { row ->
                        programStoreKey(row.stream.id, row.stream.epgChannelId, xmltv)?.let(keys::add)
                    }
                    val previous = nowSnapshot.value
                    val fresh = previous.generation == generation &&
                        previous.xmltv == xmltv &&
                        previous.resolved.size <= NOW_CACHE_LIMIT
                    val wanted = if (fresh) keys.filterNot { it in previous.resolved } else keys.toList()
                    if (fresh && wanted.isEmpty()) return@collectLatest
                    val begun = System.nanoTime()
                    val found = epgProgramDao.getNowForChannels(wanted, clock()).associateBy { it.channelId }
                    if (wanted.isNotEmpty()) HtvPerf.log(
                        "livetv.now.lookup",
                        (System.nanoTime() - begun) / 1_000_000L,
                        "keys=${wanted.size} found=${found.size} gen=$generation fresh=$fresh",
                    )
                    nowSnapshot.value = if (fresh) {
                        previous.copy(byKey = previous.byKey + found, resolved = previous.resolved + wanted)
                    } else {
                        NowSnapshot(generation, xmltv, found, wanted.toSet())
                    }
                }
        }
    }

    val ui: StateFlow<LiveTvUiState> = combine(baseUi, nowSnapshot, xmltvMode) { base, snapshot, xmltv ->
        if (base.isLoading) return@combine base
        val usable = snapshot.xmltv == xmltv
        var allResolved = true
        val rows = base.channels.map { row ->
            val key = programStoreKey(row.stream.id, row.stream.epgChannelId, xmltv)
                ?: return@map row // no key for this mode: "No guide info", nothing to look up
            if (!usable || key !in snapshot.resolved) {
                allResolved = false
                return@map row
            }
            val now = snapshot.byKey[key] ?: return@map row
            row.copy(
                nowTitle = now.title,
                nowUntilLabel = "until ${
                    END_TIME_FORMATTER.format(
                        java.time.Instant.ofEpochMilli(now.endUtcMillis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalTime(),
                    )
                }",
            )
        }
        base.copy(channels = rows, nowResolved = allResolved)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvUiState(isLoading = true))

    /**
     * OK or D-pad Right on a rail row (spec #7 section 3.2): select the
     * category and load its channels. Moving focus through the rail never
     * calls this (that replaces F-16's "focus selects category"), and the pick
     * is remembered for the "Last category" entry option.
     */
    fun selectCategory(selection: LiveCategorySelection, minRows: Int = 0) {
        // #138: the jump to a playing channel deep in a category pages in as many rows as
        // reach it, whether the category is already open or is about to be.
        val needed = ((minRows + PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE
        if (_selected.value == selection) {
            if (needed > channelWindow.value) channelWindow.value = needed
        } else {
            windowFloor = needed
            HtvPerf.start(HtvPerf.LIVETV_CATEGORY_ROWS)
        }
        _selected.value = selection
        railKey(selection)?.let { key -> viewModelScope.launch { saveLastCategory(key) } }
    }

    /**
     * The pane is nearing the end of its paged-in window (F-46) — widen it by
     * one more [PAGE_SIZE] page. No-op when the window came back short (end of
     * data) and on Favorites / Recently watched, which read whole id lists.
     */
    fun loadMoreChannels() {
        when (_selected.value) {
            null,
            LiveCategorySelection.NothingPicked,
            LiveCategorySelection.Favorites,
            LiveCategorySelection.RecentlyWatched,
            -> return
            else -> Unit
        }
        val limit = channelWindow.value
        if (windowShort.value) return
        channelWindow.value = limit + PAGE_SIZE
    }

    /**
     * The header's Guide | List toggle (spec #7 section 3.6): switches the body now
     * and remembers the choice - the Live TV tile reopens the last view.
     */
    fun setView(view: LiveTvView) {
        viewChoice.value = view
        viewModelScope.launch { saveView(view) }
    }

    /**
     * The dashboard's TV guide tile opens Guide view whatever was used last. Not
     * remembered: only a toggle changes what the Live TV tile reopens.
     */
    fun showViewForThisVisit(view: LiveTvView) {
        viewChoice.value = view
    }

    /**
     * The one program of a channel that is on at [atUtcMillis], with its description,
     * for the info band and the details dialog (#15). A single indexed row, off the
     * main thread; callers debounce it on focus changes. This is deliberately **not**
     * part of the batched now lookup (#32 took descriptions out of it on purpose).
     */
    suspend fun programFor(streamId: String, epgChannelId: String?, atUtcMillis: Long): EpgProgram? {
        val xmltv = xmltvMode.first()
        val key = programStoreKey(streamId, epgChannelId, xmltv) ?: return null
        return withContext(Dispatchers.IO) { epgProgramDao.programAt(key, atUtcMillis) }
    }

    fun toggleFavorite(streamId: String) {
        viewModelScope.launch { favoriteDao.toggle(streamId, StreamType.LIVE) }
    }

    /** Long-press "Hide channel": the channel leaves every list. */
    fun hideChannel(streamId: String) {
        viewModelScope.launch { hiddenChannelDao.toggle(streamId, StreamType.LIVE) }
        _channelMenu.value = null
    }

    /** Long-press "Hide News": hides the channel's whole category. */
    fun hideCategory(categoryId: String) {
        viewModelScope.launch { categoryDao.toggleHidden(categoryId, StreamType.LIVE) }
        _channelMenu.value = null
        // A hidden category must not stay on screen as the selection.
        if ((_selected.value as? LiveCategorySelection.Category)?.categoryId == categoryId) {
            _selected.value = LiveCategorySelection.NothingPicked
        }
    }

    /** "Show hidden channels" — brings back everything hidden in one catalog. */
    fun setShowHiddenChannels(value: Boolean) {
        _showHiddenChannels.value = value
    }

    fun setHideEmptyCategories(value: Boolean) {
        _hideEmptyCategories.value = value
    }

    fun openChannelMenu(stream: LiveStream) {
        _channelMenu.value = stream
    }

    fun closeChannelMenu() {
        _channelMenu.value = null
    }

    fun setMoreMenuOpen(open: Boolean) {
        _moreMenuOpen.value = open
    }

    /** "Recently watched" only counts once something has been watched. */
    fun recordWatched(streamId: String) {
        viewModelScope.launch { recentChannelDao.recordWatch(streamId, clock()) }
    }

    /**
     * Long-press "Remove from recently watched" (F-53): the row leaves the
     * `recent_channels` table, so it stays gone across restarts — watching the
     * channel again is what brings it back. The menu closes here (like
     * [hideChannel]); the screen hands focus to the next row.
     */
    fun removeRecentlyWatched(streamId: String) {
        viewModelScope.launch { recentChannelDao.remove(streamId) }
        _channelMenu.value = null
    }

    /**
     * The rail, in spec #7 section 3.2 order: the two pinned rows, a divider,
     * the provider's categories with counts, then "All channels" last (paged,
     * never the default).
     */
    private fun buildCategoryRail(
        categories: List<Category>,
        counts: Map<String, Int>,
        hiddenPerCategory: Map<String, Int>,
        allChannelsCount: Int,
        favorites: Set<String>,
        recent: List<String>,
        selection: LiveCategorySelection,
        hideEmpty: Boolean,
    ): List<LiveCategoryItem> {
        val items = mutableListOf<LiveCategoryItem>()
        items += LiveCategoryItem.Pinned(
            id = RAIL_FAVORITES,
            label = "Favorites",
            count = favorites.size,
            iconKind = PinnedIconKind.STAR,
            selected = selection is LiveCategorySelection.Favorites,
            target = LiveCategorySelection.Favorites,
        )
        items += LiveCategoryItem.Pinned(
            id = RAIL_RECENT,
            label = "Recently watched",
            count = recent.size,
            iconKind = PinnedIconKind.HISTORY,
            selected = selection is LiveCategorySelection.RecentlyWatched,
            target = LiveCategorySelection.RecentlyWatched,
        )
        items += LiveCategoryItem.Divider
        val providerRows = categories
            .filter { !hideEmpty || (counts[it.id] ?: 0) > 0 }
            .map { category ->
                val count = ((counts[category.id] ?: 0) - (hiddenPerCategory[category.id] ?: 0))
                    .coerceAtLeast(0)
                LiveCategoryItem.ProviderCategory(
                    id = category.id,
                    label = category.name,
                    count = count,
                    selected = (selection as? LiveCategorySelection.Category)?.categoryId == category.id,
                    target = LiveCategorySelection.Category(category.id, category.name),
                )
            }
        items += providerRows
        items += LiveCategoryItem.AllChannels(
            id = RAIL_ALL,
            label = "All channels",
            count = allChannelsCount,
            selected = selection is LiveCategorySelection.AllChannels,
        )
        return items
    }

    /** Factory wired against [Graph], like every other browse ViewModel. */
    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == BrowseViewModel::class.java) {
                "Factory only knows about BrowseViewModel; got $modelClass"
            }
            val db = Graph.database
            return BrowseViewModel(
                categoryDao = db.categoryDao(),
                liveStreamDao = db.liveStreamDao(),
                favoriteDao = db.favoriteDao(),
                recentChannelDao = db.recentChannelDao(),
                hiddenChannelDao = db.hiddenChannelDao(),
                epgProgramDao = db.epgProgramDao(),
                // The store's key space depends on the source that wrote it;
                // follow Settings instead of reading it once at construction.
                xmltvMode = Graph.settings.settings
                    .map { isXmltvGuideEnabled(it.epgUrl) }
                    .distinctUntilChanged(),
                liveTvPrefs = Graph.settings.settings
                    .map { LiveTvPrefs(it.liveTvOpenOn, it.keepCategoriesOpen, it.lastLiveCategory, it.liveTvView) }
                    .distinctUntilChanged(),
                saveLastCategory = { Graph.settings.setLastLiveCategory(it) },
                saveView = { Graph.settings.setLiveTvView(it) },
            ) as T
        }
    }

    companion object {
        internal const val RAIL_FAVORITES = "__favorites__"
        internal const val RAIL_RECENT = "__recent__"
        internal const val RAIL_ALL = "__all__"

        /** The stored key for "Last category", or null for a selection that is not remembered. */
        internal fun railKey(selection: LiveCategorySelection): String? = when (selection) {
            LiveCategorySelection.NothingPicked -> null
            LiveCategorySelection.Favorites -> RAIL_FAVORITES
            LiveCategorySelection.RecentlyWatched -> RAIL_RECENT
            LiveCategorySelection.AllChannels -> RAIL_ALL
            is LiveCategorySelection.Category -> selection.categoryId
        }

        /**
         * Rows per paging step — 40 channel rows in the pane (F-46). The
         * window grows one page each time the pane approaches its end, so a
         * 39 361-channel panel costs one page of hydration until the user
         * actually browses deeper. Mirrors F-55's poster-grid page size.
         */
        internal const val PAGE_SIZE = 240

        /** [logFirstRows]'s logcat tag — QA greps it for first-rows timings. */
        private const val LOG_TAG = "LiveTvLoad"

        /** Resolved keys kept between lookups before the cache starts over. */
        private const val NOW_CACHE_LIMIT = 2_000

        /** How often the "now" line re-reads the guide. */
        private const val NOW_LINE_REFRESH_MILLIS = 60_000L

        /** "until 9:00" — the end time the handoff shows under a program title. */
        internal val END_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("h:mm")
    }
}

/** The Live TV settings the screen follows live (see [BrowseViewModel]). */
@Immutable
data class LiveTvPrefs(
    val openOn: LiveTvOpenOn = LiveTvOpenOn.FAVORITES,
    val keepCategoriesOpen: Boolean = false,
    val lastCategory: String? = null,
    /** Spec #7 section 3.6: Guide | List, remembered. The guide is the default. */
    val view: LiveTvView = LiveTvView.GUIDE,
)

/** The selected rail row — the channel pane shows its channels. */
@Immutable
sealed interface LiveCategorySelection {
    /** Category-first entry with no favorites: the rail is focused, the pane is empty (#10). */
    data object NothingPicked : LiveCategorySelection
    data object AllChannels : LiveCategorySelection
    data object Favorites : LiveCategorySelection
    data object RecentlyWatched : LiveCategorySelection
    data class Category(val categoryId: String, val label: String) : LiveCategorySelection
}

/**
 * One row on the category rail — pinned, provider category, header or divider.
 *
 * F-42: `@Immutable` (and on each subtype) lets Compose skip rail rows whose
 * data is unchanged when the selection moves, instead of recomposing all 380+.
 */
@Immutable
sealed interface LiveCategoryItem {
    val key: String

    @Immutable
    data class Pinned(
        val id: String,
        val label: String,
        val count: Int = 0,
        val iconKind: PinnedIconKind,
        val selected: Boolean,
        val target: LiveCategorySelection,
    ) : LiveCategoryItem {
        override val key: String = id
    }

    @Immutable
    data class AllChannels(
        val id: String,
        val label: String,
        val count: Int,
        val selected: Boolean,
    ) : LiveCategoryItem {
        override val key: String = id
    }

    @Immutable
    data class ProviderCategory(
        val id: String,
        val label: String,
        val count: Int,
        val selected: Boolean,
        val target: LiveCategorySelection,
    ) : LiveCategoryItem {
        override val key: String = id
    }

    @Immutable
    data class Header(val label: String) : LiveCategoryItem {
        override val key: String = "h:$label"
    }

    data object Divider : LiveCategoryItem {
        override val key: String = "__divider__"
    }
}

enum class PinnedIconKind { STAR, HISTORY }

/**
 * A channel plus everything the row shows beside its name.
 *
 * F-42: `@Immutable` lets the Compose compiler treat this as stable and skip
 * re-composing a channel row whose data hasn't changed when the rail moves.
 * Every field is an immutable value type, so the promise holds.
 */
@Immutable
data class LiveChannelRow(
    val stream: LiveStream,
    val isFavorite: Boolean = false,
    /** Guide title for what is on now, or null for "No guide info". */
    val nowTitle: String? = null,
    /** "until 9:00", never null when [nowTitle] is set — it never truncates. */
    val nowUntilLabel: String? = null,
)

/**
 * One snapshot of the screen — what LiveTvScreen collects.
 *
 * F-42: `@Immutable` promises Compose this snapshot never mutates in place, so
 * a rail focus move that only flips `selected` on two rows can skip the panes
 * whose data is unchanged instead of recomposing the whole screen. The list
 * fields are built fresh (never mutated) by the ViewModel, so the promise holds.
 */
@Immutable
data class LiveTvUiState(
    val categories: List<LiveCategoryItem> = emptyList(),
    val channels: List<LiveChannelRow> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val hiddenChannelIds: Set<String> = emptySet(),
    val selectedSelection: LiveCategorySelection = LiveCategorySelection.AllChannels,
    /**
     * How many channels the selection really holds (after hiding) — the pane
     * heading's count. [channels] is a paged window of the selection (F-46),
     * so its size must never be shown as the total.
     */
    val selectionChannelCount: Int = 0,
    val totalChannels: Int = 0,
    val visibleCategoryCount: Int = 0,
    /** Channels hidden inside the selected category — the "· 2 hidden" suffix. */
    val hiddenChannelCount: Int = 0,
    val showHiddenChannels: Boolean = false,
    /** The ⋮ menu's "Hide empty categories" filter. */
    val hideEmptyCategories: Boolean = false,
    /** Settings "Keep categories open": the rail is a permanent pane (spec #7 section 3.2). */
    val keepCategoriesOpen: Boolean = false,
    /** Which body the screen shows: the guide grid or the channel list (spec #7 section 3.6). */
    val view: LiveTvView = LiveTvView.GUIDE,
    /**
     * True until the selection's first channel list has landed (F-46) — the
     * pane draws skeleton rows while this holds, never the empty-state copy.
     * The initial [LiveTvUiState] carries true; every state the ViewModel
     * builds after `combine`'s first emission carries false.
     */
    val isLoading: Boolean = false,
    /**
     * True once the now lines have been looked up for [channels] — a row with
     * no [LiveChannelRow.nowTitle] then really has no guide info, instead of
     * "still asking". Drives the `livetv.category.epg` timing.
     */
    val nowResolved: Boolean = false,
)

/**
 * What the channel pane draws for one [LiveTvUiState] — the F-46
 * loading-state mapping, split out so it is unit-testable without Compose.
 *
 * The rule the finding demands: the empty-state copy ("No channels to show
 * in …") must never appear while data is still loading — for the ~5 s a
 * 39 361-channel catalog takes to answer, the pane shows a skeleton instead.
 */
@Immutable
sealed interface ChannelPaneContent {
    /** First channel list still in flight — skeleton rows. */
    data object Loading : ChannelPaneContent

    /** Category-first entry with nothing starred: "Pick a category to see its channels". */
    data object PickCategory : ChannelPaneContent

    /** Rows to draw (a paged window of the selection). */
    @Immutable
    data class Rows(val rows: List<LiveChannelRow>) : ChannelPaneContent

    /** The selection resolved and is genuinely empty. */
    @Immutable
    sealed interface Empty : ChannelPaneContent {
        /** Favorites with nothing starred — "No favorites yet". */
        data object NoFavorites : Empty

        /**
         * F-53: Recently watched with nothing watched yet — "No recently
         * watched channels". Derived from the selection exactly like
         * [NoFavorites] so the pane keeps this state instead of falling
         * through to the generic wording.
         */
        data object RecentlyWatched : Empty

        /** Everything in the selection is hidden — "… N are hidden". */
        data class AllHidden(val hiddenCount: Int) : Empty

        /** Nothing here right now — the generic empty state. */
        data object NothingHere : Empty
    }
}

/**
 * Maps a [LiveTvUiState] to [ChannelPaneContent]: rows win over everything
 * (a window that came back empty is not "loading"), then the loading
 * skeleton, then the empty variants in the order the empty state renders
 * them. Test: [ChannelPaneContentTest].
 */
internal fun channelPaneContent(state: LiveTvUiState): ChannelPaneContent = when {
    state.selectedSelection is LiveCategorySelection.NothingPicked && !state.isLoading ->
        ChannelPaneContent.PickCategory
    state.channels.isNotEmpty() -> ChannelPaneContent.Rows(state.channels)
    state.isLoading -> ChannelPaneContent.Loading
    state.selectedSelection is LiveCategorySelection.Favorites ->
        ChannelPaneContent.Empty.NoFavorites
    // F-53: same shape as the favorites case above — the recently-watched
    // selection with an empty list is its own state, not the generic one.
    state.selectedSelection is LiveCategorySelection.RecentlyWatched ->
        ChannelPaneContent.Empty.RecentlyWatched
    state.hiddenChannelCount > 0 -> ChannelPaneContent.Empty.AllHidden(state.hiddenChannelCount)
    else -> ChannelPaneContent.Empty.NothingHere
}
