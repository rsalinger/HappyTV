package com.rysal.happytv.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rysal.happytv.data.AppSettings
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.SyncMark
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.network.RefreshGateway
import com.rysal.happytv.data.network.RefreshProgress
import com.rysal.happytv.data.network.RefreshState
import com.rysal.happytv.data.network.SessionState
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.theme.HappyTvThemeState
import java.util.Locale
import com.rysal.happytv.ui.browse.formatCount
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/* -------------------------------------------------------------------------- */
/*  Tile model — the design spec tile-state table as types                        */
/* -------------------------------------------------------------------------- */

/**
 * What the tile's count area shows. "Never both, never neither": exactly one
 * variant is active at all times.
 */
sealed interface TileCounts {
    /** First-ever load: two placeholder bars. Never "0 channels". */
    data object Placeholders : TileCounts

    /** Two formatted count lines, e.g. "1,432 channels" / "47 categories". */
    data class Lines(val first: String, val second: String) : TileCounts

    /** Signed out: one line, "Sign in to load channels" (or movies, shows). */
    data class SignedOut(val prompt: String) : TileCounts
}

/**
 * The status pill's presentation — every row of the design spec tile-state table.
 * The bar (Updating/Completing/Collapsing) and the stamp (Stamp) are mutually
 * exclusive by construction; Hidden means the pill isn't drawn at all.
 */
sealed interface TileStatus {
    /** Signed out — pill hidden. */
    data object Hidden : TileStatus

    /** Updating — indeterminate bar, no text, nothing at the right. */
    data object Updating : TileStatus

    /** Complete — bar filled to 100%, text still "Updating…". */
    data object Completing : TileStatus

    /** Collapsing — fill shrinking to 35%, check growing into the icon slot. */
    data object Collapsing : TileStatus

    /** Fresh / Recent / Stale stamp, aged by the minute. */
    data class Stamp(val text: String, val age: AgeTier) : TileStatus

    /** Failed — red alert, red text, Retry. */
    data class Failed(val message: String) : TileStatus

    /** Drives the stamp's icon: green check, tertiary clock, amber clock. */
    enum class AgeTier { FRESH, RECENT, STALE }
}

/** One dashboard tile, fully derived — the UI only maps this to pixels. */
data class DashboardTile(
    val type: StreamType,
    val title: String,
    val unit: String,
    val channels: Int,
    val categories: Int,
    val counts: TileCounts,
    val status: TileStatus,
    val showStaleBadge: Boolean,
    val refreshState: RefreshState,
    val lastRefreshedAtUtcMillis: Long,
    /** #195: the stored rows are a partial catalog (a sync was cut short); the numbers are hidden. */
    val incomplete: Boolean = false,
) {
    /** First-load placeholder — the count lines haven't arrived yet. */
    val isFirstLoad: Boolean get() = counts is TileCounts.Placeholders

    /** The coordinator's error text for this tile (or null). */
    val errorMessage: String?
        get() = (refreshState as? RefreshState.Error)?.message

    /** Screen-reader label: body "Live TV, 1,432 channels, 47 categories". */
    val bodyDescription: String
        get() = when (val c = counts) {
            is TileCounts.Placeholders -> "$title, loading"
            is TileCounts.SignedOut -> "$title, ${c.prompt}"
            is TileCounts.Lines -> "$title, ${c.first}, ${c.second}"
        }

    /** Screen-reader label: pill "Live TV: Updated just now. Refresh". */
    val pillDescription: String
        get() = when (val s = status) {
            is TileStatus.Hidden -> "$title, no status"
            is TileStatus.Updating, is TileStatus.Completing -> "$title: Updating."
            is TileStatus.Collapsing -> "$title: Updated just now."
            is TileStatus.Stamp -> "$title: ${s.text}. Refresh"
            is TileStatus.Failed -> "$title: Couldn't reach server. Retry"
        }
}

/** Aggregate UI state for the whole dashboard. */
data class DashboardUiState(
    val tiles: List<DashboardTile> = emptyList(),
    val isSignedIn: Boolean = false,
    /**
     * False until the cold-start credential read lands (issue #30). While
     * false the dashboard is neutral: no "Not signed in", no sign-in prompts,
     * no welcome strip, tiles show first-load placeholders and are inert.
     */
    val sessionResolved: Boolean = true,
    val refreshOnStart: Boolean = true,
    val showConnectionBanner: Boolean = false,
    val connectionDetailsExpanded: Boolean = false,
    /** Banner copy: "Nothing refreshed. Your last successful update was 3 hours ago." */
    val connectionMessage: String = "",
    /** Details panel: "Today at 5:12 PM" (or "Never"). */
    val lastGoodUpdateText: String = "Never",
    /** Details panel + About menu: "provider.example:8080". */
    val serverLabel: String = "",
    val lastSignedInAtUtcMillis: Long = 0L,
    /** Non-null while the long-press menu is open on that tile. */
    val menuTile: StreamType? = null,
    /** True when the menu shows the "About this data" view. */
    val menuShowingAbout: Boolean = false,
    /** About view: "Today at 4:31 PM (12 minutes ago)" for the menu's tile. */
    val aboutLastUpdated: String = "Never",
) {
    /** "Nothing refreshed" → connection banner on (per design spec). */
    val allTilesErrored: Boolean
        get() = tiles.size == 3 && tiles.all { it.refreshState is RefreshState.Error }

    /** Header for the long-press menu — the tile's name. */
    val menuTitle: String?
        get() = menuTile?.let { tileTitle(it) }
}

/* -------------------------------------------------------------------------- */
/*  ViewModel                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * The dashboard's brain.
 *
 * Aggregates [RefreshGateway.progress], [SettingsRepository.settings], the
 * one-shot catalog row counts, a 15 s stamp ticker and the per-tile refresh
 * motion into one [DashboardUiState]. The motion state machine (Updating →
 * Complete → Collapsing → Fresh, with the design spec's 200 ms / 60 ms beats)
 * lives here so `DashboardViewModelTest` can drive every row of the tile-state
 * table without a compositor.
 *
 * Ordering guarantee (the fix for the flaky v1 tests): catalog counts are
 * re-queried *inside* the progress pipeline, before the combine ever sees a
 * new progress value — so a tile can never be observed as Done with the
 * pre-refresh counts.
 */
class DashboardViewModel(
    private val coordinator: RefreshGateway,
    private val settings: SettingsRepository,
    private val database: HappyTvDatabase,
    private val clockUtcMillis: () -> Long = System::currentTimeMillis,
    /** Motion beats (200 ms fill, 60 ms hold, 200 ms collapse). Injectable for tests. */
    private val motionDelayMs: suspend (Long) -> Unit = { delay(it) },
    /** How long the start-up refresh waits after the dashboard is up (#157). Null = no wait (tests). */
    private val startupRefreshDelayMs: Long? = STARTUP_REFRESH_DELAY_MS,
) : ViewModel() {

    private val _ui = MutableStateFlow(initialUiState())
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()

    private val catalogCounts = MutableStateFlow(CatalogCounts())
    private val motionPhase = MutableStateFlow<Map<StreamType, TileStatus>>(emptyMap())
    private val silentRefresh = MutableStateFlow(false)
    private val longPressState = MutableStateFlow(LongPressMenuState.NONE)
    private val serverLabelState = MutableStateFlow("")
    private val nowTick = MutableStateFlow(clockUtcMillis())

    private val motionJobs = mutableMapOf<StreamType, Job>()
    private var prevProgress = RefreshProgress()

    init {
        // Stamp aging: the combine re-derives every 15 s, so "Updated 12
        // minutes ago" ages by the minute with instant text swaps.
        viewModelScope.launch {
            while (true) {
                delay(STAMP_TICK_MS)
                nowTick.value = clockUtcMillis()
            }
        }

        // Counts-before-state ordering: `onEach` re-queries the DAOs before
        // the progress value reaches the combine, so a tile can never be
        // observed as Done with the pre-refresh counts — and the motion
        // launch happens on the same coroutine, so Complete lands atomically
        // with the new counts.
        viewModelScope.launch {
            val sources: List<kotlinx.coroutines.flow.Flow<Any>> = listOf(
                coordinator.progress.onEach { progress ->
                    processMotion(progress)
                    refreshCatalogCounts()
                },
                settings.settings,
                catalogCounts,
                motionPhase,
                silentRefresh,
                nowTick,
                longPressState,
                // The live session state (F-45): the login flow's update reaches
                // this combine the moment credentials land — no re-prime, no
                // restart, the header cannot lag the sign-in.
                coordinator.sessionState,
                serverLabelState,
            )
            combine(sources) { values ->
                @Suppress("UNCHECKED_CAST")
                buildUiState(
                    progress = values[0] as RefreshProgress,
                    settingsValue = values[1] as AppSettings,
                    counts = values[2] as CatalogCounts,
                    motion = values[3] as Map<StreamType, TileStatus>,
                    silent = values[4] as Boolean,
                    now = values[5] as Long,
                    menu = values[6] as LongPressMenuState,
                    session = values[7] as SessionState,
                    server = values[8] as String,
                )
            }.collect { _ui.value = it }
        }

        refreshServerLabel()
    }

    /* ----------------------------------------------------------- actions */

    /**
     * The dashboard calls this once per launch. With "Refresh on start" on, it
     * brings the stale tiles up to date, but never while the app is starting
     * (#157, #158):
     *
     *  - it waits [startupRefreshDelayMs] so the first frames and the first
     *    D-pad moves are not competing with a download for I/O and memory;
     *  - it skips tiles refreshed within [STARTUP_REFRESH_AGE_MS], or already
     *    loading or loaded in this process (a sign-in just refreshed them);
     *  - it runs the remaining tiles one at a time, not all at once.
     */
    fun onEnteredForeground(): Job = viewModelScope.launch {
        val first = settings.settings.first()
        if (!first.refreshOnStart) return@launch
        if (!coordinator.isSignedIn()) return@launch
        if (startupTilesToRefresh(first, coordinator.progress.value, clockUtcMillis()).isEmpty()) {
            HtvPerf.log("startup.refresh.skipped", 0L, "reason=fresh")
            return@launch
        }
        startupRefreshDelayMs?.let { motionDelayMs(it) }
        // Settings, session and progress may have moved while we waited.
        val s = settings.settings.first()
        if (!s.refreshOnStart || !coordinator.isSignedIn()) return@launch
        val tiles = startupTilesToRefresh(s, coordinator.progress.value, clockUtcMillis())
        HtvPerf.log("startup.refresh.begin", 0L, "tiles=${tiles.size}")
        for (type in tiles) {
            when (type) {
                StreamType.LIVE -> coordinator.refreshLiveOnly()
                StreamType.SERIES -> coordinator.refreshSeriesOnly()
                StreamType.VOD -> coordinator.refreshVodOnly()
            }
        }
    }

    /**
     * Back from the player or guide: a silent refresh only when some tile's
     * data is actually stale (F-41). Stamps stay up (no bars) and swap when
     * done. See [staleForReturnRefresh] for what counts as stale — a 0 stamp
     * alone no longer does, so a load that finished minutes ago doesn't kick
     * off a full refreshAll on every BACK.
     */
    fun onReturnedFromChild(): Job = viewModelScope.launch {
        if (!coordinator.isSignedIn()) return@launch
        // Hiding things in Live TV changes the tile's numbers (#167).
        refreshCatalogCounts()
        val now = clockUtcMillis()
        val stale = _ui.value.tiles.any { tile ->
            staleForReturnRefresh(tile.lastRefreshedAtUtcMillis, tile.refreshState, now, tile.incomplete)
        }
        if (!stale) return@launch
        silentRefresh.value = true
        try {
            coordinator.refreshAll()
        } finally {
            silentRefresh.value = false
        }
    }

    /** Status pill "Refresh" / "Retry" → only that tile. */
    fun refreshTile(type: StreamType): Job = viewModelScope.launch {
        when (type) {
            StreamType.LIVE -> coordinator.refreshLiveOnly()
            StreamType.VOD -> coordinator.refreshVodOnly()
            StreamType.SERIES -> coordinator.refreshSeriesOnly()
        }
    }

    /** Connection-details "Retry all" → all three tiles. */
    fun retryAll(): Job = viewModelScope.launch {
        coordinator.refreshAll()
    }

    /**
     * #126: the header's light/dark switch. The palette flips at once (it is
     * process-wide snapshot state, so every screen redraws without waiting for
     * the DataStore round trip); the choice is saved for the next launch.
     */
    fun setLightMode(light: Boolean): Job {
        HappyTvThemeState.light = light
        return viewModelScope.launch { settings.setLightMode(light) }
    }

    /** Connection-details "Sign out" — the dashboard owns the action. */
    fun signOut(): Job = viewModelScope.launch {
        // The coordinator publishes the signed-out SessionState on its own
        // session flow — the header falls back to "Not signed in" from there.
        coordinator.signOut()
        refreshServerLabel()
    }

    /** Long-press OK on a tile body. */
    fun openLongPressMenu(type: StreamType) {
        refreshServerLabel()
        longPressState.value = LongPressMenuState(type, showingAbout = false)
    }

    /** "About this data" swaps the menu's items. */
    fun menuShowAbout() {
        longPressState.update { it.copy(showingAbout = true) }
    }

    /** Close or Back — focus returns to the tile that opened the menu. */
    fun closeLongPressMenu() {
        longPressState.value = LongPressMenuState.NONE
    }

    fun toggleConnectionDetails() {
        _ui.update { it.copy(connectionDetailsExpanded = !it.connectionDetailsExpanded) }
    }

    private fun refreshServerLabel() {
        viewModelScope.launch {
            serverLabelState.value = coordinator.serverLabel()
        }
    }

    /* ------------------------------------------------------- derivation */

    /**
     * Per-tile motion: Loading → Updating; Done → Completing (+200 ms fill,
     * +60 ms hold) → Collapsing (+200 ms) → fresh stamp; Error/Idle → cancel.
     * A silent refresh runs no motion at all — the stamp just swaps.
     */
    private fun processMotion(progress: RefreshProgress) {
        StreamType.entries.forEach { type ->
            val old = prevProgress[type]
            val new = progress[type]
            if (new != old) {
                motionJobs.remove(type)?.cancel()
                when {
                    silentRefresh.value -> motionPhase.update { it - type }
                    new is RefreshState.Loading ->
                        setMotion(type, TileStatus.Updating)
                    new is RefreshState.Done -> {
                        // Set Completing synchronously — the launch below may
                        // not run before the combine re-derives, and a bare
                        // Done would flash a fresh stamp over the old counts.
                        setMotion(type, TileStatus.Completing)
                        motionJobs[type] = viewModelScope.launch {
                            motionDelayMs(FILL_MS + HOLD_MS)
                            setMotion(type, TileStatus.Collapsing)
                            motionDelayMs(COLLAPSE_MS)
                            motionPhase.update { it - type }
                        }
                    }
                    new is RefreshState.Error -> motionPhase.update { it - type }
                    new is RefreshState.Idle -> motionPhase.update { it - type }
                }
            }
        }
        prevProgress = progress
    }

    private fun setMotion(type: StreamType, status: TileStatus) {
        motionPhase.update { it + (type to status) }
    }

    /**
     * Re-reads the tile counts. A tile that is loading right now keeps the numbers it had: the
     * tables fill in chunks, so a mid-refresh read is a partial "19,000 channels, 0 categories"
     * (#163). The settled read lands when the tile leaves Loading.
     *
     * Channels follow what Live TV lists (hidden channels and categories left out) and
     * categories are the visible ones, so the tile and the screen's header agree (#167).
     */
    private suspend fun refreshCatalogCounts() {
        val categoryDao = database.categoryDao()
        val progress = coordinator.progress.value
        val previous = catalogCounts.value
        val fresh = CatalogCounts(
            liveChannels = database.liveStreamDao().countVisible(),
            vodChannels = database.vodStreamDao().count(),
            seriesChannels = database.seriesStreamDao().count(),
            liveCategories = categoryDao.countVisible(StreamType.LIVE),
            vodCategories = categoryDao.countVisible(StreamType.VOD),
            seriesCategories = categoryDao.countVisible(StreamType.SERIES),
        )
        catalogCounts.value = settleCounts(previous, fresh, progress)
    }

    /**
     * Pure fold of the nine upstream signals into one [DashboardUiState] —
     * kept separate so the combine lambda stays readable and the rules
     * ("never both, never neither", "counts never become 0") live in one place.
     */
    private fun buildUiState(
        progress: RefreshProgress,
        settingsValue: AppSettings,
        counts: CatalogCounts,
        motion: Map<StreamType, TileStatus>,
        silent: Boolean,
        now: Long,
        menu: LongPressMenuState,
        session: SessionState,
        server: String,
    ): DashboardUiState {
        val tiles = StreamType.entries.map { type ->
            val state = progress[type]
            val lastMs = lastRefreshFor(settingsValue, type)
            val motionStatus = motion[type]
            val incomplete = syncIncomplete(syncMarkFor(settingsValue, type), state)
            val stamp = if (incomplete) NEEDS_UPDATING_STAMP else stampFor(lastMs, now)
            val status = when {
                !session.signedIn -> TileStatus.Hidden // resolving or signed out
                motionStatus != null && silent -> stamp
                motionStatus != null -> motionStatus
                state is RefreshState.Error -> TileStatus.Failed(state.message)
                state is RefreshState.Loading -> if (silent) stamp else TileStatus.Updating
                else -> stamp
            }
            val (channels, categories) = countsFor(counts, type)
            DashboardTile(
                type = type,
                title = tileTitle(type),
                unit = tileUnit(type),
                channels = channels,
                categories = categories,
                counts = tileCounts(
                    signedIn = session.signedIn,
                    resolved = session.resolved,
                    state = state,
                    channels = channels,
                    categories = categories,
                    unit = tileUnit(type),
                    lastRefreshedAtUtcMillis = lastMs,
                    incomplete = incomplete,
                ),
                status = status,
                // #195: "Needs updating" already says it; the badge is for old data (DESIGN.md).
                showStaleBadge = !incomplete && status is TileStatus.Stamp &&
                    status.age == TileStatus.AgeTier.STALE && settingsValue.refreshOnStart,
                refreshState = state,
                lastRefreshedAtUtcMillis = lastMs,
                incomplete = incomplete,
            )
        }

        val allErrored = tiles.all { it.refreshState is RefreshState.Error }
        val lastGoodMs = tiles.maxOf { it.lastRefreshedAtUtcMillis }
        val oldestGoodMs = tiles.filter { it.lastRefreshedAtUtcMillis > 0L }
            .minOfOrNull { it.lastRefreshedAtUtcMillis } ?: 0L

        return DashboardUiState(
            tiles = tiles,
            isSignedIn = session.signedIn,
            sessionResolved = session.resolved,
            refreshOnStart = settingsValue.refreshOnStart,
            showConnectionBanner = allErrored,
            connectionDetailsExpanded = _ui.value.connectionDetailsExpanded && allErrored,
            connectionMessage = if (allErrored) {
                buildString {
                    append("Nothing refreshed.")
                    if (oldestGoodMs > 0L) {
                        append(" Your last successful update was ${agoPhrase(oldestGoodMs, now)}.")
                    }
                }
            } else {
                ""
            },
            lastGoodUpdateText = if (lastGoodMs > 0L) longFormTime(lastGoodMs, now) else "Never",
            serverLabel = server,
            lastSignedInAtUtcMillis = session.lastSignedInAtUtcMillis,
            menuTile = menu.type,
            menuShowingAbout = menu.showingAbout,
            aboutLastUpdated = aboutText(menu.type, tiles, now),
        )
    }

    /** Stable factory so the Activity can pass dependencies without Hilt. */
    class Factory(
        private val coordinator: RefreshGateway = Graph.refreshCoordinator,
        private val settings: SettingsRepository = Graph.settings,
        private val database: HappyTvDatabase = Graph.database,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return DashboardViewModel(
                coordinator = coordinator,
                settings = settings,
                database = database,
                // No auth providers: the ViewModel observes coordinator.sessionState
                // directly — the same flow the login flow publishes into (F-45).
            ) as T
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Pure helpers (internal for tests)                                         */
/* -------------------------------------------------------------------------- */

/** Internal: the four DAO counts the UI needs. */
internal data class CatalogCounts(
    val liveChannels: Int = 0,
    val vodChannels: Int = 0,
    val seriesChannels: Int = 0,
    val liveCategories: Int = 0,
    val vodCategories: Int = 0,
    val seriesCategories: Int = 0,
)

/**
 * #163: the counts to show once [fresh] has been read. A type whose refresh is [RefreshState.Loading]
 * keeps its [previous] numbers, because its tables are only partly written.
 */
internal fun settleCounts(
    previous: CatalogCounts,
    fresh: CatalogCounts,
    progress: RefreshProgress,
): CatalogCounts {
    val live = progress.live is RefreshState.Loading
    val vod = progress.vod is RefreshState.Loading
    val series = progress.series is RefreshState.Loading
    return CatalogCounts(
        liveChannels = if (live) previous.liveChannels else fresh.liveChannels,
        liveCategories = if (live) previous.liveCategories else fresh.liveCategories,
        vodChannels = if (vod) previous.vodChannels else fresh.vodChannels,
        vodCategories = if (vod) previous.vodCategories else fresh.vodCategories,
        seriesChannels = if (series) previous.seriesChannels else fresh.seriesChannels,
        seriesCategories = if (series) previous.seriesCategories else fresh.seriesCategories,
    )
}

/** What the long-press menu is showing. */
internal data class LongPressMenuState(
    val type: StreamType?,
    val showingAbout: Boolean,
) {
    companion object {
        val NONE = LongPressMenuState(null, false)
    }
}

internal fun tileTitle(type: StreamType): String = when (type) {
    StreamType.LIVE -> "Live TV"
    StreamType.VOD -> "Movies"
    StreamType.SERIES -> "Shows"
}

internal fun tileUnit(type: StreamType): String = when (type) {
    StreamType.LIVE -> "channels"
    StreamType.VOD -> "movies"
    StreamType.SERIES -> "shows"
}

internal fun syncMarkFor(settingsValue: AppSettings, type: StreamType): SyncMark = when (type) {
    StreamType.LIVE -> settingsValue.liveSyncMark
    StreamType.VOD -> settingsValue.vodSyncMark
    StreamType.SERIES -> settingsValue.seriesSyncMark
}

/** The pill text of a tile whose last sync was cut short (#195). */
internal val NEEDS_UPDATING_STAMP = TileStatus.Stamp("Needs updating", TileStatus.AgeTier.STALE)

/**
 * #195: are the stored rows a partial catalog? A sync marks the tile before its first chunk and
 * clears the mark when it ends. [SyncMark.PARTIAL] is never trusted. [SyncMark.RUNNING] means a
 * sync over a complete catalog: while that sync is Loading in this process the old numbers stand
 * (#190), but a RUNNING mark met in any other state was left by a process that died mid-sync.
 */
internal fun syncIncomplete(mark: SyncMark, state: RefreshState): Boolean = when (mark) {
    SyncMark.NONE -> false
    SyncMark.PARTIAL -> true
    SyncMark.RUNNING -> state !is RefreshState.Loading
}

internal fun lastRefreshFor(settingsValue: AppSettings, type: StreamType): Long = when (type) {
    StreamType.LIVE -> settingsValue.lastLiveRefreshUtcMillis
    StreamType.VOD -> settingsValue.lastVodRefreshUtcMillis
    StreamType.SERIES -> settingsValue.lastSeriesRefreshUtcMillis
}

/**
 * F-41: does this tile's data justify a silent refresh on return from a child
 * screen?
 *
 * A `lastRefreshedAtUtcMillis` of 0 means "never successfully marked", not
 * "infinitely old data": [com.rysal.happytv.data.network.RefreshCoordinator]
 * only calls `settings.markRefreshed(type)` when that type's refresh fully
 * succeeds, so a type that errored or only partially loaded on sign-in keeps
 * 0 forever — under the old rule every BACK fired a full `refreshAll()` even
 * though the tiles had just loaded.
 *
 * The rule, per tile:
 *  - stamped (`> 0`): stale only when the stamp is at least [SILENT_REFRESH_AGE_MS] old;
 *  - unstamped (`<= 0`): stale only when this process hasn't finished loading
 *    that type ([RefreshState.Done]) — loaded-but-unmarked counts as fresh;
 *    Idle / Loading / Error with no stamp means there is no data to serve, so
 *    a refresh is still the right call.
 */
internal fun staleForReturnRefresh(
    lastRefreshedAtUtcMillis: Long,
    refreshState: RefreshState,
    now: Long,
    incomplete: Boolean = false,
): Boolean = when {
    incomplete -> true
    lastRefreshedAtUtcMillis > 0L ->
        now - lastRefreshedAtUtcMillis >= SILENT_REFRESH_AGE_MS
    else -> refreshState !is RefreshState.Done
}

private fun countsFor(counts: CatalogCounts, type: StreamType): Pair<Int, Int> = when (type) {
    StreamType.LIVE -> counts.liveChannels to counts.liveCategories
    StreamType.VOD -> counts.vodChannels to counts.vodCategories
    StreamType.SERIES -> counts.seriesChannels to counts.seriesCategories
}

/** Signed out → one prompt line; zero rows mid-first-load → placeholders. */
internal fun tileCounts(
    signedIn: Boolean,
    resolved: Boolean = true,
    state: RefreshState,
    channels: Int,
    categories: Int,
    unit: String,
    lastRefreshedAtUtcMillis: Long = 1L,
    incomplete: Boolean = false,
): TileCounts {
    // Unknown session: the first-load placeholder look, never a prompt or a 0.
    if (!resolved) return TileCounts.Placeholders
    if (!signedIn) return TileCounts.SignedOut("Sign in to load $unit")
    // #195: a sync cut short leaves rows without their categories; never present that as a total.
    if (incomplete) return TileCounts.Placeholders
    val settling = state is RefreshState.Idle || state is RefreshState.Loading
    if (channels == 0 && categories == 0 && settling) return TileCounts.Placeholders
    // #163: never refreshed to the end (first load, or a run cut short): what is stored is partial.
    if (lastRefreshedAtUtcMillis <= 0L && settling) return TileCounts.Placeholders
    return TileCounts.Lines(formatCount(channels, unit), formatCount(categories, "categories"))
}


/**
 * The stamp per the design spec: "Updated just now" for the first 60 s, then by
 * the minute, up to "Updated 3 hours ago". Never-refreshed says so.
 */
internal fun stampFor(lastRefreshedAtUtcMillis: Long, now: Long): TileStatus.Stamp {
    if (lastRefreshedAtUtcMillis <= 0L) {
        return TileStatus.Stamp("Not yet refreshed", TileStatus.AgeTier.RECENT)
    }
    val deltaMin = ((now - lastRefreshedAtUtcMillis) / 60_000L).toInt().coerceAtLeast(0)
    return when {
        deltaMin < 1 -> TileStatus.Stamp("Updated just now", TileStatus.AgeTier.FRESH)
        deltaMin < 60 ->
            TileStatus.Stamp("Updated ${minutePhrase(deltaMin)}", TileStatus.AgeTier.RECENT)
        else -> TileStatus.Stamp("Updated ${hourPhrase(deltaMin)}", TileStatus.AgeTier.STALE)
    }
}

/** "3 hours ago" / "12 minutes ago" / "just now" — for banner + About copy. */
internal fun agoPhrase(lastRefreshedAtUtcMillis: Long, now: Long): String {
    if (lastRefreshedAtUtcMillis <= 0L) return "never"
    val deltaMin = ((now - lastRefreshedAtUtcMillis) / 60_000L).toInt().coerceAtLeast(0)
    return when {
        deltaMin < 1 -> "just now"
        deltaMin < 60 -> minutePhrase(deltaMin)
        else -> hourPhrase(deltaMin)
    }
}

private fun minutePhrase(deltaMin: Int): String =
    if (deltaMin == 1) "1 minute ago" else "$deltaMin minutes ago"

private fun hourPhrase(deltaMin: Int): String {
    val hours = deltaMin / 60
    return if (hours <= 1) "1 hour ago" else "$hours hours ago"
}

/**
 * Long-form clock with AM/PM (the one place it appears — design spec: "AM/PM only
 * in long-form times"): "Today at 5:12 PM", "15 Oct at 9:03 AM".
 */
internal fun longFormTime(epochMillis: Long, now: Long): String {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val hour12 = then.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
    val minute = "%02d".format(then.get(java.util.Calendar.MINUTE))
    val amPm = if (then.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
    val time = "$hour12:$minute $amPm"
    val sameDay = then.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
        then.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        "Today at $time"
    } else {
        val month = then.getDisplayName(
            java.util.Calendar.MONTH,
            java.util.Calendar.SHORT,
            Locale.US,
        )
        "${then.get(java.util.Calendar.DAY_OF_MONTH)} $month at $time"
    }
}

/** "Today at 4:31 PM (12 minutes ago)" for the long-press About view. */
internal fun aboutText(
    type: StreamType?,
    tiles: List<DashboardTile>,
    now: Long,
): String {
    if (type == null) return "Never"
    val tile = tiles.firstOrNull { it.type == type } ?: return "Never"
    if (tile.lastRefreshedAtUtcMillis <= 0L) return "Never"
    return "${longFormTime(tile.lastRefreshedAtUtcMillis, now)} " +
        "(${agoPhrase(tile.lastRefreshedAtUtcMillis, now)})"
}

/**
 * The state before any upstream signal lands — three tiles in the neutral
 * first-load look. The session is *unresolved*, not signed out (issue #30):
 * a cold start must never flash the signed-out dashboard.
 */
internal fun initialUiState(): DashboardUiState = DashboardUiState(
    sessionResolved = false,
    tiles = StreamType.entries.map { type ->
        DashboardTile(
            type = type,
            title = tileTitle(type),
            unit = tileUnit(type),
            channels = 0,
            categories = 0,
            counts = TileCounts.Placeholders,
            status = TileStatus.Hidden,
            showStaleBadge = false,
            refreshState = RefreshState.Idle,
            lastRefreshedAtUtcMillis = 0L,
        )
    },
)

private const val STAMP_TICK_MS = 15_000L
private const val SILENT_REFRESH_AGE_MS = 30 * 60_000L

/** #157: how long after the dashboard appears the start-up refresh begins. */
internal const val STARTUP_REFRESH_DELAY_MS = 8_000L

/** #157: a tile refreshed more recently than this is not refreshed again at start-up. */
internal const val STARTUP_REFRESH_AGE_MS = SILENT_REFRESH_AGE_MS

/**
 * #157: which tiles the start-up refresh updates, in the order it runs them
 * (cheapest list first, so Live TV is current soonest).
 *
 * A tile is left alone when this process is already loading it or has loaded it
 * (the sign-in refresh), or when its last good refresh is under
 * [STARTUP_REFRESH_AGE_MS] old. A tile never refreshed (stamp 0) is stale.
 */
internal fun startupTilesToRefresh(
    settingsValue: AppSettings,
    progress: RefreshProgress,
    now: Long,
): List<StreamType> = listOf(StreamType.LIVE, StreamType.SERIES, StreamType.VOD).filter { type ->
    val state = when (type) {
        StreamType.LIVE -> progress.live
        StreamType.VOD -> progress.vod
        StreamType.SERIES -> progress.series
    }
    val stamp = lastRefreshFor(settingsValue, type)
    // #195: an incomplete tile is refreshed whatever its age (#175's age rule is for complete ones).
    val incomplete = syncIncomplete(syncMarkFor(settingsValue, type), state)
    state !is RefreshState.Loading && state !is RefreshState.Done &&
        (incomplete || stamp <= 0L || now - stamp >= STARTUP_REFRESH_AGE_MS)
}

/** Motion beats (design spec "Tile refresh motion"). */
private const val FILL_MS = 200L
private const val HOLD_MS = 60L
private const val COLLAPSE_MS = 200L
