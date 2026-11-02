@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.rysal.happytv.ui.guide

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.data.guide.GuideRepository
import com.rysal.happytv.data.guide.GuideWindow
import com.rysal.happytv.perf.HtvPerf
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The data behind the Guide view of the combined Live TV screen (issue #15).
 *
 * Live TV's own ViewModel owns the selection and its channels; this one only
 * answers "what is on those channels, in this two-hour window". The screen hands
 * it the selection's channels with [setChannels]; nothing here knows about the
 * category rail, focus, or navigation any more (Compose owns focus).
 *
 * Sources:
 *  - the `epg_programs` store via [GuideRepository], read for **one window of
 *    channels** at a time ([ROW_PAGE] rows, widened as the D-pad nears the end,
 *    #10) with titles and times only;
 *  - a 15 s "now" ticker that also slides the window at each half-hour mark;
 *  - provider-mode **on-demand fetches**: shallow (now + next) for rows reported
 *    visible, deep when focus lands on a channel - both write through to the
 *    store, so the flow round-trip rebuilds the grid.
 *
 * Until the first window has answered the state is "loading", never "No guide
 * yet" (#43): [GuideUiState.gridReady] is false and the empty rule is not applied.
 */
class GuideViewModel(
    private val repository: GuideRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowProvider: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** The selection's channels, tagged with the selection that produced them. */
    private class Input(val tag: Any?, val channels: List<GuideChannel>)

    private val input = MutableStateFlow<Input?>(null)
    private val rowLimit = MutableStateFlow(ROW_PAGE)
    private val xmltvMode = MutableStateFlow<Boolean?>(null) // null until settings answer
    private val now = MutableStateFlow(nowProvider())
    private val day = MutableStateFlow(GuideDay.TODAY)

    /**
     * #146: where the grid has moved forward to (a half-hour aligned window start), or null
     * while it sits on the day's starting window and follows Now.
     */
    private val shiftedStart = MutableStateFlow<Long?>(null)

    /** One window of the grid with the data behind it. */
    private class GridData(
        val tag: Any?,
        val channels: List<GuideChannel>,
        val programs: Map<String, List<GuideProgram>>,
        /** The time range [programs] were read for (#146): rows are built for any window inside it. */
        val range: GuideTime.LoadRange,
        val counts: Map<String, Int>,
        val storeHasGuide: Boolean,
        val xmltv: Boolean,
        /** The window holds every channel of the selection. */
        val exhausted: Boolean,
    )

    private val grid = MutableStateFlow<GridData?>(null)

    /** `guide.window.ready`: construction to the first filled window (rows + programmes). */
    private val createdAtNanos = System.nanoTime()
    private var firstWindowLogged = false

    // Row cache: rows depend on the grid data, day and `now`; the details dialog
    // changes none of them (#10), so rebuild() reuses them.
    private var cachedRows: List<GuideRow>? = null
    private var cachedRowsGrid: GridData? = null
    private var cachedRowsDay: GuideDay? = null
    private var cachedRowsNow: Long = Long.MIN_VALUE
    private var cachedRowsStart: Long = Long.MIN_VALUE

    private val _ui = MutableStateFlow(
        buildGuideState(
            channels = emptyList(),
            programsByKey = emptyMap(),
            guideCounts = emptyMap(),
            xmltvMode = false,
            day = GuideDay.TODAY,
            nowUtcMillis = now.value,
            zone = zone,
            // Nothing is known yet: loading, not "No guide yet" (#43).
            loaded = false,
        ),
    )

    /** Everything the grid renders. */
    val ui: StateFlow<GuideUiState> = _ui.asStateFlow()

    /** Fetch bookkeeping - sets mark intent before the coroutine so two */
    private val fetchedShallow = HashSet<String>()
    private val fetchedDeep = HashSet<String>()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val xmltv = repository.isXmltvEnabled()
            xmltvMode.value = xmltv
            // First open with XMLTV selected but nothing stored yet: kick a
            // fetch now instead of waiting for the periodic worker, so the
            // empty state doesn't outstay its welcome.
            if (xmltv && !repository.hasAnyGuide()) {
                launch {
                    runCatching { repository.fetchAndStoreXmlTv() }
                        .onFailure { Log.w(TAG, "initial XMLTV fetch failed: ${it.message}") }
                }
            }
        }

        // The grid's data: the selection's channels (a window), then exactly their
        // programmes and counts for the window. Each step restarts the next
        // (flatMapLatest), and the result is published only once everything for
        // this window has answered.
        viewModelScope.launch {
            // The programmes held cover a block of time around the window (#146): a half-hour
            // step inside it reads nothing, a step out of it reads the next block once.
            val rangeFlow = combine(now, day, shiftedStart) { n, d, shifted ->
                val base = GuideWindow.fromNow(n, zone, d.dayOffset).startUtcMillis
                GuideTime.loadRange(base, GuideTime.windowStart(base, d, shifted))
            }.distinctUntilChanged()
            combine(input.filterNotNull(), rowLimit, xmltvMode.filterNotNull(), rangeFlow) { sel, limit, xmltv, range ->
                Query(sel, limit, xmltv, range)
            }.flatMapLatest { query ->
                val shown = query.input.channels.take(query.limit)
                val keys = shown.mapNotNull { it.programKey(query.xmltv) }
                combine(
                    repository.observeWindowWithCounts(keys, query.range.from, query.range.to),
                    repository.observeHasAnyGuide(),
                ) { windowAndCounts, hasAny ->
                    GridData(
                        tag = query.input.tag,
                        channels = shown,
                        programs = windowAndCounts.first,
                        range = query.range,
                        counts = windowAndCounts.second,
                        storeHasGuide = hasAny,
                        xmltv = query.xmltv,
                        exhausted = query.input.channels.size <= query.limit,
                    )
                }
            }
                // The store reads run on Default: collected on Main they waited behind the
                // frames composing the screen (#52, ~100 ms of a cold open).
                .flowOn(Dispatchers.Default)
                .collect { data ->
                val begun = System.nanoTime()
                grid.value = data
                rebuild()
                if (!firstWindowLogged) {
                    firstWindowLogged = true
                    HtvPerf.log(
                        "guide.window.ready",
                        (System.nanoTime() - createdAtNanos) / 1_000_000L,
                        "rows=${data.channels.size} build=${(System.nanoTime() - begun) / 1_000_000L}ms",
                    )
                }
            }
        }

        // 15 s ticker - Now pill, now line, window boundary.
        viewModelScope.launch {
            while (true) {
                // Real time on Default: a virtual-time delay under a test Main dispatcher would tick endlessly.
                withContext(Dispatchers.Default) { delay(TICK_MS) }
                now.value = nowProvider()
                rebuild()
            }
        }
    }

    private class Query(val input: Input, val limit: Int, val xmltv: Boolean, val range: GuideTime.LoadRange)

    // ---------------------------------------------------------------- input

    /**
     * The selection's channels. [tag] identifies the selection (the screen compares
     * it with [GuideUiState.tag] to know the rows are for what the rail says). A new
     * [tag] starts on a fresh first window; the same tag with a different list
     * (a favorite toggled, a page loaded) keeps the window.
     */
    fun setChannels(tag: Any?, channels: List<GuideChannel>) {
        val previous = input.value
        if (previous != null && previous.tag == tag && previous.channels == channels) return
        if (previous == null || previous.tag != tag) {
            rowLimit.value = rowFloor?.takeIf { it.first == tag }?.second?.coerceAtLeast(ROW_PAGE) ?: ROW_PAGE
        }
        input.value = Input(tag, channels)
    }

    /**
     * Holds at least [count] rows (rounded up to whole [ROW_PAGE]s), so a channel deep in the
     * selection is part of the grid: the hold-return jump to the playing channel (#138).
     */
    fun showRows(tag: Any?, count: Int) {
        val needed = ((count + ROW_PAGE - 1) / ROW_PAGE) * ROW_PAGE
        // Remembered for [tag]: the selection's channels may reach [setChannels] after this
        // call, and a new tag starts on a fresh first window, which must not undo it.
        rowFloor = tag to needed
        rowLimit.update { limit -> maxOf(limit, needed) }
    }

    private var rowFloor: Pair<Any?, Int>? = null

    /**
     * The header's day picker. Choosing a day also snaps the grid back to that day's starting
     * window (for Today, the one with Now in it), so choosing the day it already shows is how
     * to get back from a grid that has moved forward (#146).
     */
    fun setDay(value: GuideDay) {
        if (day.value == value && shiftedStart.value == null) return
        shiftedStart.value = null
        day.value = value
        rebuild()
    }

    /** Back to the starting window of the selected day (the Now window on Today), #146. */
    fun resetToNow() {
        if (shiftedStart.value == null) return
        shiftedStart.value = null
        rebuild()
    }

    /** The window start the grid is (about to be) showing; the screen compares it with its rows. */
    fun currentWindowStart(): Long = windowStartNow()

    private fun baseStart(): Long = GuideWindow.fromNow(now.value, zone, day.value.dayOffset).startUtcMillis

    private fun windowStartNow(): Long = GuideTime.windowStart(baseStart(), day.value, shiftedStart.value)

    /**
     * #146: Left or Right on [from] when the grid's own rule has no program to go to, or when
     * the grid is not up to date with an earlier move. Moves the window if the target needs it
     * and returns the program to focus; null when the key goes nowhere in time. Cheap: one
     * row's programmes, no query.
     */
    fun moveInTime(from: GuideFocus.Program, key: GuideKey): GuideFocus.Program? {
        val loaded = grid.value ?: return null
        val channel = loaded.channels.getOrNull(from.row) ?: return null
        val programs = channel.programKey(loaded.xmltv)?.let { loaded.programs[it] } ?: return null
        val nowMillis = now.value
        val base = baseStart()
        val start = windowStartNow()
        val move = when (key) {
            GuideKey.RIGHT -> {
                if (day.value == GuideDay.YESTERDAY) return null
                GuideTime.forward(
                    from, programs, channel.hasReplay, start, loaded.range.to, nowMillis,
                    GuideTime.lastWindowStart(nowMillis, zone),
                )
            }
            GuideKey.LEFT -> GuideTime.backward(from, programs, channel.hasReplay, start, base, nowMillis)
            else -> null
        } ?: return null
        if (move.windowStart != start) {
            shiftStartedNanos = System.nanoTime()
            shiftedStart.value = if (move.windowStart <= base) null else move.windowStart
            rebuild()
        }
        return move.focus
    }

    private var shiftStartedNanos = 0L

    /**
     * Focus is near the end of the loaded rows: widen the window by one page.
     * A no-op once the window holds the whole selection.
     */
    fun widenWindowNear(rowIndex: Int) {
        val loaded = grid.value ?: return
        if (loaded.exhausted) return
        if (rowIndex < loaded.channels.size - ROW_LOOKAHEAD) return
        rowLimit.update { limit -> if (limit == loaded.channels.size) limit + ROW_PAGE else limit }
    }

    /** The UI reports which rows are on screen -> shallow fetch them (provider mode). */
    fun onVisibleChannels(streamIds: List<String>) {
        if (xmltvMode.value != false) return
        streamIds.forEach { ensureFetch(it, deep = false) }
    }

    /** Focus landed on a channel's row -> its deeper guide (provider mode). */
    fun onFocusLanded(streamId: String) {
        ensureFetch(streamId, deep = true)
    }

    /** Coming back from the player / settings: refresh "now", re-derive. */
    fun onResumed() {
        now.value = nowProvider()
        rebuild()
    }

    // ---------------------------------------------------------------- state

    private fun rebuild() {
        val loaded = grid.value
        val currentDay = day.value
        val nowMillis = now.value
        val base = GuideWindow.fromNow(nowMillis, zone, currentDay.dayOffset).startUtcMillis
        val start = GuideTime.windowStart(base, currentDay, shiftedStart.value)
        // The window has left what was read (a step past the loaded block): keep showing the
        // last grid until the next block lands, which calls this again, rather than a flash of
        // striped "No guide info" rows.
        if (loaded != null && !loaded.range.covers(start)) return
        val reuseRows = loaded != null &&
            cachedRows != null &&
            cachedRowsGrid === loaded &&
            cachedRowsDay == currentDay &&
            cachedRowsNow == nowMillis &&
            cachedRowsStart == start
        val built = buildGuideState(
            channels = loaded?.channels ?: emptyList(),
            programsByKey = loaded?.programs ?: emptyMap(),
            guideCounts = loaded?.counts ?: emptyMap(),
            xmltvMode = loaded?.xmltv ?: xmltvMode.value ?: false,
            day = GuideTime.dayFor(currentDay, start, base, nowMillis, zone),
            nowUtcMillis = nowMillis,
            zone = zone,
            windowStartUtcMillis = start.takeIf { it != base },
            prebuiltRows = if (reuseRows) cachedRows else null,
            // Until the first grid query returns we know nothing about the store:
            // that is "loading", never "No guide yet" (#43: a guide that existed
            // flashed the empty state for 12 to 19 s while an import staged).
            loaded = loaded != null,
            storeHasGuide = loaded?.storeHasGuide ?: true,
        )
        if (!reuseRows) {
            cachedRows = built.rows
            cachedRowsGrid = loaded
            cachedRowsDay = currentDay
            cachedRowsNow = nowMillis
            cachedRowsStart = start
            if (shiftStartedNanos != 0L) {
                HtvPerf.log("guide.shift", (System.nanoTime() - shiftStartedNanos) / 1_000_000L, "rows=${built.rows.size}")
                shiftStartedNanos = 0L
            }
        }
        _ui.value = built.copy(gridReady = loaded != null, tag = loaded?.tag)
    }

    private fun ensureFetch(streamId: String, deep: Boolean) {
        if (xmltvMode.value != false) return
        if (streamId in fetchedDeep) return
        if (!deep && streamId in fetchedShallow) return
        val mark = if (deep) fetchedDeep else fetchedShallow
        mark.add(streamId)
        viewModelScope.launch {
            try {
                val listings = repository.fetchProviderListings(
                    streamId,
                    if (deep) DEEP_FETCH_LIMIT else SHALLOW_FETCH_LIMIT,
                )
                if (listings == null) {
                    // Signed out - nobody to ask. Leave the stored rows
                    // alone and un-mark so a signed-in open fetches them.
                    mark.remove(streamId)
                } else {
                    repository.storeProviderPrograms(streamId, listings)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mark.remove(streamId)
                Log.w(TAG, "guide fetch failed for $streamId: ${e.message}")
            }
        }
    }

    /**
     * Manual-DI factory (Graph) - same convention as
     * DashboardViewModel.Factory: nested in the class, not the companion
     * (a companion-nested classifier doesn't resolve as `VM.Factory`).
     */
    class Factory(
        private val repository: GuideRepository = Graph.guideRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GuideViewModel(repository) as T
    }

    companion object {
        private const val TAG = "GuideViewModel"
        const val TICK_MS = 15_000L

        /** Channel rows loaded per page (#10): the first window, and each widening. */
        const val ROW_PAGE = 120

        /** Widen when focus is within this many rows of the loaded end. */
        const val ROW_LOOKAHEAD = 24

        /** Cold-start/visible rows get "now + next" only. */
        const val SHALLOW_FETCH_LIMIT = 4

        /** Focus-land fetches cache 4 hours of listings. */
        const val DEEP_FETCH_LIMIT = 16
    }
}
