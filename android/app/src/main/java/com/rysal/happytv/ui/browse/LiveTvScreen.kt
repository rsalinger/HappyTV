package com.rysal.happytv.ui.browse

import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rysal.happytv.data.LiveTvView
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.data.guide.knownGenre
import com.rysal.happytv.data.guide.toGuideProgram
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.focus.FOCUS_WAIT_TIMEOUT_MS
import com.rysal.happytv.ui.focus.awaitFocus
import com.rysal.happytv.ui.focus.focusTargetAfterRemoval
import com.rysal.happytv.ui.player.BackKeyGate
import com.rysal.happytv.ui.guide.GuideBody
import com.rysal.happytv.ui.guide.GuideDay
import com.rysal.happytv.ui.guide.GuideDetailsDialog
import com.rysal.happytv.ui.guide.GuideDialogUi
import com.rysal.happytv.ui.guide.GuideFocus
import com.rysal.happytv.ui.guide.GuideKey
import com.rysal.happytv.ui.guide.guideReturnsToNow
import com.rysal.happytv.ui.guide.GuideRow
import com.rysal.happytv.ui.guide.GuideUiState
import com.rysal.happytv.ui.guide.GuideViewModel
import com.rysal.happytv.ui.guide.HeaderSegments
import com.rysal.happytv.ui.guide.InfoBandUi
import com.rysal.happytv.ui.guide.ROW_HEIGHT_PX
import com.rysal.happytv.ui.guide.channelLineOf
import com.rysal.happytv.ui.guide.dialogFor
import com.rysal.happytv.ui.guide.infoBandFor
import com.rysal.happytv.ui.guide.infoBandForFocus
import com.rysal.happytv.ui.guide.infoBlockFor
import com.rysal.happytv.ui.guide.infoBandNoGuide
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.HappyTvIcons
import com.rysal.happytv.ui.theme.tabular

/**
 * The combined Live TV screen (spec #7 section 3, issues #14 and #15): header, an
 * info band with a static 448 x 252 preview slot, and one of two bodies - the
 * TV guide grid (default) or a compact channel list - chosen by the header's
 * Guide | List toggle and remembered. The category rail is an accordion in both.
 * The separate guide screen no longer exists.
 *
 * Layout, in artboard px through [browseDesign]:
 *  - header at (96, 54), 1728 x 72;
 *  - info band at top 142, 252 tall: the focused item's details on the left, the
 *    preview slot at x 1376 (channel art only; the player lands there later, so
 *    nothing opaque is designed to sit over that rectangle, F-48);
 *  - the channel list from top 410, 64 tall rows 4 apart;
 *  - the rail: a 600 px container at x 56 with 40 px padding (glow room), items
 *    at x 96, 400 x 64, 8 apart.
 *
 * D-pad (spec #7 section 3.2): focus moving through the rail loads nothing. OK
 * or Right on a category selects it, loads its channels, folds the rail away
 * (200 ms) and focuses the first channel. Left from a channel row, or Back from
 * the channels, fans the rail out with focus on the selected category; Back
 * from the rail leaves Live TV. "Keep categories open" makes the rail a
 * permanent left pane with no animation. The list behind the overlay is never
 * re-laid out: it is always placed as if the rail were folded away.
 */
@Composable
fun LiveTvScreen(
    viewModelFactory: ViewModelProvider.Factory = BrowseViewModel.Factory(),
    onBack: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onPlayChannel: (LiveStream) -> Unit = {},
    onWatchFromStart: (LiveStream) -> Unit = {},
    onRefreshChannels: () -> Unit = {},
    /**
     * Spec #7 section 3.6: the dashboard's TV guide tile asks for the guide whatever
     * view was used last (not remembered); null follows the stored choice.
     */
    startView: LiveTvView? = null,
    /** Replay of one program (the guide dialog's "Watch from the start"). */
    onPlayReplay: (streamId: String, startUtcMillis: Long, durationMinutes: Int) -> Unit = { _, _, _ -> },
    /** The empty guide's "Open guide settings" (Settings, Guide section). */
    onOpenGuideSettings: () -> Unit = {},
    /** The guide grid's data; null builds the real one from [com.rysal.happytv.data.Graph]. */
    guideViewModelFactory: ViewModelProvider.Factory? = null,
    /** The activity's player host (#16); null when Live TV is shown without one (tests). */
    playback: LivePlayback? = null,
) {
    val viewModel: BrowseViewModel = viewModel(factory = viewModelFactory)
    // Applied synchronously, before the first state is collected, so the wrong view
    // never flashes on the way in.
    remember(viewModel) { startView?.let(viewModel::showViewForThisVisit) }
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val channelMenu by viewModel.channelMenu.collectAsStateWithLifecycle()
    val moreMenuOpen by viewModel.moreMenuOpen.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The guide's ViewModel is made as soon as the screen is up (or while the stored view is
    // still unknown) so its settings read overlaps the channel load; the list view, once known,
    // never touches it. It idles until [GuideViewModel.setChannels] gives it channels.
    val guideViewModel: GuideViewModel? = if (state.isLoading || state.view == LiveTvView.GUIDE) {
        viewModel(factory = guideViewModelFactory ?: remember { GuideViewModel.Factory() })
    } else {
        null
    }
    val guide: GuideUiState? = guideViewModel?.let { it.ui.collectAsStateWithLifecycle().value }
    // The selection's channels reach the guide the moment the ViewModel has them: a collector,
    // not a composition-time effect, which waited for the frame that drew the new state (#52).
    LaunchedEffect(guideViewModel, viewModel) {
        if (guideViewModel == null) return@LaunchedEffect
        viewModel.ui
            .filter { !it.isLoading && it.view == LiveTvView.GUIDE }
            .map { shown -> GuideInput(shown.selectedSelection, shown.channels, shown.categories) }
            .distinctUntilChanged()
            .collect { input ->
                guideViewModel.setChannels(input.selection, input.channels.toGuideChannels(input.categories))
            }
    }

    // Leaving the guide (to the list view, out of Live TV) forgets how far it was moved:
    // coming back starts at the Now window again (#146).
    DisposableEffect(guideViewModel) {
        onDispose { guideViewModel?.setDay(GuideDay.TODAY) }
    }

    LiveTvContent(
        state = state,
        guide = guide,
        onBack = onBack,
        onSearchClick = onSearchClick,
        onSelectCategory = viewModel::selectCategory,
        onJumpToCategory = { selection, minRows -> viewModel.selectCategory(selection, minRows) },
        onSelectView = viewModel::setView,
        onGuideDay = { guideViewModel?.setDay(it) },
        onGuideTimeMove = { from, key -> guideViewModel?.moveInTime(from, key) },
        onGuideWindowStart = { guideViewModel?.currentWindowStart() ?: 0L },
        onGuideBackToNow = { guideViewModel?.resetToNow() },
        onGuideWiden = { guideViewModel?.widenWindowNear(it) },
        onGuideShowRows = { tag, rows -> guideViewModel?.showRows(tag, rows) },
        onGuideVisible = { guideViewModel?.onVisibleChannels(it) },
        onGuideFocusLanded = { guideViewModel?.onFocusLanded(it) },
        onOpenGuideSettings = onOpenGuideSettings,
        onPlayReplay = { streamId, start, minutes ->
            viewModel.recordWatched(streamId)
            onPlayReplay(streamId, start, minutes)
        },
        loadProgram = { streamId, epgChannelId, at ->
            viewModel.programFor(streamId, epgChannelId, at)?.toGuideProgram()
        },
        onPlayChannel = { stream ->
            viewModel.recordWatched(stream.id)
            onPlayChannel(stream)
        },
        onToggleFavorite = viewModel::toggleFavorite,
        onLongPressChannel = viewModel::openChannelMenu,
        onCloseChannelMenu = viewModel::closeChannelMenu,
        onWatchFromStart = { stream ->
            viewModel.closeChannelMenu()
            viewModel.recordWatched(stream.id)
            // Replay the program that is on now, from its start; a channel with no
            // guide row has nothing to rewind to, so it opens live as before.
            scope.launch {
                val program = viewModel.programFor(stream.id, stream.epgChannelId, System.currentTimeMillis())
                if (program != null) {
                    onPlayReplay(
                        stream.id,
                        program.startUtcMillis,
                        program.toGuideProgram().durationMinutes,
                    )
                } else {
                    onWatchFromStart(stream)
                }
            }
        },
        onHideChannel = { stream -> viewModel.hideChannel(stream.id) },
        onRemoveFromRecentlyWatched = { stream -> viewModel.removeRecentlyWatched(stream.id) },
        onHideCategory = { categoryId -> viewModel.hideCategory(categoryId) },
        onShowHiddenChannels = viewModel::setShowHiddenChannels,
        onOpenMoreMenu = { viewModel.setMoreMenuOpen(true) },
        onCloseMoreMenu = { viewModel.setMoreMenuOpen(false) },
        onHideEmptyCategories = { viewModel.setHideEmptyCategories(it) },
        onRefreshChannels = onRefreshChannels,
        onLoadMoreChannels = viewModel::loadMoreChannels,
        channelMenu = channelMenu,
        moreMenuOpen = moreMenuOpen,
        playback = playback,
    )
}

/** What the guide is fed from Live TV's state; equality decides whether it needs feeding again. */
private data class GuideInput(
    val selection: LiveCategorySelection,
    val channels: List<LiveChannelRow>,
    val categories: List<LiveCategoryItem>,
)

/** Where the D-pad goes when it comes back from the category rail to the channels (#150). */
internal sealed interface RailReturn {
    /** The guide scrolls to the program or channel it was on, or to the first row in a new category. */
    data object Guide : RailReturn

    /** The list row the D-pad was on, scrolled into composition first. */
    data class Row(val id: String) : RailReturn

    /** The first row (a new category, or nothing was focused yet). */
    data object FirstRow : RailReturn
}

/**
 * Row 0's requester finds nothing once the list or guide has scrolled past it, so a Right from the
 * rail only worked on the first screenful. The list returns to the channel it left, in the same
 * category; the guide does the same from its own last focus. A first entry, or a new category,
 * still starts on the first row through its requester.
 */
internal fun railReturnTarget(
    guideShown: Boolean,
    guideFocusedHere: Boolean,
    channelIds: Sequence<String>,
    lastChannelId: String?,
    lastSelection: LiveCategorySelection?,
    selection: LiveCategorySelection,
): RailReturn = when {
    guideShown && guideFocusedHere && lastSelection == selection -> RailReturn.Guide
    guideShown -> RailReturn.FirstRow
    lastChannelId != null && lastSelection == selection && channelIds.any { it == lastChannelId } ->
        RailReturn.Row(lastChannelId)
    else -> RailReturn.FirstRow
}

/**
 * F-40: [FocusRequester.requestFocus] throws `IllegalStateException`
 * ("FocusRequester is not initialized") when no attached row backs this
 * requester — the rail disposes its first row when the LazyColumn scrolls it
 * out of view, and `channelsFocused` can still be true at that point.
 *
 * Returns true when focus was requested (the requester is attached), false
 * when it isn't, so callers can fall back instead of crashing.
 */
internal fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
    true
} catch (_: IllegalStateException) {
    false
}

@Composable
private fun LiveTvContent(
    state: LiveTvUiState,
    guide: GuideUiState?,
    onBack: () -> Unit,
    onSearchClick: () -> Unit,
    onSelectCategory: (LiveCategorySelection) -> Unit,
    /** Opens a category with at least [Int] rows paged in, so a channel deep in it is composed (#138). */
    onJumpToCategory: (LiveCategorySelection, Int) -> Unit = { selection, _ -> onSelectCategory(selection) },
    onSelectView: (LiveTvView) -> Unit,
    onGuideDay: (GuideDay) -> Unit,
    /** Moves the guide through time on Left/Right (#146); null when the key goes nowhere in time. */
    onGuideTimeMove: (GuideFocus.Program, GuideKey) -> GuideFocus.Program? = { _, _ -> null },
    onGuideWindowStart: () -> Long = { 0L },
    /** Back, when the guide has moved forward: return to the Now window (#146). */
    onGuideBackToNow: () -> Unit = {},
    onGuideWiden: (Int) -> Unit,
    /** Holds at least this many guide rows, so a channel deep in the selection has one (#138). */
    onGuideShowRows: (tag: Any?, rows: Int) -> Unit = { _, _ -> },
    onGuideVisible: (List<String>) -> Unit,
    onGuideFocusLanded: (String) -> Unit,
    onOpenGuideSettings: () -> Unit,
    onPlayReplay: (streamId: String, startUtcMillis: Long, durationMinutes: Int) -> Unit,
    loadProgram: suspend (streamId: String, epgChannelId: String?, atUtcMillis: Long) -> GuideProgram?,
    onPlayChannel: (LiveStream) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onLongPressChannel: (LiveStream) -> Unit,
    onCloseChannelMenu: () -> Unit,
    onWatchFromStart: (LiveStream) -> Unit,
    onHideChannel: (LiveStream) -> Unit,
    onHideCategory: (String) -> Unit,
    onRemoveFromRecentlyWatched: (LiveStream) -> Unit,
    onShowHiddenChannels: (Boolean) -> Unit,
    onOpenMoreMenu: () -> Unit,
    onCloseMoreMenu: () -> Unit,
    onHideEmptyCategories: (Boolean) -> Unit,
    onRefreshChannels: () -> Unit,
    onLoadMoreChannels: () -> Unit,
    channelMenu: LiveStream?,
    moreMenuOpen: Boolean,
    playback: LivePlayback? = null,
) {
    val d = browseDesign()
    val currentState by rememberUpdatedState(state)
    val currentGuide by rememberUpdatedState(guide)

    // ---- view (spec #7 section 3.4 / 3.6) ---------------------------------------
    // While the first answer is pending the stored view is not known yet: neither body.
    val guideMode = state.view == LiveTvView.GUIDE && !state.isLoading
    val content = channelPaneContent(state)
    // The guide's rows are for the selection the rail shows (the ViewModel tags them).
    val guideShown = guideMode && content is ChannelPaneContent.Rows &&
        guide != null && guide.gridReady && guide.tag == state.selectedSelection
    val guideShownNow by rememberUpdatedState(guideShown)
    // The guide can take a moment after the channels; waiting forever would leave the
    // D-pad on nothing if it never comes, so focus logic gives up after a while.
    var guideWaitExpired by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectedSelection, guideMode) {
        guideWaitExpired = false
        if (guideMode) {
            delay(GUIDE_READY_TIMEOUT_MILLIS)
            guideWaitExpired = true
        }
    }
    val bodyReady = !state.isLoading &&
        (!guideMode || content !is ChannelPaneContent.Rows || guideShown || guideWaitExpired)
    ReportLiveTvTimings(state, ready = bodyReady, epgByRows = guideMode)
    var screenHasFocus by remember { mutableStateOf(false) }

    // Program details dialog (Guide view): hoisted here so its scrim covers the header.
    var guideDialog by remember { mutableStateOf<GuideDialogUi?>(null) }
    var dialogReturn by remember { mutableStateOf<GuideFocus?>(null) }
    var restoreTick by remember { mutableIntStateOf(0) }
    var guideEntryTick by remember { mutableIntStateOf(0) }
    // The focused guide element, for the info band.
    // Held as State objects, not read here: a focus move must not recompose this whole screen
    // (#52). Only [LiveTvInfoRegion] reads them.
    val focusedGuide = remember { mutableStateOf<GuideFocus?>(null) }
    val closeGuideDialog: () -> Unit = {
        if (guideDialog != null) {
            HtvPerf.start(HtvPerf.GUIDE_DETAILS_CLOSE)
            guideDialog = null
            restoreTick++
        }
    }
    // `guide.details.close`: Back -> the dialog gone and a frame drawn.
    LaunchedEffect(guideDialog == null) {
        if (guideDialog != null) return@LaunchedEffect
        withFrameNanos { }
        HtvPerf.end(HtvPerf.GUIDE_DETAILS_CLOSE)
    }

    // ---- rail state (spec #7 section 3.2) -------------------------------------
    val keepOpen = state.keepCategoriesOpen
    // True once the user fanned the rail out (Left / Back). Entering with nothing
    // picked forces it open without this flag ([railVisible]).
    var railOpen by remember { mutableStateOf(false) }
    val railVisible = !state.isLoading &&
        (keepOpen || railOpen || state.selectedSelection is LiveCategorySelection.NothingPicked)
    // The rail covers the list; the list behind it takes no focus meanwhile.
    val overlayShown = railVisible && !keepOpen
    var railHasFocus by remember { mutableStateOf(false) }
    var channelsHaveFocus by remember { mutableStateOf(false) }
    var focusedRailKey by remember { mutableStateOf<String?>(null) }
    val focusedChannelId = remember { mutableStateOf<String?>(null) }
    // The category the focused channel or program was in: the D-pad returns to it only in that category (#150).
    var focusedSelection by remember { mutableStateOf<LiveCategorySelection?>(null) }
    // The list row that takes focus when the D-pad comes back from the rail (#150); its row is
    // scrolled into composition first, as for the channel menu.
    var returnRowId by remember { mutableStateOf<String?>(null) }
    var categoryMenu by remember { mutableStateOf<LiveCategoryItem.ProviderCategory?>(null) }
    // The category just chosen with OK / Right, until its channels have loaded.
    var pendingSelect by remember { mutableStateOf<LiveCategorySelection?>(null) }
    // Row to hand rail focus to instead of the selected one (a closed category menu).
    var railFocusOverride by remember { mutableStateOf<String?>(null) }
    var railFocusTick by remember { mutableIntStateOf(0) }
    var listFocusTick by remember { mutableIntStateOf(0) }
    val railEntry = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val menuRowFocus = remember { FocusRequester() }
    val railListState = rememberLazyListState()
    val entryKey = railEntryKey(state.categories, railFocusOverride)

    fun fanOutRail() {
        if (!keepOpen) railOpen = true
        railFocusTick++
    }

    // Rail focus: scroll the entry row into composition, then ask for focus,
    // retrying frame by frame while the overlay composes (no sleeps).
    LaunchedEffect(railFocusTick) {
        if (railFocusTick == 0) return@LaunchedEffect
        val items = currentState.categories
        val key = railEntryKey(items, railFocusOverride)
        railListState.scrollRailTo(
            items.indexOfFirst { it.key == key }.coerceAtLeast(0),
            keepScroll = railFocusOverride != null,
        )
        // Success is focus really being in the rail: a request that finds no
        // composed row yet does nothing, and does not throw. Bounded by time.
        if (awaitFocus(landed = { railHasFocus }, request = { railEntry.tryRequestFocus() })) {
            railFocusOverride = null
        }
    }
    // First channel: the list container's requester finds its first focusable
    // (a row, or the empty state's action). If nothing can take it, fan the
    // rail out so the D-pad is never left on nothing.
    LaunchedEffect(listFocusTick) {
        if (listFocusTick == 0) return@LaunchedEffect
        // As for the rail: only focus actually landing in the list counts.
        val landed = when (val back = railReturnTarget(
            guideShown = guideShownNow,
            guideFocusedHere = focusedGuide.value != null && currentGuide?.isEmptyState == false,
            channelIds = currentState.channels.asSequence().map { it.stream.id },
            lastChannelId = focusedChannelId.value,
            lastSelection = focusedSelection,
            selection = currentState.selectedSelection,
        )) {
            // The guide scrolls to where it was and focuses it (the first row in a new category).
            RailReturn.Guide -> {
                guideEntryTick++
                awaitFocus(landed = { channelsHaveFocus }, request = { })
            }
            // Row 0 is not composed once the list has scrolled, so its requester finds nothing (#150).
            is RailReturn.Row -> {
                returnRowId = back.id
                try {
                    awaitFocus(landed = { channelsHaveFocus }, request = { menuRowFocus.tryRequestFocus() })
                } finally {
                    returnRowId = null
                }
            }
            RailReturn.FirstRow -> awaitFocus(landed = { channelsHaveFocus }, request = { listFocus.tryRequestFocus() })
        }
        if (!landed) fanOutRail()
    }
    // Entry: once Live TV has loaded, the D-pad starts on the rail when it is
    // open (nothing picked yet), on the first channel otherwise.
    var entryFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(bodyReady) {
        if (entryFocusDone || !bodyReady) return@LaunchedEffect
        entryFocusDone = true
        withFrameNanos { }
        val shown = currentState
        when {
            // Nothing picked yet: the rail has the D-pad, even when it is permanent.
            shown.selectedSelection is LiveCategorySelection.NothingPicked -> railFocusTick++
            shown.channels.isNotEmpty() -> listFocusTick++
            else -> fanOutRail()
        }
    }
    // OK / Right on a category: its channels are loading. When they have landed,
    // fold the rail away and move on to the first channel.
    LaunchedEffect(pendingSelect, state.selectedSelection, bodyReady) {
        val target = pendingSelect ?: return@LaunchedEffect
        if (state.selectedSelection != target || !bodyReady) return@LaunchedEffect
        pendingSelect = null
        if (!keepOpen) railOpen = false
        listFocusTick++
    }
    val selectCategory: (LiveCategorySelection) -> Unit = { selection ->
        pendingSelect = selection
        onSelectCategory(selection)
    }

    // F-43: the channel menu takes the D-pad when it opens — [menuItemFocus]
    // lands on the first item — and hands it back to the row that opened it
    // when it closes (handoff: "Back … hands focus back to whatever opened
    // it"), so the pane is never left with nothing focused.
    val menuItemFocus = remember { FocusRequester() }
    val categoryMenuFocus = remember { FocusRequester() }
    // F-53: "Remove from recently watched" deletes the very row the menu was
    // opened on, so the usual handback target is gone when the menu closes.
    // Focus goes to the row that takes its place instead (the one below, or
    // the one above when the removed row was last) — or nowhere here, letting
    // the empty state take focus when the list drops to zero. Hiding a channel
    // hands focus over the same way.
    var focusAfterRemoveId by remember { mutableStateOf<String?>(null) }
    // F-57: set when a remove empties the list — the empty state's CTA
    // takes focus on the next composition of ChannelPaneEmptyState.
    var emptyStateTakeFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectedSelection) { emptyStateTakeFocus = false }
    // #171: removing the last favorite from Favorites (the menu, or the row's star) empties the
    // pane; the empty state's action takes the D-pad once it arrives.
    val toggleFavorite: (String) -> Unit = { id ->
        if (removesLastFavorite(state.selectedSelection, state.channels.size, id in state.favoriteIds)) {
            emptyStateTakeFocus = true
        }
        onToggleFavorite(id)
    }
    LaunchedEffect(focusAfterRemoveId) {
        if (focusAfterRemoveId == null) return@LaunchedEffect
        focusAfterRemoveId = null
        // `menuRowFocus` rides on the target row via `focusRowId` below; it can
        // be detached (F-40's crash), so guard the request.
        if (!menuRowFocus.tryRequestFocus()) fanOutRail()
    }
    // #16: Back from full-screen playback (to the preview, or a stop) puts the D-pad
    // back on the channel that was playing. The row's requester is attached by the
    // next recomposition and the focus gate opens in the frame the host flipped to
    // Preview, so [awaitFocus] (time-bounded, #58) keeps asking until focus is in the
    // body. A channel that is not in the current rows falls back to the first one.
    var restoreRowId by remember { mutableStateOf<String?>(null) }
    // #138: true while the hold-return jump is finding its row. The guide's focus healing
    // would otherwise take focus to the first row in the meantime and the jump would count
    // that as landed.
    var jumpInFlight by remember { mutableStateOf(false) }
    val playbackRestoreTick = playback?.restoreTick ?: 0
    LaunchedEffect(playbackRestoreTick) {
        val host = playback ?: return@LaunchedEffect
        if (playbackRestoreTick == 0) return@LaunchedEffect
        val id = host.restoreStreamId
        jumpInFlight = id != null && host.restoreJump
        // A category just picked with OK on the rail may still be pending; once the jump has
        // moved the selection it must not fire later and send focus to its first row.
        if (jumpInFlight) pendingSelect = null
        // A newer restore cancels this one; the flag must not outlive either.
        currentCoroutineContext()[Job]?.invokeOnCompletion { jumpInFlight = false }
        // #138: after a hold-return the D-pad goes to the playing channel's row from wherever
        // the hold started. The rail is folded first (a list under an open rail takes no
        // focus), then, if the channel is not in the rows shown, its own category is opened
        // with enough rows paged in to reach it.
        if (id != null && host.restoreJump) {
            if (!keepOpen) railOpen = false
            if (currentState.channels.none { it.stream.id == id }) {
                val where = host.locate(id)
                val target = where?.let { loc ->
                    currentState.categories.firstNotNullOfOrNull { item ->
                        (item as? LiveCategoryItem.ProviderCategory)?.target
                            ?.takeIf { it is LiveCategorySelection.Category && it.categoryId == loc.categoryId }
                    }
                }
                if (target != null && where != null) {
                    onJumpToCategory(target, where.rowsBefore + 1)
                    withTimeoutOrNull(JUMP_CATEGORY_TIMEOUT_MILLIS) {
                        snapshotFlow { currentState.channels.any { it.stream.id == id } }.first { it }
                    }
                }
            }
            // The guide holds its own window of rows within the channels: widen it to the row.
            val index = currentState.channels.indexOfFirst { it.stream.id == id }
            if (index >= 0 && currentState.view == LiveTvView.GUIDE) {
                onGuideShowRows(currentState.selectedSelection, index + 1)
                // The grid itself, not just its rows: until it is shown the guide has nothing to focus.
                withTimeoutOrNull(JUMP_GUIDE_TIMEOUT_MILLIS) {
                    snapshotFlow {
                        val g = currentGuide
                        g != null && g.gridReady && g.tag == currentState.selectedSelection &&
                            g.rows.any { it.channel.streamId == id }
                    }.first { it }
                }
            }
        }
        restoreRowId = id?.takeIf { wanted -> currentState.channels.any { it.stream.id == wanted } }
        val landed = restoreRowId != null &&
            awaitFocus(timeoutMs = if (host.restoreJump) JUMP_GUIDE_TIMEOUT_MILLIS / 2 else FOCUS_WAIT_TIMEOUT_MS, landed = { channelsHaveFocus }, request = { menuRowFocus.tryRequestFocus() })
        jumpInFlight = false
        if (!landed) listFocusTick++
        restoreRowId = null
        withFrameNanos { }
        HtvPerf.end(HtvPerf.PLAYER_BACK_PREVIEW)
    }
    val hideChannelAndRefocus: (LiveStream) -> Unit = { stream ->
        val rows = state.channels
        val index = rows.indexOfFirst { it.stream.id == stream.id }
        val next = rows.getOrNull(index + 1) ?: rows.getOrNull(index - 1)
        onHideChannel(stream)
        if (next != null) focusAfterRemoveId = next.stream.id else fanOutRail()
    }
    val hideCategoryAndFanOut: (String) -> Unit = { categoryId ->
        // #114: focus lands on the category that takes the hidden one's place, and the rail
        // stays where it is. Read from the rail as it is now, before the hide removes the row.
        railFocusOverride = railKeyAfterHide(currentState.categories, categoryId)
        onHideCategory(categoryId)
        fanOutRail()
    }
    val dismissCategoryMenu: () -> Unit = {
        railFocusOverride = categoryMenu?.key
        categoryMenu = null
        railFocusTick++
    }

    // Back: channels -> rail, and from the rail out of Live TV. Menus close first
    // (they handle Back themselves, see [HtPopupMenu]).
    val menuOpen = channelMenu != null || categoryMenu != null || moreMenuOpen || guideDialog != null
    // #168: every way out of the More options menu (item, Back, scrim tap) hands focus back to the
    // button that opened it *before* the menu leaves composition, while its item still holds the
    // D-pad. Doing it afterwards raced the guide's focus healing, which took the grid instead.
    val moreButtonFocus = remember { FocusRequester() }
    val moreItemFocus = remember { FocusRequester() }
    val closeMoreMenu: () -> Unit = {
        moreButtonFocus.tryRequestFocus()
        onCloseMoreMenu()
    }
    // A modal over a playing preview hides the video for its lifetime (see LivePlayback).
    DisposableEffect(menuOpen) {
        playback?.overlayOpen = menuOpen
        onDispose { playback?.overlayOpen = false }
    }
    val handleBack: () -> Unit = {
        when {
            guideDialog != null -> closeGuideDialog()
            channelMenu != null -> onCloseChannelMenu()
            categoryMenu != null -> dismissCategoryMenu()
            moreMenuOpen -> closeMoreMenu()
            // #146: a guide that has moved forward goes back to Now first; the next Back does what it always did.
            guideShown && guide?.shifted == true && !overlayShown -> onGuideBackToNow()
            channelsHaveFocus -> fanOutRail()
            else -> onBack()
        }
    }
    // #59: the header menu takes no focus of its own, so closing it from an item
    // removes the node that held the D-pad. With the rail fanned out nothing else
    // heals that (the channel pane behind the scrim takes no focus): hand it to the
    // selected category. Folded away, the list takes it; the guide heals itself.
    var moreMenuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(moreMenuOpen) {
        val justClosed = !moreMenuOpen && moreMenuWasOpen
        moreMenuWasOpen = moreMenuOpen
        if (!justClosed) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (screenHasFocus) return@LaunchedEffect
        when {
            overlayShown -> railFocusTick++
            !guideShown -> listFocusTick++
        }
    }
    BackHandler(enabled = playback?.fullActive != true) { handleBack() }
    // #146: a channel taken full screen from a moved guide brings the guide back to Now, so Back from
    // full screen lands on the channel at Now (its info band reads the program on now, not "No guide info").
    val fullScreen = playback?.fullActive == true
    LaunchedEffect(fullScreen) {
        if (guideReturnsToNow(fullScreen, currentGuide?.shifted == true)) onGuideBackToNow()
    }
    val currentBack by rememberUpdatedState(handleBack)
    // The preview's 2 s hold is the activity's (#138); here Back is always a key-up short press.
    val backGate = remember { BackKeyGate(onShortPress = { currentBack() }, onLongPress = {}) }

    // 200 ms ease-in-out; "Keep categories open" never animates (spec #7 section 3.2).
    val railProgress = animateFloatAsState(
        targetValue = if (overlayShown) 1f else 0f,
        animationSpec = tween(RAIL_ANIMATION_MILLIS),
        label = "rail",
    )
    val scrimProgress = animateFloatAsState(
        targetValue = if (overlayShown && channelPaneContent(state) !is ChannelPaneContent.PickCategory) 1f else 0f,
        animationSpec = tween(RAIL_ANIMATION_MILLIS),
        label = "rail-scrim",
    )
    val focusedCategory = if (railHasFocus) {
        state.categories.firstOrNull { it.key == focusedRailKey && it.isRailRow() }
    } else {
        null
    }
    val guideRows = if (guideMode) guide?.rows.orEmpty() else emptyList()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvColors.Ground)
            .onFocusChanged { screenHasFocus = it.hasFocus }
            // The Back key is handled here, on the key itself. Left to the system
            // dispatcher, Compose consumes the first Back key-down to clear focus
            // before the activity can see it, which left the D-pad on nothing and
            // needed a second Back (QA on #42).
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back || menuOpen || playback?.fullActive == true) {
                    // Full screen: the host routes Back, wherever focus is (#16).
                    false
                } else if (playback?.playingStreamId != null) {
                    // #16: with a preview playing, a short Back acts on key-up. A hold is
                    // the activity's (#138): repeats count as the same press, so a release
                    // before 2 s is a short Back and a completed hold never reaches here.
                    when (event.type) {
                        KeyEventType.KeyDown -> backGate.onDown(repeatCount = 0)
                        KeyEventType.KeyUp -> backGate.onUp()
                    }
                    true
                } else {
                    if (event.type == KeyEventType.KeyDown) handleBack()
                    true
                }
            },
    ) {
        HtHeader(
            title = "Live TV",
            // Spec #7 section 2: the pane heading is gone; its "News · 86
            // channels" lives in the header's one-line subtitle.
            subtitle = liveTvHeaderSubtitle(
                selection = state.selectedSelection,
                selectionChannelCount = state.selectionChannelCount,
                hiddenChannelCount = state.hiddenChannelCount,
                loading = state.isLoading,
                totalChannels = state.totalChannels,
                visibleCategoryCount = state.visibleCategoryCount,
            ),
            onBack = onBack,
            onSearchClick = onSearchClick,
            onMoreClick = onOpenMoreMenu,
            moreFocusRequester = moreButtonFocus,
            modifier = Modifier
                .offsetPx(HEADER_X_PX, HEADER_Y_PX)
                .width(d.px(HEADER_WIDTH_PX))
                // Down from any header control goes to the body on purpose. Left to
                // Compose's geometric search it could land on nothing (QA on #51).
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionDown || menuOpen) {
                        false
                    } else {
                        if (event.type == KeyEventType.KeyDown) {
                            when {
                                overlayShown -> railFocusTick++
                                guideShown && guide?.isEmptyState == false -> guideEntryTick++
                                else -> listFocusTick++
                            }
                        }
                        true
                    }
                },
            trailing = {
                // Spec #7 section 3.4 / 3.6: the day picker (Guide view only), then
                // the Guide | List toggle.
                if (!state.isLoading) {
                    if (guideMode) {
                        HeaderSegments(
                            labels = GuideDay.entries.map { it.label },
                            selectedIndex = (guide?.day ?: GuideDay.TODAY).ordinal,
                            onSelect = { onGuideDay(GuideDay.entries[it]) },
                        )
                    }
                    HeaderSegments(
                        labels = listOf("Guide", "List"),
                        selectedIndex = if (guideMode) 0 else 1,
                        onSelect = { onSelectView(if (it == 0) LiveTvView.GUIDE else LiveTvView.LIST) },
                        horizontalPaddingPx = 24,
                    )
                }
            },
        )

        // Info band (spec #7 section 3.1): the focused item on the left, the
        // static preview slot on the right.
        LiveTvInfoRegion(
            guideMode = guideMode,
            guideRows = guideRows,
            channels = state.channels,
            focusedGuide = focusedGuide,
            focusedChannelId = focusedChannelId,
            focusedCategory = focusedCategory,
            loadProgram = loadProgram,
            // F-48: while a channel plays the slot paints nothing; the player's surface owns it.
            showPreviewArt = playback?.playingStreamId == null,
        )

        // The list is placed as if the rail were folded away; the overlay lays
        // over it, so opening and closing the rail never re-lays it out.
        val listX = if (keepOpen) LIST_X_KEEP_OPEN_PX else LIST_X_PX
        Box(
            modifier = Modifier
                .offsetPx(listX, BODY_TOP_PX)
                .size(width = d.px(LIST_RIGHT_PX - listX), height = d.px(ARTBOARD_HEIGHT_PX - BODY_TOP_PX))
                // The list keeps its focus until the rail has taken it (Back / Left):
                // clearing it the moment the overlay opens left the D-pad on nothing
                // for as long as the rail took to compose. Once focus is in the rail
                // the list behind takes none.
                // The details dialog holds the D-pad too: nothing behind it may take focus.
                .focusProperties { canFocus = (!overlayShown || channelsHaveFocus) && guideDialog == null }
                .onFocusChanged { channelsHaveFocus = it.hasFocus }
                .then(
                    if (overlayShown && content !is ChannelPaneContent.PickCategory) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer { alpha = 1f - LIST_DIM_UNDER_RAIL * railProgress.value },
        ) {
            if (guideMode && content is ChannelPaneContent.Rows && !guideShown) {
                // The channels are in, the guide's programmes are a query behind.
                ChannelPaneSkeleton(modifier = Modifier.padding(horizontal = d.px(BREATHING_ROOM_PX)))
            } else if (guideMode && guide != null && content is ChannelPaneContent.Rows) {
                GuideBody(
                    guide = guide,
                    cornerLabel = "${paneTitle(state.selectedSelection)} · ${formatCount(state.selectionChannelCount)}",
                    listWidthPx = LIST_RIGHT_PX - listX,
                    // The body ends at 1026 (spec #7 section 3); the grid never runs past it.
                    listHeightPx = GUIDE_BODY_BOTTOM_PX - BODY_TOP_PX,
                    entryTick = guideEntryTick,
                    onOpenProgram = { row, block, from ->
                        HtvPerf.start(HtvPerf.GUIDE_DETAILS_OPEN)
                        guideDialog = dialogFor(row, block, System.currentTimeMillis())
                        dialogReturn = from
                    },
                    onPlayChannel = { channel ->
                        state.channels.firstOrNull { it.stream.id == channel.streamId }?.let { onPlayChannel(it.stream) }
                    },
                    onChannelLongPress = { channel ->
                        state.channels.firstOrNull { it.stream.id == channel.streamId }?.let { onLongPressChannel(it.stream) }
                    },
                    onLeftFromGrid = ::fanOutRail,
                    onFocused = { target ->
                        focusedGuide.value = target
                        focusedSelection = currentState.selectedSelection
                        guide.rows.getOrNull(
                            when (target) {
                                is GuideFocus.ChannelCell -> target.row
                                is GuideFocus.Program -> target.row
                            },
                        )?.let { onGuideFocusLanded(it.channel.streamId) }
                    },
                    onVisibleChannels = onGuideVisible,
                    onTimeMove = onGuideTimeMove,
                    currentWindowStart = onGuideWindowStart,
                    onNeedMoreRows = { row ->
                        onGuideWiden(row)
                        // The guide shows every channel the list has paged in so far: ask for more.
                        if (guide.rows.size >= state.channels.size) onLoadMoreChannels()
                    },
                    onOpenGuideSettings = onOpenGuideSettings,
                    entryFocus = listFocus,
                    menuRowFocus = menuRowFocus,
                    menuChannelId = channelMenu?.id ?: focusAfterRemoveId ?: restoreRowId,
                    restoreTarget = dialogReturn,
                    restoreTick = restoreTick,
                    screenHasFocus = screenHasFocus,
                    // Not while the player owns the whole screen: the guide "healing" focus back onto
                    // a program and the player taking it back again fought every 0.3 s (#131).
                    healFocus = entryFocusDone && !overlayShown && !menuOpen && !state.isLoading &&
                        playback?.fullActive != true && !jumpInFlight,
                )
            } else ChannelPane(
                state = state,
                content = content,
                keepOpen = keepOpen,
                onPlay = onPlayChannel,
                onToggleFavorite = toggleFavorite,
                onLongPress = onLongPressChannel,
                onChannelFocused = {
                    focusedChannelId.value = it
                    focusedSelection = currentState.selectedSelection
                },
                onLeftFromChannels = ::fanOutRail,
                onShowHiddenChannels = { onShowHiddenChannels(true) },
                onSelectAllChannels = { selectCategory(LiveCategorySelection.AllChannels) },
                onRefreshChannels = onRefreshChannels,
                takeFocusOnEmpty = emptyStateTakeFocus,
                listFocus = listFocus,
                onLoadMore = onLoadMoreChannels,
                // The row that holds the menu open keeps the requester, so
                // closing the menu can hand focus straight back to it. After a
                // remove (F-53) or hide that row is gone, so the requester moves
                // to the row that takes its place (see `focusAfterRemoveId`).
                focusRowId = channelMenu?.id ?: focusAfterRemoveId ?: restoreRowId ?: returnRowId,
                menuRowFocus = menuRowFocus,
                modifier = Modifier
                    .offset(x = -d.px(BREATHING_ROOM_PX), y = -d.px(BREATHING_ROOM_PX))
                    .size(
                        width = d.px(LIST_RIGHT_PX - listX + 2 * BREATHING_ROOM_PX),
                        height = d.px(ARTBOARD_HEIGHT_PX - BODY_TOP_PX + BREATHING_ROOM_PX),
                    ),
            )
        }

        HtBottomFade(
            modifier = Modifier.align(Alignment.BottomCenter),
            heightPx = FADE_HEIGHT_PX,
        )

        // Rail. Folded away: zero width. Fanned out: slides in over a 92% Ground
        // scrim (200 ms). "Keep categories open": a permanent pane, no animation.
        val railEntries = remember(state.categories) { state.categories.toRailEntries() }
        HtRailLayer(
            entries = if (state.isLoading) emptyList() else railEntries,
            geometry = LIVE_RAIL_GEOMETRY,
            listState = railListState,
            keepOpen = keepOpen,
            overlayShown = overlayShown,
            railProgress = railProgress,
            scrimProgress = scrimProgress,
            entryKey = entryKey,
            entryRequester = railEntry,
            onSelect = { key -> currentState.categories.selectionForKey(key)?.let(selectCategory) },
            onRowFocused = { key -> focusedRailKey = key },
            onLongPress = { key ->
                categoryMenu = currentState.categories
                    .firstOrNull { it.key == key } as? LiveCategoryItem.ProviderCategory
            },
            onRailFocusChanged = { railHasFocus = it },
        )

        categoryMenu?.let { item ->
            HtPopupMenu(
                title = item.label,
                onDismiss = dismissCategoryMenu,
                anchorX = CATEGORY_MENU_X_PX,
                anchorY = CATEGORY_MENU_Y_PX,
                initialFocus = categoryMenuFocus,
            ) {
                HtMenuItem(
                    label = "Hide category",
                    icon = HappyTvIcons.EyeSlashed,
                    focusRequester = categoryMenuFocus,
                    onClick = {
                        categoryMenu = null
                        hideCategoryAndFanOut(item.id)
                    },
                )
            }
        }

        if (moreMenuOpen) {
            HtPopupMenu(
                title = "Live TV",
                onDismiss = closeMoreMenu,
                anchorX = MORE_MENU_X_PX,
                anchorY = MORE_MENU_Y_PX,
                initialFocus = moreItemFocus,
            ) {
                HtMenuItem(
                    label = if (state.showHiddenChannels) {
                        "Hide hidden channels"
                    } else {
                        "Show hidden channels"
                    },
                    icon = if (state.showHiddenChannels) HappyTvIcons.EyeSlashed else HappyTvIcons.Eye,
                    focusRequester = moreItemFocus,
                    onClick = {
                        onShowHiddenChannels(!state.showHiddenChannels)
                        closeMoreMenu()
                    },
                )
                HtMenuItem(
                    label = if (state.hideEmptyCategories) {
                        "Show all categories"
                    } else {
                        "Hide empty categories"
                    },
                    icon = Icons.Filled.Folder,
                    onClick = {
                        onHideEmptyCategories(!state.hideEmptyCategories)
                        closeMoreMenu()
                    },
                )
                HtMenuItem(
                    label = "Refresh channels",
                    icon = Icons.Filled.Refresh,
                    onClick = {
                        onRefreshChannels()
                        closeMoreMenu()
                    },
                )
            }
        }

        channelMenu?.let { stream ->
            val menuIndex = state.channels.indexOfFirst { it.stream.id == stream.id }
            val anchorY = if (menuIndex >= 0) {
                // The guide's rows start under its ruler and are 56 tall.
                val rowsTop = if (guideShown) BODY_TOP_PX + GUIDE_ROWS_OFFSET_PX else BODY_TOP_PX
                val rowStep = if (guideShown) GUIDE_ROW_HEIGHT_PX + GUIDE_ROW_GAP_PX else LIST_ROW_HEIGHT_PX + CHANNEL_ROW_GAP_PX
                (rowsTop + menuIndex * rowStep)
                    .coerceIn(CHANNEL_MENU_MIN_Y_PX, CHANNEL_MENU_MAX_Y_PX)
            } else {
                CHANNEL_MENU_MIN_Y_PX
            }
            // Every way out of the menu (item, Back, scrim tap) hands focus
            // back to the opening row *before* the menu leaves composition —
            // that is when the requester is still attached. Both requesters
            // can still be detached (F-40's crash: FocusRequester throws when
            // its node is gone — e.g. a catalog refresh dropping the row, or
            // the rail scrolled past its first row), so every requestFocus in
            // this path is guarded and a dead focus restore never kills the
            // close.
            val dismissMenu: () -> Unit = {
                if (!menuRowFocus.tryRequestFocus()) fanOutRail()
                onCloseChannelMenu()
            }
            HtPopupMenu(
                title = channelMenuTitle(stream),
                onDismiss = dismissMenu,
                anchorX = CHANNEL_MENU_X_PX,
                anchorY = anchorY,
                initialFocus = menuItemFocus,
            ) {
                // F-53: the "Recently watched" pane's own action first — the
                // menu is the home for future actions on a watched item.
                val inRecentlyWatched =
                    state.selectedSelection is LiveCategorySelection.RecentlyWatched
                if (inRecentlyWatched) {
                    HtMenuItem(
                        label = "Remove from recently watched",
                        icon = Icons.Filled.Delete,
                        onClick = {
                            // Hand focus to the row below the removed one (or
                            // the one above when it was last) *before* the list
                            // shrinks — the usual handback row is the one
                            // leaving composition.
                            val rows = state.channels
                            val index = rows.indexOfFirst { it.stream.id == stream.id }
                            val next = rows.getOrNull(index + 1) ?: rows.getOrNull(index - 1)
                            focusAfterRemoveId = next?.stream?.id
                            if (next == null) {
                                // F-57: last row — list goes empty. Let the
                                // empty state's CTA take focus.
                                emptyStateTakeFocus = true
                            }
                            onRemoveFromRecentlyWatched(stream)
                        },
                        focusRequester = menuItemFocus,
                    )
                }
                HtMenuItem(
                    label = if (stream.id in state.favoriteIds) {
                        "Remove from favorites"
                    } else {
                        "Add to favorites"
                    },
                    icon = Icons.Filled.Star,
                    iconTint = if (stream.id in state.favoriteIds) {
                        HappyTvColors.Accent
                    } else {
                        HappyTvColors.TextSecondary
                    },
                    focusRequester = if (inRecentlyWatched) null else menuItemFocus,
                    onClick = {
                        toggleFavorite(stream.id)
                        dismissMenu()
                    },
                )
                if (stream.tvArchive) {
                    HtMenuItem(
                        label = "Watch from the start",
                        icon = Icons.Filled.Replay,
                        onClick = { onWatchFromStart(stream) },
                    )
                }
                // #123: a channel that is hidden (reachable only through "Show hidden channels")
                // reads "Unhide channel"; its row stays in the list, so focus returns to it.
                val channelHidden = stream.id in state.hiddenChannelIds
                HtMenuItem(
                    label = hideChannelLabel(channelHidden),
                    icon = if (channelHidden) HappyTvIcons.Eye else HappyTvIcons.EyeSlashed,
                    onClick = {
                        if (channelHidden) {
                            onHideChannel(stream)
                            dismissMenu()
                        } else {
                            hideChannelAndRefocus(stream)
                        }
                    },
                )
                val categoryLabel = categoryLabelFor(state, stream)
                if (categoryLabel != null) {
                    HtMenuItem(
                        label = "Hide $categoryLabel",
                        icon = Icons.Filled.Folder,
                        onClick = { hideCategoryAndFanOut(stream.categoryId) },
                    )
                }
            }
        }

        // Program details (Guide view). Watch now / Watch from the start open the
        // player through the same launch contract the list uses.
        guideDialog?.let { dialog ->
            GuideDetailsDialog(
                dialog = dialog,
                loadDescription = {
                    loadProgram(dialog.channel.streamId, dialog.channel.epgChannelId, dialog.program.startUtcMillis)
                        ?.description
                },
                onWatchNow = {
                    closeGuideDialog()
                    state.channels.firstOrNull { it.stream.id == dialog.channel.streamId }
                        ?.let { onPlayChannel(it.stream) }
                },
                onWatchFromStart = {
                    closeGuideDialog()
                    onPlayReplay(
                        dialog.channel.streamId,
                        dialog.program.startUtcMillis,
                        dialog.program.durationMinutes,
                    )
                },
                onDismiss = closeGuideDialog,
            )
        }
    }
}

/**
 * `HtvPerf` probes for Live TV (see `docs/WORKFLOW.md`): `livetv.open` and
 * `livetv.category.rows` end on the first frame drawn after the rows landed (in
 * Guide view: after the guide's programmes landed too, [ready]);
 * `livetv.category.epg` then runs until the now lines have been resolved - in
 * Guide view the programmes arrive with the grid, so [epgByRows] ends it there.
 */
@Composable
private fun ReportLiveTvTimings(state: LiveTvUiState, ready: Boolean, epgByRows: Boolean) {
    val rowsKey = if (!ready) null else state.selectedSelection
    val latest by rememberUpdatedState(state)

    fun endEpgIfResolved() {
        val shown = latest
        if (!(shown.nowResolved || epgByRows) || !HtvPerf.isRunning(HtvPerf.LIVETV_CATEGORY_EPG)) return
        val filled = shown.channels.count { it.nowTitle != null }
        HtvPerf.end(HtvPerf.LIVETV_CATEGORY_EPG, "filled=$filled/${shown.channels.size}")
    }

    LaunchedEffect(rowsKey) {
        if (rowsKey == null) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        val detail = "n=${latest.channels.size}${if (epgByRows) " guide" else ""}"
        HtvPerf.end(HtvPerf.LIVETV_OPEN, detail)
        HtvPerf.end(HtvPerf.LIVETV_CATEGORY_ROWS, detail)
        // Rows are on screen: the clock for the now lines starts here.
        HtvPerf.start(HtvPerf.LIVETV_CATEGORY_EPG)
        endEpgIfResolved()
    }
    LaunchedEffect(rowsKey, state.nowResolved) {
        if (rowsKey == null || !state.nowResolved) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        endEpgIfResolved()
    }
}

/** "101 · Northbay News" — the channel menu's header. */
private fun channelMenuTitle(stream: LiveStream): String =
    if (stream.num != null) "${stream.num} · ${stream.name}" else stream.name

/**
 * The header subtitle (spec #7 section 2): "News · 86 channels" for the
 * selected category, "N channels · M categories" when nothing is selected.
 *
 * The count is the selection's real size (F-46) — the pane pages one window
 * at a time, so the window size would read "240 channels" on a 39 361-channel
 * catalog — and shows an ellipsis until the catalog answers, so a skeleton is
 * never next to a wrong "0 channels".
 */
internal fun liveTvHeaderSubtitle(
    selection: LiveCategorySelection?,
    selectionChannelCount: Int,
    hiddenChannelCount: Int,
    loading: Boolean,
    totalChannels: Int,
    visibleCategoryCount: Int,
): String {
    if (selection == null || selection is LiveCategorySelection.NothingPicked) {
        return "${formatCount(totalChannels, "channels")} · ${formatCount(visibleCategoryCount, "categories")}"
    }
    val title = paneTitle(selection)
    if (loading) return "$title · …"
    val hidden = if (hiddenChannelCount > 0) " · $hiddenChannelCount hidden" else ""
    return "$title · ${formatCount(selectionChannelCount, "channels")}$hidden"
}

internal fun paneTitle(selection: LiveCategorySelection): String = when (selection) {
    LiveCategorySelection.NothingPicked -> "Channels"
    LiveCategorySelection.AllChannels -> "All channels"
    LiveCategorySelection.Favorites -> "Favorites"
    LiveCategorySelection.RecentlyWatched -> "Recently watched"
    is LiveCategorySelection.Category -> selection.label
}

private fun categoryLabelFor(state: LiveTvUiState, stream: LiveStream): String? =
    state.categories
        .filterIsInstance<LiveCategoryItem.ProviderCategory>()
        .firstOrNull { it.id == stream.categoryId }
        ?.label

/* -------------------------------------------------------------------------- */
/*  Info band and preview slot (spec #7 section 3.1)                          */
/* -------------------------------------------------------------------------- */

/** True for the rail items that can be focused and selected (not dividers or headers). */
internal fun LiveCategoryItem.isRailRow(): Boolean =
    this !is LiveCategoryItem.Header && this !is LiveCategoryItem.Divider

private fun LiveCategoryItem.isSelectedRow(): Boolean = when (this) {
    is LiveCategoryItem.Pinned -> selected
    is LiveCategoryItem.AllChannels -> selected
    is LiveCategoryItem.ProviderCategory -> selected
    else -> false
}

/**
 * Where D-pad focus lands when the rail takes it (spec #7 section 3.2): an
 * explicit [override] (the row a closed menu belonged to), else the selected
 * category, else the first row (nothing picked yet).
 */
internal fun railEntryKey(items: List<LiveCategoryItem>, override: String? = null): String? {
    if (override != null && items.any { it.key == override }) return override
    return items.firstOrNull { it.isSelectedRow() }?.key
        ?: items.firstOrNull { it.isRailRow() }?.key
}

/**
 * #114: the rail row that takes focus when the category [categoryId] is hidden (the next row, else the
 * previous one); null when the category is not in the rail or nothing else is.
 */
internal fun railKeyAfterHide(items: List<LiveCategoryItem>, categoryId: String): String? =
    items.focusTargetAfterRemoval(items.indexOfFirst { it.key == categoryId }) { it.isRailRow() }?.key

/** What the info band shows for a focused rail item: its name and channel count. */
internal fun railItemInfo(item: LiveCategoryItem): Pair<String, Int>? = when (item) {
    is LiveCategoryItem.Pinned -> item.label to item.count
    is LiveCategoryItem.AllChannels -> item.label to item.count
    is LiveCategoryItem.ProviderCategory -> item.label to item.count
    else -> null
}

/**
 * The focused item on the left of the info band (spec #7 section 3.1): the channel
 * line, the program title, the time and status line and a two-line description; or,
 * while the rail has focus, the category and its size.
 *
 * The description is **not** part of the batched now lookup (#32 took it out on
 * purpose): it arrives as one single-row read for the focused program, debounced on
 * focus changes and off the main thread, and fills in under the title.
 */
@Composable
internal fun InfoBand(category: LiveCategoryItem?, info: InfoBandUi?, description: String?) {
    val d = browseDesign()
    val type = browseType()
    Column(
        modifier = Modifier
            .offsetPx(INFO_X_PX, INFO_TOP_PX)
            .size(width = d.px(INFO_WIDTH_PX), height = d.px(INFO_HEIGHT_PX))
            // Anchored to the top: with Center the channel line moved whenever the
            // focused row had no time or description line (QA on #51).
            .padding(top = d.px(28)),
        verticalArrangement = Arrangement.Top,
    ) {
        val railInfo = category?.let(::railItemInfo)
        if (railInfo != null) {
            Text(
                text = railInfo.first,
                style = type.compactTitle,
                color = HappyTvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatCount(railInfo.second, "channels"),
                style = type.compactCaption,
                color = HappyTvColors.TextSecondary,
            )
        } else if (info != null) {
            Text(
                text = info.channelLine,
                style = type.compactCaptionSemibold.tabular(),
                color = HappyTvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = info.title,
                style = type.compactTitle,
                color = if (info.hasProgram) HappyTvColors.TextPrimary else HappyTvColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (info.timeLine != null) {
                Text(
                    text = info.timeLine,
                    style = type.compactCaption.tabular(),
                    color = HappyTvColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = type.compactCaption,
                    color = HappyTvColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What the info band describes and how to ask for its description: the [band] to
 * draw at once (from data already on screen) and the single-row [query] that
 * fills in the description and the exact time line (null when there is no
 * programme to ask about).
 */
@Immutable
internal data class InfoTarget(val band: InfoBandUi, val query: InfoQuery?)

/** One program to look up: the channel's store key parts, and an instant inside it. */
@Immutable
internal data class InfoQuery(
    val streamId: String,
    val epgChannelId: String?,
    val atUtcMillis: Long,
    val hasReplay: Boolean,
)

/** The info band for a focused list row: the now line the row already carries. */
internal fun infoTargetForList(row: LiveChannelRow, nowUtcMillis: Long): InfoTarget {
    val stream = row.stream
    val line = channelLineOf(stream.num, stream.name)
    val title = row.nowTitle ?: return InfoTarget(infoBandNoGuide(line), query = null)
    return InfoTarget(
        band = InfoBandUi(
            channelLine = line,
            title = title,
            hasProgram = true,
            timeLine = "On now · ${row.nowUntilLabel.orEmpty()}",
        ),
        query = InfoQuery(stream.id, stream.epgChannelId, nowUtcMillis, stream.tvArchive),
    )
}

/** The info band for the focused guide element: a program block, or a channel cell's now. */
internal fun infoTargetForGuide(focus: GuideFocus, rows: List<GuideRow>, nowUtcMillis: Long): InfoTarget? {
    val band = infoBandForFocus(focus, rows, nowUtcMillis) ?: return null
    val rowIndex = when (focus) {
        is GuideFocus.ChannelCell -> focus.row
        is GuideFocus.Program -> focus.row
    }
    val row = rows.getOrNull(rowIndex) ?: return null
    val at = infoBlockFor(focus, row)?.program?.startUtcMillis
    val query = at?.let { InfoQuery(row.channel.streamId, row.channel.epgChannelId, it, row.channel.hasReplay) }
    return InfoTarget(band, query)
}

/** Live TV's rows as the guide's channels (genre from the provider category name). */
internal fun List<LiveChannelRow>.toGuideChannels(categories: List<LiveCategoryItem>): List<GuideChannel> {
    val names = categories.filterIsInstance<LiveCategoryItem.ProviderCategory>().associate { it.id to it.label }
    return map { row ->
        val stream = row.stream
        val categoryName = names[stream.categoryId]
        GuideChannel(
            streamId = stream.id,
            num = stream.num,
            name = stream.name,
            genre = knownGenre(categoryName),
            isFavorite = row.isFavorite,
            hasReplay = stream.tvArchive,
            epgChannelId = stream.epgChannelId,
            categoryId = stream.categoryId,
            categoryName = categoryName,
            logoUrl = stream.streamIcon,
        )
    }
}

/**
 * The 448 x 252 preview slot at x 1376 (16:9, radius 16, Surface when empty).
 * Static channel art only for now. The player takes this rectangle over later
 * (Back-to-preview), so nothing opaque may be designed to sit on top of it:
 * the slot holds one background and its art, and no other element, scrim or
 * overlay reaches into its bounds (F-48).
 */
@Composable
internal fun PreviewSlot(channel: LiveChannelRow?, showArt: Boolean) {
    val d = browseDesign()
    // F-48: while a channel plays the slot draws nothing at all; the player's
    // surface owns this rectangle and anything painted over it would hide it.
    if (!showArt) return
    Box(
        modifier = Modifier
            .offsetPx(PREVIEW_X_PX, INFO_TOP_PX)
            .size(width = d.px(PREVIEW_WIDTH_PX), height = d.px(INFO_HEIGHT_PX))
            .clip(RoundedCornerShape(d.px(PREVIEW_RADIUS_PX)))
            .background(HappyTvColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        if (channel != null) {
            HtChannelMark(
                name = channel.stream.name,
                logoUrl = channel.stream.streamIcon,
                widthPx = PREVIEW_WIDTH_PX,
                heightPx = INFO_HEIGHT_PX,
                radiusPx = PREVIEW_RADIUS_PX,
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Category rail (accordion)                                                 */
/* -------------------------------------------------------------------------- */

/**
 * Live TV's rail rows for the shared [HtRailLayer]: the same pinned / provider / all rows, as
 * [RailEntry]s. The mapping is keyed on the list so an unchanged rail is not rebuilt.
 */
internal fun List<LiveCategoryItem>.toRailEntries(): List<RailEntry> = map { item ->
    when (item) {
        is LiveCategoryItem.Pinned -> RailEntry.Row(
            key = item.key,
            label = item.label,
            count = item.count,
            icon = if (item.iconKind == PinnedIconKind.STAR) Icons.Outlined.StarBorder else Icons.Filled.History,
            selected = item.selected,
        )
        is LiveCategoryItem.AllChannels -> RailEntry.Row(
            key = item.key,
            label = item.label,
            count = item.count,
            icon = null,
            selected = item.selected,
        )
        is LiveCategoryItem.ProviderCategory -> RailEntry.Row(
            key = item.key,
            label = item.label,
            count = item.count,
            icon = null,
            selected = item.selected,
            hasMenu = true,
        )
        is LiveCategoryItem.Header -> RailEntry.Header(item.label)
        LiveCategoryItem.Divider -> RailEntry.Divider
    }
}

/** The selection a tapped rail key stands for. */
internal fun List<LiveCategoryItem>.selectionForKey(key: String): LiveCategorySelection? =
    when (val item = firstOrNull { it.key == key }) {
        is LiveCategoryItem.Pinned -> item.target
        is LiveCategoryItem.AllChannels -> LiveCategorySelection.AllChannels
        is LiveCategoryItem.ProviderCategory -> item.target
        else -> null
}

/* -------------------------------------------------------------------------- */
/*  Channel list (spec #7 section 3.5)                                        */
/* -------------------------------------------------------------------------- */

@Composable
private fun ChannelPane(
    state: LiveTvUiState,
    content: ChannelPaneContent,
    keepOpen: Boolean,
    onPlay: (LiveStream) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onLongPress: (LiveStream) -> Unit,
    onChannelFocused: (String) -> Unit,
    onLeftFromChannels: () -> Unit,
    onShowHiddenChannels: () -> Unit,
    onSelectAllChannels: () -> Unit,
    onRefreshChannels: () -> Unit,
    onLoadMore: () -> Unit,
    focusRowId: String?,
    menuRowFocus: FocusRequester,
    listFocus: FocusRequester,
    takeFocusOnEmpty: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    when (content) {
        // F-46: the first load draws the skeleton — the empty-state copy must
        // never flash while the catalog query is still in flight.
        ChannelPaneContent.Loading -> {
            ChannelPaneSkeleton(modifier = modifier.padding(horizontal = d.px(BREATHING_ROOM_PX)))
            return
        }
        is ChannelPaneContent.Empty -> {
            ChannelPaneEmptyState(
                empty = content,
                state = state,
                onShowHiddenChannels = onShowHiddenChannels,
                onSelectAllChannels = onSelectAllChannels,
                onRefreshChannels = onRefreshChannels,
                // F-53: focus is only taken when the caller asked for it (a
                // deliberate re-entry into an empty selection) — never on a
                // passive re-entry, where stealing the D-pad would yank focus
                // off whatever the user is actually on.
                takeFocus = takeFocusOnEmpty,
                listFocus = listFocus,
                onLeft = onLeftFromChannels,
                modifier = modifier.padding(horizontal = d.px(BREATHING_ROOM_PX)),
            )
            return
        }
        ChannelPaneContent.PickCategory -> {
            // Nothing queried, nothing to draw but a quiet hint. The rail is open
            // and has the D-pad; the hint sits clear of it (rail 400 wide, 24 gap).
            val type = browseType()
            Box(modifier = modifier.padding(horizontal = d.px(BREATHING_ROOM_PX))) {
                Text(
                    text = "Pick a category to see its channels",
                    style = type.compactBody,
                    color = HappyTvColors.TextSecondary,
                    modifier = Modifier.offsetPx(if (keepOpen) 0 else HINT_X_OFFSET_PX, EMPTY_STATE_TOP_PX / 2),
                )
            }
            return
        }
        is ChannelPaneContent.Rows -> Unit // the LazyColumn below
    }

    // A new category starts at the top, not wherever the last one was scrolled.
    val listState = remember(state.selectedSelection) { LazyListState() }
    // Paging trigger (F-46): when the last visible row closes in on the end of
    // the paged-in window, ask for the next page — same shape as F-55's poster
    // grid. The window only grows toward what the user has actually browsed.
    LaunchedEffect(listState, state.channels.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= 0 && lastVisible >= state.channels.size - LOAD_MORE_LOOKAHEAD) {
                    onLoadMore()
                }
            }
    }
    // #138: a row asked to take focus that is far down the window is scrolled into
    // composition first (an uncomposed row has no requester to focus).
    LaunchedEffect(listState, focusRowId, state.channels) {
        val index = state.channels.indexOfFirst { it.stream.id == focusRowId }
        if (focusRowId != null && index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(all = d.px(BREATHING_ROOM_PX)),
        verticalArrangement = Arrangement.spacedBy(d.px(CHANNEL_ROW_GAP_PX)),
    ) {
        itemsIndexed(
            state.channels,
            key = { _, row -> row.stream.id },
            // F-42: every item is the same ChannelRow shape, so declare one
            // content type — LazyColumn then reuses a single recycled node
            // pool instead of re-binding by index while the list scrolls.
            contentType = { _, _ -> "channel-row" },
        ) { index, row ->
            ChannelRow(
                row = row,
                onPlay = { onPlay(row.stream) },
                onToggleFavorite = { onToggleFavorite(row.stream.id) },
                onLongPress = { onLongPress(row.stream) },
                onFocused = { onChannelFocused(row.stream.id) },
                onLeft = onLeftFromChannels,
                // Only the menu's channel carries the requester, so focusing
                // it means exactly "hand the D-pad back to the opener" (F-43).
                focusRequester = if (row.stream.id == focusRowId) menuRowFocus else null,
                // The first row is where OK / Right on a category lands.
                firstRowFocus = if (index == 0) listFocus else null,
            )
        }
    }
}

/**
 * One channel (spec #7 section 3.5): a 64 tall row button plus a separate 56 x 56
 * star button (its own focus stop), 8 apart. The row reads, left to right: the
 * number in a tabular column wide enough for five digits on one line, an
 * 84 x 56 monogram, the name with the Replay chip, and the now line. Left
 * from the row fans the rail out; the star sits to the right of it.
 */
@Composable
private fun ChannelRow(
    row: LiveChannelRow,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongPress: () -> Unit,
    onFocused: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    firstRowFocus: FocusRequester? = null,
) {
    val d = browseDesign()
    val type = browseType()
    val stream = row.stream
    // "Northbay News, channel 101, now Headline report until 9:00, replay available"
    val readerLabel = buildString {
        append(stream.name)
        stream.num?.let { append(", channel $it") }
        val now = listOfNotNull(row.nowTitle, row.nowUntilLabel).joinToString(" ")
        if (now.isNotEmpty()) append(", now $now")
        if (stream.tvArchive) append(", replay available")
    }
    Row(
        modifier = modifier.height(d.px(LIST_ROW_HEIGHT_PX)),
        horizontalArrangement = Arrangement.spacedBy(d.px(LIST_STAR_GAP_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionLeft) {
                        if (event.type == KeyEventType.KeyDown) onLeft()
                        true
                    } else {
                        false
                    }
                }
                // Before the surface: a FocusRequester only finds focus
                // targets that come *after* it in the chain (menu-close
                // hands focus back through this requester).
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
                )
                .then(
                    if (firstRowFocus != null) Modifier.focusRequester(firstRowFocus) else Modifier,
                )
                .htvClickableSurface(
                    radiusPx = 16,
                    background = HappyTvColors.Surface,
                    onClick = onPlay,
                    onLongClick = onLongPress,
                    onFocused = onFocused,
                )
                .semantics { contentDescription = readerLabel }
                .padding(start = d.px(16), end = d.px(24)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(d.px(CHANNEL_NUMBER_COLUMN_PX))) {
                Text(
                    text = stream.num?.toString().orEmpty(),
                    style = type.compactCaptionSemibold.tabular(),
                    color = HappyTvColors.TextTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            HtChannelMark(
                name = stream.name,
                logoUrl = stream.streamIcon,
                widthPx = MONOGRAM_WIDTH_PX,
                heightPx = MONOGRAM_HEIGHT_PX,
                radiusPx = 8,
                textStyle = type.compactCaptionSemibold,
            )
            Spacer(Modifier.width(d.px(20)))
            Row(
                modifier = Modifier.weight(NAME_WEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stream.name,
                    style = type.compactRowName,
                    color = HappyTvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (stream.tvArchive) {
                    Spacer(Modifier.width(d.px(12)))
                    ReplayChip(compact = true)
                }
            }
            Spacer(Modifier.width(d.px(24)))
            NowLine(row = row, modifier = Modifier.weight(NOW_WEIGHT))
        }
        StarButton(isFavorite = row.isFavorite, onClick = onToggleFavorite)
    }
}

/**
 * The now line: what is on, and when it ends. The end time never truncates —
 * the program title gives way first (design spec "Typeface > Truncation").
 */
@Composable
private fun NowLine(row: LiveChannelRow, modifier: Modifier = Modifier) {
    val type = browseType()
    if (row.nowTitle == null) {
        Text(
            text = "No guide info",
            style = type.compactCaption,
            color = HappyTvColors.TextTertiary,
            maxLines = 1,
            modifier = modifier,
        )
        return
    }
    Row(modifier = modifier) {
        Text(
            text = row.nowTitle,
            style = type.compactCaption,
            color = HappyTvColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = " · ${row.nowUntilLabel.orEmpty()}",
            style = type.compactCaption,
            color = HappyTvColors.TextTertiary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

/** 56 x 56 circle, Surface, 28 px star — filled accent when a favorite. */
@Composable
private fun StarButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    Box(
        modifier = modifier
            .size(d.px(STAR_BUTTON_PX))
            .htvClickableSurface(
                radiusPx = STAR_BUTTON_PX / 2,
                background = HappyTvColors.Surface,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            tint = if (isFavorite) HappyTvColors.Accent else HappyTvColors.TextSecondary,
            modifier = Modifier.size(d.px(STAR_ICON_PX)),
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Empty states                                                              */
/* -------------------------------------------------------------------------- */

@Composable
private fun ChannelPaneEmptyState(
    empty: ChannelPaneContent.Empty,
    state: LiveTvUiState,
    onShowHiddenChannels: () -> Unit,
    onSelectAllChannels: () -> Unit,
    onRefreshChannels: () -> Unit,
    modifier: Modifier = Modifier,
    // F-57: only steal focus when the empty state appeared because a list
    // mutation just removed the last row. On initial load (or when switching
    // to an empty category) the empty state must NOT grab focus — the rail
    // or header keeps it, and stealing it mid-walk breaks D-pad traversal.
    takeFocus: Boolean = false,
    // OK / Right on a category that turns out empty: the action takes focus.
    listFocus: FocusRequester? = null,
    // #171: Left from the action fans the rail out, as from the first column everywhere else.
    onLeft: () -> Unit = {},
) {
    // F-57: the empty state's CTA must take focus so the pane is never left
    // with nothing focused after a list mutation removes the last row. The
    // CTA button's own FocusRequester sits after its clickable surface in
    // HtButton (requestFocus() finds no focusable after it — a silent
    // no-op), so we wire a requester on the Box *before* HtEmptyState: a
    // FocusRequester finds the first focusable that comes *after* it in the
    // chain, which is the CTA button inside the empty state.
    val focusedButton = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (takeFocus) focusedButton.tryRequestFocus()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // Nothing lies left of the empty state's action, so a directional search would
                // leave the D-pad on nothing (the list behind a folded rail takes no focus).
                if (event.key == Key.DirectionLeft) {
                    if (event.type == KeyEventType.KeyDown) onLeft()
                    true
                } else {
                    false
                }
            }
            .focusRequester(focusedButton)
            .then(if (listFocus != null) Modifier.focusRequester(listFocus) else Modifier),
    ) {
        when (empty) {
            ChannelPaneContent.Empty.NoFavorites -> {
                HtEmptyState(
                    icon = Icons.Outlined.StarBorder,
                    heading = "No favorites yet",
                    body = "Star the channels you watch most and they'll wait for you here.",
                    buttonLabel = "Browse all channels",
                    onButtonClick = onSelectAllChannels,
                    primary = false,
                    focusRequester = focusedButton,
                    modifier = Modifier.offsetPx(0, EMPTY_STATE_TOP_PX),
                )
            }
            // F-53: removing the last item lands here — say so plainly instead
            // of the generic "No channels to show" wording.
            ChannelPaneContent.Empty.RecentlyWatched -> {
                HtEmptyState(
                    icon = Icons.Filled.History,
                    heading = "No recently watched channels",
                    body = "Channels you watch show up here so you can pick up where you left off.",
                    buttonLabel = "Browse all channels",
                    onButtonClick = onSelectAllChannels,
                    primary = false,
                    focusRequester = focusedButton,
                    modifier = Modifier.offsetPx(0, EMPTY_STATE_TOP_PX),
                )
            }
            is ChannelPaneContent.Empty.AllHidden -> {
                HtEmptyState(
                    icon = HappyTvIcons.EyeSlashed,
                    heading = "No channels to show in ${paneTitle(state.selectedSelection)}",
                    body = "All ${empty.hiddenCount} are hidden. " +
                        "Bring them back whenever you like.",
                    buttonLabel = "Show hidden channels",
                    onButtonClick = onShowHiddenChannels,
                    primary = true,
                    focusRequester = focusedButton,
                    modifier = Modifier.offsetPx(0, EMPTY_STATE_TOP_PX),
                )
            }
            ChannelPaneContent.Empty.NothingHere -> {
                HtEmptyState(
                    icon = HappyTvIcons.Eye,
                    heading = "No channels to show in ${paneTitle(state.selectedSelection)}",
                    body = "Nothing here right now.",
                    buttonLabel = "Refresh channels",
                    onButtonClick = onRefreshChannels,
                    primary = false,
                    focusRequester = focusedButton,
                    modifier = Modifier.offsetPx(0, EMPTY_STATE_TOP_PX),
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Loading (F-46)                                                            */
/* -------------------------------------------------------------------------- */

/**
 * The channel pane's first-load placeholder: skeleton channel rows under a
 * shimmer sweep (the design spec "Poster grid > Loading" treatment, applied to
 * rows). Not focusable — same rule as the poster grid.
 */
@Composable
private fun ChannelPaneSkeleton(modifier: Modifier = Modifier) {
    val d = browseDesign()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(all = d.px(BREATHING_ROOM_PX)),
        verticalArrangement = Arrangement.spacedBy(d.px(CHANNEL_ROW_GAP_PX)),
        userScrollEnabled = false,
    ) {
        items(List(CHANNEL_SKELETON_ROWS) { "channel-skeleton-$it" }) { key ->
            ChannelRowSkeleton(key = key)
        }
    }
}

/**
 * One placeholder row in the geometry of [ChannelRow]: the row surface with a
 * monogram block and two Loading-color bars, plus the star button's circle —
 * all under a left-to-right shimmer sweep.
 */
@Composable
private fun ChannelRowSkeleton(key: String) {
    val d = browseDesign()
    val transition = rememberInfiniteTransition(label = key)
    // Read in the draw phase below: `by` in composition recomposed the row every frame.
    val sweepState = transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-$key",
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowWidthPx = with(density) { d.px(LIST_RIGHT_PX - LIST_X_PX).toPx() }
    val rowHeightPx = with(density) { d.px(LIST_ROW_HEIGHT_PX).toPx() }
    Row(
        modifier = Modifier.height(d.px(LIST_ROW_HEIGHT_PX)),
        horizontalArrangement = Arrangement.spacedBy(d.px(LIST_STAR_GAP_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(HappyTvColors.Surface, RoundedCornerShape(d.px(16))),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val sweep = sweepState.value
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    HappyTvColors.ProgressTrack,
                                    Color.Transparent,
                                ),
                                start = Offset(sweep * rowWidthPx, 0f),
                                end = Offset((sweep + 1f) * rowWidthPx, rowHeightPx),
                            ),
                        )
                    },
            )
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = d.px(16), end = d.px(24)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The channel-number column, kept so the bars start where the
                // real row's text starts.
                Box(modifier = Modifier.width(d.px(CHANNEL_NUMBER_COLUMN_PX)))
                Box(
                    modifier = Modifier
                        .size(width = d.px(MONOGRAM_WIDTH_PX), height = d.px(MONOGRAM_HEIGHT_PX))
                        .background(HappyTvColors.Loading, RoundedCornerShape(d.px(8))),
                )
                Spacer(Modifier.width(d.px(20)))
                SkeletonBar(widthFraction = 0.3f)
            }
        }
        Box(
            modifier = Modifier
                .size(d.px(STAR_BUTTON_PX))
                .background(HappyTvColors.Loading, androidx.compose.foundation.shape.CircleShape),
        )
    }
}

/** A Loading-color rounded bar — same proportions as the poster grid's. */
@Composable
private fun SkeletonBar(widthFraction: Float) {
    val d = browseDesign()
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(d.px(24))
            .background(HappyTvColors.Loading, RoundedCornerShape(d.px(12))),
    )
}

/* -------------------------------------------------------------------------- */
/*  Geometry (spec #7, px at 1920 x 1080)                                     */
/* -------------------------------------------------------------------------- */

private const val ARTBOARD_WIDTH_PX = 1920
private const val ARTBOARD_HEIGHT_PX = 1080

private const val HEADER_X_PX = 96
private const val HEADER_Y_PX = HEADER_TOP_PX
private const val HEADER_WIDTH_PX = 1728

/** Info band (spec #7 section 3.1): top 142, 252 tall; the preview slot at x 1376, 448 wide. */
private const val INFO_TOP_PX = 142
private const val INFO_HEIGHT_PX = 252
private const val INFO_X_PX = 96
private const val INFO_WIDTH_PX = 1256
private const val PREVIEW_X_PX = 1376
private const val PREVIEW_WIDTH_PX = 448
private const val PREVIEW_RADIUS_PX = 16

/** The body (rail and list) starts here, 16 below the info band. */
private const val BODY_TOP_PX = 410


/** The list is also dimmed by this much while the rail is up, so it is unreadable behind the pills. */
private const val LIST_DIM_UNDER_RAIL = 0.5f
private val LIVE_RAIL_GEOMETRY = RailGeometry(BODY_TOP_PX)

/** The list's left edge: folded rail 96, "Keep categories open" 520 (rail 96 + 400, 24 gap). */
private const val LIST_X_PX = 96
private const val LIST_X_KEEP_OPEN_PX = 520
private const val LIST_RIGHT_PX = 1824

/** Hint x offset inside the list pane, so it clears the rail overlay (520 - 96). */
private const val HINT_X_OFFSET_PX = LIST_X_KEEP_OPEN_PX - LIST_X_PX

/** Breathing room for the focus glow around the list. */
private const val BREATHING_ROOM_PX = 40

private const val LIST_ROW_HEIGHT_PX = 64
private const val CHANNEL_ROW_GAP_PX = 4
private const val LIST_STAR_GAP_PX = 8

/**
 * Wide enough for five tabular digits (24 px text, about 15 px a digit) on one
 * line, with room to spare; the number never wraps (QA on #40).
 */
private const val CHANNEL_NUMBER_COLUMN_PX = 88
private const val MONOGRAM_WIDTH_PX = 84
private const val MONOGRAM_HEIGHT_PX = 56
private const val STAR_BUTTON_PX = 56
private const val STAR_ICON_PX = 28

/** The name block and the now line share what is left of the row, 0.8 to 1. */
private const val NAME_WEIGHT = 0.8f
private const val NOW_WEIGHT = 1f

/** Paging: ask for the next page when the last visible row is this close to the end. */
private const val LOAD_MORE_LOOKAHEAD = 3

/** Skeleton rows while the first list loads: a screenful. */
private const val CHANNEL_SKELETON_ROWS = 9

/** Shimmer sweep period. */
private const val SHIMMER_MILLIS = 1600

private const val FADE_HEIGHT_PX = 128

/** How many frames a focus request waits for its target to be composed. */


/** Info band: wait this long after the last focus change before reading the description. */
internal const val INFO_DEBOUNCE_MILLIS = 120L

/** Entry focus waits this long for the guide's programmes before giving up on them. */
private const val GUIDE_READY_TIMEOUT_MILLIS = 4_000L

/** How long the hold-return waits for the playing channel's own category to load (#138). */
private const val JUMP_CATEGORY_TIMEOUT_MILLIS = 3_000L

/** A cold guide can take seconds to build its grid; the jump waits for it (#138). */
private const val JUMP_GUIDE_TIMEOUT_MILLIS = 8_000L

/** The guide's rows start this far below the body top (ruler 40 + 8). */
private const val GUIDE_ROWS_OFFSET_PX = 48

/** The body's bottom edge (spec #7 section 3: body 410 to 1026). */
private const val GUIDE_BODY_BOTTOM_PX = 1026
private const val GUIDE_ROW_HEIGHT_PX = ROW_HEIGHT_PX
private const val GUIDE_ROW_GAP_PX = com.rysal.happytv.ui.guide.ROW_GAP_PX

/** ⋮ menu anchor (below the header, right-aligned) and the channel / category menus. */
private const val MORE_MENU_X_PX = 1224
private const val MORE_MENU_Y_PX = 136
private const val CHANNEL_MENU_X_PX = 1180
private const val CHANNEL_MENU_MIN_Y_PX = 300
private const val CHANNEL_MENU_MAX_Y_PX = 580
private const val CATEGORY_MENU_X_PX = 520
private const val CATEGORY_MENU_Y_PX = BODY_TOP_PX

/** The empty states sit this far down inside the list pane. */
private const val EMPTY_STATE_TOP_PX = 136

/** The channel menu's hide item: "Unhide channel" when the channel is already hidden (#123). */
/** #171: the toggle removes the only row of the Favorites pane, which then shows its empty state. */
internal fun removesLastFavorite(selection: LiveCategorySelection, rowCount: Int, isFavorite: Boolean): Boolean =
    selection is LiveCategorySelection.Favorites && isFavorite && rowCount == 1

internal fun hideChannelLabel(hidden: Boolean): String = if (hidden) "Unhide channel" else "Hide channel"
