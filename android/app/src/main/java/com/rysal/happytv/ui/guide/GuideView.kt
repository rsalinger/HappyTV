package com.rysal.happytv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rysal.happytv.data.guide.GENRE_COLORS
import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.data.guide.GuideProgramState
import com.rysal.happytv.data.guide.GuideWindow
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.browse.HtBottomFade
import com.rysal.happytv.ui.browse.HtChannelMark
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.browse.htvClickableSurface
import com.rysal.happytv.ui.focus.awaitFocus
import com.rysal.happytv.ui.focus.tryRequest
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.HappyTvIcons
import com.rysal.happytv.ui.theme.happyTvFocusRing
import com.rysal.happytv.ui.theme.tabular
import kotlin.math.max
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The Guide view of the combined Live TV screen (spec #7 section 3.4): a corner
 * cell with the category, a two-hour time ruler, and 56 tall rows - a 360 wide
 * channel cell and title-only program blocks. The info band above shows what the
 * focused block is (the screen draws it), the Now line and pill cross the grid.
 *
 * This composable is placed by the screen at the body's top-left (x 96, or 520
 * with "Keep categories open", y 410) and extends 40 px up and left of it so
 * focus rings and glows are never clipped, exactly like the channel list.
 *
 * **Focus is Compose's.** Every program block and channel cell is a focus stop
 * that reports its own identity; a D-pad key asks the pure rules in
 * `GuideFocusNav` where it goes from *that* element and requests focus there.
 * OK is a click on the focused element, so it can never act on a stale idea of
 * what is focused. If the whole screen loses focus (a program aged into
 * ended x no-replay, the rows refilled) the grid takes it back.
 */
@Composable
fun GuideBody(
    guide: GuideUiState,
    cornerLabel: String,
    listWidthPx: Int,
    listHeightPx: Int,
    onOpenProgram: (GuideRow, GuideBlock.ProgramBlock, GuideFocus) -> Unit,
    onPlayChannel: (GuideChannel) -> Unit,
    onChannelLongPress: (GuideChannel) -> Unit,
    onLeftFromGrid: () -> Unit,
    onFocused: (GuideFocus) -> Unit,
    onVisibleChannels: (List<String>) -> Unit,
    onNeedMoreRows: (Int) -> Unit,
    /** #146: Left/Right in time; moves the grid's window if needed and returns the program to focus. */
    onTimeMove: (GuideFocus.Program, GuideKey) -> GuideFocus.Program?,
    /** #146: the window start the ViewModel is on; differs from [guide]'s while a move is still being drawn. */
    currentWindowStart: () -> Long,
    onOpenGuideSettings: () -> Unit,
    entryFocus: FocusRequester,
    menuRowFocus: FocusRequester,
    menuChannelId: String?,
    restoreTarget: GuideFocus?,
    restoreTick: Int,
    entryTick: Int,
    screenHasFocus: Boolean,
    healFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    val rows = guide.rows

    if (guide.isEmptyState) {
        GuideEmptyState(
            onOpenGuideSettings = onOpenGuideSettings,
            entryFocus = entryFocus,
            modifier = modifier.padding(start = d.px(BREATHING_PX), top = d.px(BREATHING_PX)),
        )
        return
    }

    val fadeZonePx = with(LocalDensity.current) { d.px(BOTTOM_FADE_PX + FOCUS_ABOVE_FADE_PX).toPx() }
    val rowsNow by rememberUpdatedState(rows)
    val guideNow by rememberUpdatedState(guide)
    val currentTimeMove by rememberUpdatedState(onTimeMove)
    val currentWindowStartOf by rememberUpdatedState(currentWindowStart)
    val screenHasFocusNow by rememberUpdatedState(screenHasFocus)
    val listState = remember(guide.tag) { LazyListState() }
    val requesters = remember(guide.tag) { HashMap<String, FocusRequester>() }
    // Per selection: a focus remembered from the previous category must never be
    // "healed" back onto the new one (it put focus on row 7 after choosing a category).
    var focused by remember(guide.tag) { mutableStateOf<GuideFocus?>(null) }
    var lastFocused by remember(guide.tag) { mutableStateOf<GuideFocus?>(null) }
    var pending by remember(guide.tag) { mutableStateOf<GuideFocus?>(null) }
    // Not a state read in composition: a counter here recomposed the whole body (ruler,
    // gridlines, Now line, the row list's content lambda) on every D-pad move (#52).
    val navRequests = remember(guide.tag) { Channel<Unit>(Channel.CONFLATED) }
    // Bumped when a move never landed, so the heal effect looks again once nothing is in flight.
    var healTick by remember(guide.tag) { mutableIntStateOf(0) }

    fun navigate(target: GuideFocus) {
        pending = target
        navRequests.trySend(Unit)
    }

    // The timeline is as wide as the body allows: the reference 1360 px, or less
    // when "Keep categories open" narrows the body.
    val timelineWidth = listWidthPx - CHANNEL_CELL_WIDTH_PX - CHANNEL_CELL_GAP_PX
    val scale = timelineWidth / GuideWindow.TIMELINE_WIDTH_PX

    // Left/Right on a program: the grid's own rule inside the window, the time rule at its
    // edges (#146). While an earlier step is still being drawn the rows are stale, so the
    // time rule (which reads the ViewModel's own state) decides alone.
    fun moveFocusInTime(origin: GuideFocus, key: GuideKey): GuideFocus? {
        if (origin !is GuideFocus.Program || (key != GuideKey.LEFT && key != GuideKey.RIGHT)) {
            return moveFocus(origin, key, rowsNow)
        }
        val stale = currentWindowStartOf() != guideNow.window.startUtcMillis
        val inGrid = if (stale) null else moveFocus(origin, key, rowsNow)
        return if (key == GuideKey.RIGHT) {
            inGrid ?: currentTimeMove(origin, key)
        } else {
            // Left: while moved forward the previous program may be off screen to the left.
            val inTime = if (stale || guideNow.shifted) currentTimeMove(origin, key) else null
            inTime ?: inGrid ?: if (stale) GuideFocus.ChannelCell(origin.row) else null
        }
    }

    // The navigator is built once, so what it calls must be read live.
    val currentOnLeft by rememberUpdatedState(onLeftFromGrid)
    val currentOnFocused by rememberUpdatedState(onFocused)
    val currentOnNeedMore by rememberUpdatedState(onNeedMoreRows)
    val currentOnOpen by rememberUpdatedState(onOpenProgram)
    val currentOnPlay by rememberUpdatedState(onPlayChannel)
    val currentOnLongPress by rememberUpdatedState(onChannelLongPress)
    val nav = remember(requesters) {
        GuideNavigator(
            requesterFor = { target -> requesters.getOrPut(focusKey(target)) { FocusRequester() } },
            moveFrom = { current, key ->
                // A move still on its way counts as where we are: a held key must
                // keep stepping, not restart from the element that has not lost focus yet.
                val origin = pending?.takeIf { it != focused } ?: current
                val target = moveFocusInTime(origin, key)
                when {
                    target != null -> {
                        navigate(target)
                        true
                    }
                    else -> when (edgeFor(current, key)) {
                        GuideEdge.RAIL -> {
                            currentOnLeft()
                            true
                        }
                        GuideEdge.HEADER -> false
                        GuideEdge.STOP -> true
                    }
                }
            },
            focusChanged = { target, hasFocus ->
                if (hasFocus) {
                    focused = target
                    lastFocused = target
                    if (pending == target) pending = null
                    currentOnFocused(target)
                    if (target.row() >= rowsNow.size - GuideViewModel.ROW_LOOKAHEAD) {
                        currentOnNeedMore(target.row())
                    }
                } else if (focused == target) {
                    focused = null
                }
            },
            open = { row, block, from -> currentOnOpen(row, block, from) },
            play = { channel -> currentOnPlay(channel) },
            longPress = { channel -> currentOnLongPress(channel) },
            entryRequester = entryFocus,
            menuRequester = menuRowFocus,
        )
    }

    // Scroll the target row into composition (when needed), then ask for focus
    // until it really lands, bounded by time (not frames). No sleeps.
    LaunchedEffect(navRequests) {
        // A newer request cancels the one still waiting for focus, as a new tick did.
        navRequests.receiveAsFlow().collectLatest {
            val target = pending ?: return@collectLatest
            val landed = awaitFocus(
                landed = { focused == target },
                request = {
                    val row = target.row()
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == row }) {
                        listState.scrollToItem(row.coerceAtLeast(0))
                    }
                    nav.requesterFor(target).tryRequest()
                },
            )
            // #94: the focused row (and its ring) must sit above the bottom fade, never
            // under it. Compose only scrolls it to the viewport edge, so finish the job.
            listState.layoutInfo.let { info ->
                val item = info.visibleItemsInfo.firstOrNull { it.index == target.row() }
                if (item != null) {
                    val limit = info.viewportSize.height - fadeZonePx
                    val excess = item.offset + item.size - limit
                    if (excess > 0) listState.scrollBy(excess.toFloat())
                }
            }
            // Only clear what this wait was for: a newer move may have set `pending` while the
            // focus request for this one was landing, and clearing that left no wait for it and
            // the D-pad on nothing (#146, found with a held key).
            if (pending == target) pending = null
            // The target never took focus (the grid moved on under a held key, #146): re-pick in
            // the same row from what is on screen now, or let the heal effect below decide, so
            // the D-pad is never left on nothing.
            if (!landed && !screenHasFocusNow && pending == null) {
                val fallback = revalidateFocus(target, rowsNow)
                if (fallback != null && fallback != target) navigate(fallback) else healTick++
            }
        }
    }

    // #138: the channel asked to take focus is scrolled into composition first.
    LaunchedEffect(listState, menuChannelId, rows) {
        val index = rows.indexOfFirst { it.channel.streamId == menuChannelId }
        if (menuChannelId != null && index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
        }
    }

    // The details dialog closed: focus goes back to the program that opened it.
    LaunchedEffect(restoreTick) {
        if (restoreTick == 0) return@LaunchedEffect
        val target = restoreTarget?.let { revalidateFocus(it, rowsNow) } ?: return@LaunchedEffect
        navigate(target)
    }

    // Down from the header: onto the grid, at the last focused program or the first row's entry.
    LaunchedEffect(entryTick) {
        if (entryTick == 0) return@LaunchedEffect
        val target = lastFocused?.let { revalidateFocus(it, rowsNow) } ?: firstRowFocus(rowsNow)
        if (target != null) navigate(target)
    }

    // Nothing on the screen holds focus (a focused program disappeared, the rows
    // were rebuilt under it): take it back, so a key is never sent into nothing.
    LaunchedEffect(screenHasFocus, healFocus, rows, healTick) {
        if (!healFocus || screenHasFocus || rows.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (screenHasFocusNow || pending != null) return@LaunchedEffect
        val target = lastFocused?.let { revalidateFocus(it, rowsNow) } ?: firstRowFocus(rowsNow)
        if (target != null) navigate(target)
    }

    // `guide.open` ends when the grid is filled; the details dialog's close probe
    // ends when the grid is back and nothing is open over it.
    LaunchedEffect(guide.gridReady) {
        if (!guide.gridReady) return@LaunchedEffect
        withFrameNanos { }
        HtvPerf.end(HtvPerf.GUIDE_OPEN, "rows=${rows.size}")
    }

    // Which rows are on screen -> the ViewModel's shallow (now + next) fetch.
    LaunchedEffect(listState, rows) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                rows.getOrNull(info.index)?.channel?.streamId
            }
        }.collect { ids -> if (ids.isNotEmpty()) onVisibleChannels(ids) }
    }

    val entry = firstRowFocus(rows)
    val timelineLeft = BREATHING_PX + CHANNEL_CELL_WIDTH_PX + CHANNEL_CELL_GAP_PX

    Box(
        modifier = modifier
            .offset(x = -d.px(BREATHING_PX), y = -d.px(BREATHING_PX))
            .size(d.px(listWidthPx + 2 * BREATHING_PX), d.px(listHeightPx + BREATHING_PX)),
    ) {
        // Corner cell: the selected category, so it stays visible while the rail is folded.
        Box(
            modifier = Modifier
                .offset(d.px(BREATHING_PX), d.px(BREATHING_PX))
                .size(d.px(CHANNEL_CELL_WIDTH_PX), d.px(RULER_HEIGHT_PX)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = cornerLabel,
                style = browseType().compactCaptionSemibold.tabular(),
                color = HappyTvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = d.px(12)),
            )
        }

        // Ruler: half-hour labels with their ticks; a label is hidden while the Now
        // pill sits over it.
        Box(
            modifier = Modifier
                .offset(d.px(timelineLeft), d.px(BREATHING_PX))
                .size(d.px(timelineWidth), d.px(RULER_HEIGHT_PX)),
        ) {
            guide.ticks.forEach { (x, label) ->
                val hidden = guide.showNowLine && labelHiddenByNow(x, guide.nowX)
                Row(
                    modifier = Modifier
                        .offset(d.px(x * scale), d.px(4))
                        .height(d.px(RULER_HEIGHT_PX - 8)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(d.px(2))
                            .fillMaxHeight()
                            .background(HappyTvColors.Loading),
                    )
                    if (!hidden) {
                        Spacer(Modifier.width(d.px(12)))
                        Text(
                            text = label,
                            style = browseType().compactCaptionSemibold.tabular(),
                            color = HappyTvColors.TextTertiary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        // Gridlines: the ruler's ticks continued down behind the rows.
        Box(
            modifier = Modifier
                .offset(d.px(timelineLeft), d.px(BREATHING_PX + RULER_HEIGHT_PX))
                .size(d.px(timelineWidth), d.px(listHeightPx - RULER_HEIGHT_PX)),
        ) {
            guide.ticks.forEach { (x, _) ->
                Box(
                    modifier = Modifier
                        .offset(d.px(x * scale), 0.dp)
                        .width(d.px(2))
                        .fillMaxHeight()
                        .background(HappyTvColors.GuideGridline),
                )
            }
        }

        // The Now line, behind the rows (#94): program blocks paint over it, so it shows in
        // the gaps between blocks and rows and through the translucent later/ended blocks,
        // and never crosses a title. The pill and the stub above the first row mark it.
        if (guide.showNowLine) {
            Box(
                modifier = Modifier
                    .offset(
                        d.px(timelineLeft + guide.nowX * scale - 1),
                        d.px(BREATHING_PX + RULER_HEIGHT_PX),
                    )
                    .size(d.px(3), d.px(listHeightPx - RULER_HEIGHT_PX))
                    .background(HappyTvColors.Accent.copy(alpha = 0.9f)),
            )
        }

        // Rows: 40 px ring headroom on every side (the ring draws 4 px outside
        // the cell and LazyColumn would clip it otherwise).
        LazyColumn(
            state = listState,
            modifier = Modifier
                // Clipped to the viewport below the ruler: scrolled rows never draw over
                // the ruler or the corner cell. Ring room above the first row is the 8 px gap.
                .offset(0.dp, d.px(BREATHING_PX + RULER_HEIGHT_PX))
                .size(
                    d.px(listWidthPx + 2 * BREATHING_PX),
                    d.px(listHeightPx - RULER_HEIGHT_PX),
                ),
            contentPadding = PaddingValues(
                start = d.px(BREATHING_PX),
                top = d.px(ROWS_GAP_BELOW_RULER_PX),
                end = d.px(BREATHING_PX),
                // Room for the last row to scroll up out of the bottom fade (#94).
                bottom = d.px(BOTTOM_FADE_PX + FOCUS_ABOVE_FADE_PX),
            ),
            verticalArrangement = Arrangement.spacedBy(d.px(ROW_GAP_PX)),
        ) {
            itemsIndexed(
                items = rows,
                key = { _, row -> row.channel.streamId },
                contentType = { _, _ -> "guide-row" },
            ) { index, row ->
                // Every parameter is stable and unchanged by a focus move, so a row is
                // skipped when only the info band or the focus changed.
                GuideRowUi(
                    index = index,
                    row = row,
                    scale = scale,
                    timelineWidth = timelineWidth,
                    nav = nav,
                    entryCell = entry == GuideFocus.ChannelCell(index),
                    entryProgramKey = (entry as? GuideFocus.Program)?.takeIf { it.row == index }?.startUtcMillis,
                    isMenuRow = row.channel.streamId == menuChannelId,
                )
            }
        }

        // design spec's 120 px bottom fade, ending at the body's bottom edge.
        HtBottomFade(
            modifier = Modifier.offset(x = 0.dp, y = d.px(BREATHING_PX + listHeightPx - BOTTOM_FADE_PX)),
            heightPx = BOTTOM_FADE_PX,
        )

        if (guide.showNowLine) {
            val nowLeft = timelineLeft + guide.nowX * scale
            // #94: the line itself is drawn behind the rows (above), so it never strikes
            // through a title; here only the stub between the ruler and the first row
            // is drawn, joining the pill to the line.
            Box(
                modifier = Modifier
                    .offset(d.px(nowLeft - 1), d.px(BREATHING_PX + RULER_HEIGHT_PX))
                    .size(d.px(3), d.px(ROWS_GAP_BELOW_RULER_PX))
                    .background(HappyTvColors.Accent.copy(alpha = 0.9f)),
            )
            Box(
                modifier = Modifier
                    .offset(d.px(nowLeft - NOW_PILL_WIDTH_PX / 2), d.px(BREATHING_PX + (RULER_HEIGHT_PX - NOW_PILL_HEIGHT_PX) / 2))
                    .size(d.px(NOW_PILL_WIDTH_PX), d.px(NOW_PILL_HEIGHT_PX))
                    .background(HappyTvColors.Accent, RoundedCornerShape(d.px(NOW_PILL_HEIGHT_PX / 2))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Now ${guide.clockLabel}",
                    style = browseType().compactCaption.copy(fontWeight = FontWeight.W800).tabular(),
                    color = HappyTvColors.OnAccent,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * True when the Now pill (centered on [nowX]) would cover the ruler label that
 * starts at [tickX]. Pure, so the "hide the overlapping ruler label" rule is
 * testable.
 */
fun labelHiddenByNow(tickX: Float, nowX: Float): Boolean {
    val labelLeft = tickX
    val labelRight = tickX + RULER_LABEL_WIDTH_PX
    val pillLeft = nowX - NOW_PILL_WIDTH_PX / 2
    val pillRight = nowX + NOW_PILL_WIDTH_PX / 2
    return labelLeft < pillRight && labelRight > pillLeft
}

/** What the grid's elements call to move, report focus, and find their requesters. */
@androidx.compose.runtime.Stable
internal class GuideNavigator(
    val requesterFor: (GuideFocus) -> FocusRequester,
    val moveFrom: (GuideFocus, GuideKey) -> Boolean,
    val focusChanged: (GuideFocus, Boolean) -> Unit,
    /** OK on a program, play / long-press on a channel cell: one stable set, so rows skip recomposition. */
    val open: (GuideRow, GuideBlock.ProgramBlock, GuideFocus) -> Unit,
    val play: (GuideChannel) -> Unit,
    val longPress: (GuideChannel) -> Unit,
    /** The first row's entry requester and the channel menu's hand-back requester (kept here so rows stay skippable). */
    val entryRequester: FocusRequester,
    val menuRequester: FocusRequester,
)

/** Gap blocks have no start time; their keys sit far below any program's epoch millis. */
private const val GAP_KEY_BASE = -1L

private fun focusKey(target: GuideFocus): String = when (target) {
    is GuideFocus.ChannelCell -> "c${target.row}"
    is GuideFocus.Program -> "p${target.row}:${target.startUtcMillis}"
}

private fun GuideFocus.row(): Int = when (this) {
    is GuideFocus.ChannelCell -> row
    is GuideFocus.Program -> row
}

/** Arrow keys on a grid element go through [onKey]; true consumes the key. */
private fun Modifier.guideArrowKeys(onKey: (GuideKey) -> Boolean): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val key = when (event.key) {
            Key.DirectionLeft -> GuideKey.LEFT
            Key.DirectionRight -> GuideKey.RIGHT
            Key.DirectionUp -> GuideKey.UP
            Key.DirectionDown -> GuideKey.DOWN
            else -> null
        } ?: return@onPreviewKeyEvent false
        onKey(key)
    }

// ------------------------------------------------------------ row

/** One channel row: the cell and its blocks. */
@Composable
private fun GuideRowUi(
    index: Int,
    row: GuideRow,
    scale: Float,
    timelineWidth: Int,
    nav: GuideNavigator,
    entryCell: Boolean,
    entryProgramKey: Long?,
    isMenuRow: Boolean,
) {
    val d = browseDesign()
    Row(
        modifier = Modifier.height(d.px(ROW_HEIGHT_PX)),
        horizontalArrangement = Arrangement.spacedBy(d.px(CHANNEL_CELL_GAP_PX)),
    ) {
        val cellFocus = GuideFocus.ChannelCell(index)
        GuideChannelCell(
            row = row,
            focus = cellFocus,
            nav = nav,
            entryFocus = nav.entryRequester.takeIf { entryCell },
            menuRowFocus = nav.menuRequester.takeIf { isMenuRow },
        )
        Box(modifier = Modifier.size(d.px(timelineWidth), d.px(ROW_HEIGHT_PX))) {
            row.blocks.forEach { block ->
                // Keyed by identity: when the window moves (#146) a program's node, its focus and
                // its requester follow the program instead of the slot it used to sit in.
                key(if (block is GuideBlock.ProgramBlock) block.key else GAP_KEY_BASE - block.x.toLong()) {
                    when (block) {
                        is GuideBlock.ProgramBlock -> {
                            val programFocus = GuideFocus.Program(index, block.key)
                            GuideProgramBlockUi(
                                row = row,
                                block = block,
                                focus = programFocus,
                                scale = scale,
                                nav = nav,
                                entryFocus = nav.entryRequester.takeIf { entryProgramKey == block.key },
                            )
                        }
                        is GuideBlock.GapBlock -> GuideGapBlockUi(block = block, scale = scale)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ channel cell

@Composable
internal fun GuideChannelCell(
    row: GuideRow,
    focus: GuideFocus.ChannelCell,
    nav: GuideNavigator,
    entryFocus: FocusRequester?,
    menuRowFocus: FocusRequester?,
) {
    val d = browseDesign()
    val type = browseType()
    val channel = row.channel
    val reader = buildString {
        append(channel.name)
        channel.num?.let { append(", channel $it") }
        if (channel.isFavorite) append(", favorite")
    }
    var cellFocused by remember { mutableStateOf(false) }
    // #155: rows are keyed by channel, so a row above this one leaving (Hide channel) moves the
    // focused cell to a new index without a focus change. Say who holds focus now, or the info
    // band and the rail-return target keep describing the old index.
    LaunchedEffect(focus) { if (cellFocused) nav.focusChanged(focus, true) }
    Row(
        modifier = Modifier
            .size(d.px(CHANNEL_CELL_WIDTH_PX), d.px(ROW_HEIGHT_PX))
            .onFocusChanged {
                cellFocused = it.isFocused
                nav.focusChanged(focus, it.isFocused)
            }
            .guideArrowKeys { key -> nav.moveFrom(focus, key) }
            .focusRequester(nav.requesterFor(focus))
            .then(if (entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
            .then(if (menuRowFocus != null) Modifier.focusRequester(menuRowFocus) else Modifier)
            .htvClickableSurface(
                radiusPx = 12,
                background = HappyTvColors.Surface,
                onClick = { nav.play(channel) },
                onLongClick = { nav.longPress(channel) },
                focusedOverride = cellFocused,
            )
            .semantics { contentDescription = reader }
            .padding(start = d.px(CHANNEL_CELL_PAD_START_PX), end = d.px(CHANNEL_CELL_PAD_END_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(d.px(CHANNEL_NUMBER_COLUMN_PX))) {
            Text(
                text = channel.num?.toString().orEmpty(),
                style = type.compactCaptionSemibold.tabular(),
                color = HappyTvColors.TextTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.width(d.px(CHANNEL_NUMBER_GAP_PX)))
        HtChannelMark(
            name = channel.name,
            logoUrl = channel.logoUrl,
            widthPx = 72,
            heightPx = 48,
            radiusPx = 8,
            textStyle = type.compactCaptionSemibold,
        )
        Spacer(Modifier.width(d.px(12)))
        // #94: up to two lines in the unchanged 56 px row. 24 px is the floor, so the
        // line height is tightened (26 + 26 = 52); a name that fits one line stays on one.
        val nameStyle = remember(type) {
            type.compactCaption.copy(
                fontWeight = FontWeight.W700,
                lineHeight = d.sp(CHANNEL_NAME_LINE_HEIGHT_PX),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                // Every line exactly the line height (first line included), so two fit 56 px.
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            )
        }
        Text(
            text = channel.name,
            style = nameStyle,
            color = HappyTvColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Unbounded height: Compose ellipsizes a Text to the lines that fit its max height,
            // and the row's 56 px left room for only one line on the TV (#94 QA).
            modifier = Modifier
                .weight(1f)
                .wrapContentHeight(Alignment.CenterVertically, unbounded = true)
                .testTag(CHANNEL_NAME_TAG),
        )
        if (channel.isFavorite) {
            Spacer(Modifier.width(d.px(8)))
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = HappyTvColors.Accent,
                modifier = Modifier.size(d.px(24)),
            )
        }
    }
}

// ----------------------------------------------------------- program block

@Composable
private fun GuideProgramBlockUi(
    row: GuideRow,
    block: GuideBlock.ProgramBlock,
    focus: GuideFocus.Program,
    scale: Float,
    nav: GuideNavigator,
    entryFocus: FocusRequester?,
) {
    val onOpen = { nav.open(row, block, focus) }
    val d = browseDesign()
    val type = browseType()
    // design spec fills: now = Raised, later = Surface 80%, ended = 50%. The alpha is on
    // the fill and the text, not on the node, so the focus ring is never faded.
    val alpha = when (block.state) {
        GuideProgramState.LIVE -> 1f
        GuideProgramState.ENDED -> 0.5f
        GuideProgramState.FUTURE -> 0.8f
    }
    val fill = if (block.state == GuideProgramState.LIVE) HappyTvColors.Raised else HappyTvColors.Surface
    val textAlpha = if (block.state == GuideProgramState.ENDED) 0.5f else 1f
    val width = max(0f, (block.w + BLOCK_GAP_PX) * scale - BLOCK_GAP_PX)
    val reader = "${block.program.title}, ${block.timeLabel}"

    val shapeModifier = Modifier
        .offset(d.px(block.x * scale), 0.dp)
        .size(d.px(width), d.px(ROW_HEIGHT_PX))

    val hasDot = block.genre?.let { GENRE_COLORS[it] } != null
    val content: @Composable () -> Unit = {
      if (!hasDot && !block.showReplayIcon) {
        // The common block is a title and nothing else: no Row and Box around its one Text (#52).
        Text(
            text = block.program.title,
            style = type.compactCaptionSemibold,
            color = HappyTvColors.TextPrimary.copy(alpha = textAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxHeight()
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = d.px(14)),
        )
      } else {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = d.px(14)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            block.genre?.let { genre ->
                GENRE_COLORS[genre]?.let { hex ->
                    Box(
                        modifier = Modifier
                            .size(d.px(10))
                            .background(Color(hex).copy(alpha = textAlpha), CircleShape),
                    )
                    Spacer(Modifier.width(d.px(10)))
                }
            }
            Text(
                text = block.program.title,
                style = type.compactCaptionSemibold,
                color = HappyTvColors.TextPrimary.copy(alpha = textAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (block.showReplayIcon) {
                Spacer(Modifier.width(d.px(8)))
                Icon(
                    imageVector = GuideReplayIcon,
                    contentDescription = "Replay available",
                    tint = HappyTvColors.TextSecondary,
                    modifier = Modifier.size(d.px(24)),
                )
            }
        }
      }
    }

    if (!block.focusable) {
        // Ended with no replay: a static span, no focus stop (design spec rows).
        Box(
            modifier = shapeModifier
                .background(fill.copy(alpha = alpha), RoundedCornerShape(d.px(12))),
        ) { content() }
        return
    }

    // One plain focus flag per block, no interaction-source collector and none of the
    // long-press machinery cells need: a screen of these is ~50 nodes, composed again
    // for every row the D-pad scrolls in (the guide janked at 60% with the heavier surface).
    var isFocused by remember { mutableStateOf(false) }
    LaunchedEffect(focus) { if (isFocused) nav.focusChanged(focus, true) } // #155, as for the cell
    val radius = d.px(12)
    Box(
        modifier = shapeModifier
            .onFocusChanged {
                isFocused = it.isFocused
                nav.focusChanged(focus, it.isFocused)
            }
            .guideArrowKeys { key -> nav.moveFrom(focus, key) }
            .focusRequester(nav.requesterFor(focus))
            .then(if (entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
            .background(fill.copy(alpha = alpha), RoundedCornerShape(radius))
            .happyTvFocusRing(focused = isFocused, radius = radius, focusRingColor = HappyTvColors.Accent)
            .clip(RoundedCornerShape(radius))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .semantics { contentDescription = reader },
    ) { content() }
}

/** Striped "No guide info / data" span (135 degrees, 14 px bands), at row height. */
@Composable
private fun GuideGapBlockUi(block: GuideBlock.GapBlock, scale: Float) {
    val d = browseDesign()
    val density = LocalDensity.current
    val groundArgb = HappyTvColors.Ground.toArgb()
    val surfaceArgb = HappyTvColors.Surface.toArgb()
    val brush = remember(d, density, groundArgb, surfaceArgb) {
        val band = with(density) { d.px(14).toPx() }
        DiagonalStripeBrush(
            bandPx = band,
            light = groundArgb,
            dark = surfaceArgb,
        )
    }
    val width = max(0f, (block.w + BLOCK_GAP_PX) * scale - BLOCK_GAP_PX)
    Box(
        modifier = Modifier
            .offset(d.px(block.x * scale), 0.dp)
            .size(d.px(width), d.px(ROW_HEIGHT_PX))
            .clip(RoundedCornerShape(d.px(12)))
            .background(brush)
            .padding(horizontal = d.px(14)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = block.label,
            style = browseType().compactCaptionSemibold,
            color = HappyTvColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Hard 14 px bands along a 135 degree axis (down-right), repeating forever.
 * Android's LinearGradient with REPEAT tiles along its axis - two flat colors
 * split at the midpoint give the hard band edge CSS produces.
 */
private class DiagonalStripeBrush(
    bandPx: Float,
    private val light: Int,
    private val dark: Int,
) : ShaderBrush() {
    private val axis = bandPx * 2f / kotlin.math.sqrt(2f)

    override fun createShader(size: Size): Shader = android.graphics.LinearGradient(
        0f,
        0f,
        axis,
        axis,
        intArrayOf(light, light, dark, dark),
        floatArrayOf(0f, 0.4999f, 0.5f, 1f),
        android.graphics.Shader.TileMode.REPEAT,
    )
}

// ------------------------------------------------------------- empty state

/** "No guide yet" with its way out, in the body's place (design spec empty state, compact sizes). */
@Composable
private fun GuideEmptyState(
    onOpenGuideSettings: () -> Unit,
    entryFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    val type = browseType()
    Column(
        modifier = modifier.widthIn(max = d.px(1000)),
        verticalArrangement = Arrangement.spacedBy(d.px(20)),
    ) {
        Icon(
            imageVector = HappyTvIcons.Guide,
            contentDescription = null,
            tint = HappyTvColors.TextTertiary,
            modifier = Modifier.size(d.px(64)),
        )
        Text(text = "No guide yet", style = type.compactHeading, color = HappyTvColors.TextPrimary)
        Text(
            text = "Guide data is being loaded from your provider. " +
                "If this persists, your provider may not offer guide data " +
                "— or you can add an XMLTV link in Settings.",
            style = type.compactBody,
            color = HappyTvColors.TextSecondary,
        )
        Box(
            modifier = Modifier
                .height(d.px(64))
                .focusRequester(entryFocus)
                .htvClickableSurface(
                    radiusPx = 32,
                    background = HappyTvColors.Accent,
                    onClick = onOpenGuideSettings,
                )
                .padding(horizontal = d.px(40)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Open guide settings",
                style = type.compactRowName.copy(fontWeight = FontWeight.W800),
                color = HappyTvColors.OnAccent,
            )
        }
    }
}

// ----------------------------------------------------------- header controls

/**
 * A segmented control for the header (spec #7 section 3.6): a Surface pill well
 * with one Overlay segment selected. 56 tall overall; each segment is its own
 * focus stop and OK selects. Used for the day picker and the Guide | List toggle.
 */
@Composable
fun HeaderSegments(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPaddingPx: Int = 20,
) {
    val d = browseDesign()
    val type = browseType()
    Row(
        modifier = modifier
            .background(HappyTvColors.Surface, RoundedCornerShape(d.px(28)))
            .padding(d.px(4)),
        horizontalArrangement = Arrangement.spacedBy(d.px(4)),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .height(d.px(48))
                    .htvClickableSurface(
                        radiusPx = 24,
                        background = if (selected) HappyTvColors.Overlay else HappyTvColors.Surface,
                        onClick = { onSelect(index) },
                    )
                    .semantics { contentDescription = if (selected) "$label, selected" else label }
                    .padding(horizontal = d.px(horizontalPaddingPx)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = type.compactCaptionSemibold.copy(
                        fontWeight = if (selected) FontWeight.W700 else FontWeight.W600,
                    ),
                    color = if (selected) HappyTvColors.TextPrimary else HappyTvColors.TextSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- icons

/** Replay - counter-clockwise arc with an arrowhead (stroke 2), 24-unit grid. */
internal val GuideReplayIcon: ImageVector by lazy {
    guideLineIcon("Guide Replay", 2f) {
        moveTo(4.75f, 12f)
        arcTo(7.25f, 7.25f, 0f, isMoreThanHalf = true, isPositiveArc = false, 6.87f, 6.87f)
        moveTo(4.75f, 4.25f)
        lineTo(4.75f, 8.75f)
        lineTo(9.25f, 8.75f)
    }
}

private fun guideLineIcon(
    name: String,
    strokeWidth: Float,
    block: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}.build()

// ------------------------------------------------------------- geometry

/** Room around the body for focus rings and glows (same as the channel list's). */
private const val BREATHING_PX = 40
private const val RULER_HEIGHT_PX = 40
/** Rows start 8 under the ruler (top 458 under a ruler that ends at 450). */
private const val ROWS_GAP_BELOW_RULER_PX = 8
/**
 * Five tabular digits at 24/32 600 on one line (about 75 px). #94: the number is followed
 * by [CHANNEL_NUMBER_GAP_PX] before the badge, taken from the cell's side paddings
 * (10 + 12, was 12 + 16) so the name keeps the width it had.
 */
internal const val CHANNEL_NAME_TAG = "guide.channel.name"
internal const val CHANNEL_NUMBER_COLUMN_PX = 76
internal const val CHANNEL_NUMBER_GAP_PX = 8
internal const val CHANNEL_CELL_PAD_START_PX = 10
internal const val CHANNEL_CELL_PAD_END_PX = 12
/** Two lines of the 24 px name must fit the 56 px row. */
internal const val CHANNEL_NAME_LINE_HEIGHT_PX = 26
private const val BOTTOM_FADE_PX = 120
/** Clear space under a focused row: its 4 px ring plus glow, above the fade (#94). */
private const val FOCUS_ABOVE_FADE_PX = 8
/**
 * Fits the widest label, "Now 12:59": nine tabular glyphs at 24 px 800 (~132 px) plus the
 * spec's 14 px side padding. 124 clipped "Now 10:06" (#57); the pill keeps one width
 * so the Now line and the hidden ruler label never jump as the minute changes.
 */
internal const val NOW_PILL_WIDTH_PX = 164f
private const val NOW_PILL_HEIGHT_PX = 36f
private const val RULER_LABEL_WIDTH_PX = 84f

