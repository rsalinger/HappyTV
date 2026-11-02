package com.rysal.happytv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import com.rysal.happytv.ui.focus.focusTargetAfterRemoval
import com.rysal.happytv.ui.search.ringSafeBringIntoView
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.tabular

/*
 * The fold-out category rail shared by Live TV and the Movies / Shows screen
 * (spec #7 section 3.2, spec #72). One component, so the two screens keep one
 * look and one set of key rules: selecting a row folds the rail away, Left
 * from the body fans it back out, "Keep categories open" makes it a permanent
 * pane.
 */

/** One row of the rail, whatever screen it is on. Immutable so unchanged rows are skipped. */
@Immutable
sealed interface RailEntry {
    val key: String

    @Immutable
    data class Row(
        override val key: String,
        val label: String,
        val count: Int,
        val icon: ImageVector?,
        val selected: Boolean,
        /** True when long-press OK / Menu opens a menu for this row. */
        val hasMenu: Boolean = false,
    ) : RailEntry

    @Immutable
    data class Header(val label: String) : RailEntry {
        override val key: String = "h:$label"
    }

    data object Divider : RailEntry {
        override val key: String = "__divider__"
    }
}

/** True for the rail entries that can take focus. */
internal fun RailEntry.isFocusable(): Boolean = this is RailEntry.Row

/** The rail's own geometry: [bodyTopPx] is where the screen's body starts (it differs per screen). */
internal class RailGeometry(val bodyTopPx: Int) {
    val railYPx: Int = bodyTopPx - 40
    val railHeightPx: Int = 1080 - railYPx - 14
    val scrimTopPx: Int = bodyTopPx - 16
}

/**
 * The rail plus, when it is not permanent, the 92% Ground scrim behind it.
 *
 * Folded away it is composed once and slid out (zero width), out of the focus order and the
 * accessibility tree, so fanning it out costs no composition and focus can land on the next
 * frame. "Keep categories open" ([keepOpen]) makes it a permanent pane with no animation.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HtRailLayer(
    entries: List<RailEntry>,
    geometry: RailGeometry,
    listState: LazyListState,
    keepOpen: Boolean,
    overlayShown: Boolean,
    railProgress: State<Float>,
    scrimProgress: State<Float>,
    entryKey: String?,
    entryRequester: FocusRequester,
    onSelect: (String) -> Unit,
    onRowFocused: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onRailFocusChanged: (Boolean) -> Unit,
) {
    val d = browseDesign()
    val railWidthPx = with(LocalDensity.current) { d.px(RAIL_WIDTH_PX).toPx() }
    val railFolded by remember { derivedStateOf { railProgress.value == 0f } }
    if (keepOpen) {
        HtRail(
            entries = entries,
            listState = listState,
            entryKey = entryKey,
            entryRequester = entryRequester,
            onSelect = onSelect,
            onRowFocused = onRowFocused,
            onLongPress = onLongPress,
            modifier = Modifier
                .offsetPx(RAIL_X_PX, geometry.railYPx + RAIL_VIEWPORT_INSET_PX)
                .size(width = d.px(RAIL_WIDTH_PX), height = d.px(geometry.railHeightPx - RAIL_VIEWPORT_INSET_PX))
                .onFocusChanged { onRailFocusChanged(it.hasFocus) },
        )
    } else {
        Box(
            modifier = Modifier
                .offsetPx(0, geometry.scrimTopPx)
                .size(width = d.px(1920), height = d.px(1080 - geometry.scrimTopPx))
                .graphicsLayer { alpha = scrimProgress.value }
                .background(HappyTvColors.Ground.copy(alpha = RAIL_SCRIM_ALPHA)),
        )
        HtRail(
            entries = entries,
            listState = listState,
            entryKey = entryKey,
            entryRequester = entryRequester,
            onSelect = onSelect,
            onRowFocused = onRowFocused,
            onLongPress = onLongPress,
            modifier = Modifier
                .offsetPx(RAIL_X_PX, geometry.railYPx + RAIL_VIEWPORT_INSET_PX)
                .size(width = d.px(RAIL_WIDTH_PX), height = d.px(geometry.railHeightPx - RAIL_VIEWPORT_INSET_PX))
                .graphicsLayer {
                    translationX = -(1f - railProgress.value) * railWidthPx
                    alpha = railProgress.value
                }
                .focusProperties {
                    canFocus = overlayShown
                    // Folded away, the rows are still composed just off screen: a directional move
                    // (Left from the header's Back button) must not land on one, or no visible
                    // node holds focus (#109). Focus requests aimed at a row are not affected.
                    enter = { if (overlayShown) FocusRequester.Default else FocusRequester.Cancel }
                }
                .focusGroup()
                .then(if (railFolded && !overlayShown) Modifier.clearAndSetSemantics { } else Modifier)
                .onFocusChanged { onRailFocusChanged(it.hasFocus) },
        )
    }
}

/**
 * The rail's rows in a LazyColumn. Focus moving through it loads nothing; OK or Right selects
 * ([RailRow]). [entryRequester] sits on the [entryKey] row so the screen can put focus on the
 * selected category.
 */
@Composable
internal fun HtRail(
    entries: List<RailEntry>,
    listState: LazyListState,
    entryKey: String?,
    entryRequester: FocusRequester,
    onSelect: (String) -> Unit,
    onRowFocused: (String) -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    val density = LocalDensity.current
    val ringPx = with(density) { d.px(RAIL_RING_PX).toPx() }
    val ringSafe = Modifier.ringSafeBringIntoView(ringPx, ringPx)
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = d.px(RAIL_PADDING_PX),
            end = d.px(RAIL_PADDING_PX),
            top = d.px(RAIL_RING_PX),
            bottom = d.px(RAIL_RING_PX),
        ),
        verticalArrangement = Arrangement.spacedBy(d.px(RAIL_GAP_PX)),
    ) {
        items(
            items = entries,
            key = { it.key },
            // F-42: the rail mixes visual shapes (pill rows, section headers, dividers).
            contentType = { entry ->
                when (entry) {
                    is RailEntry.Row -> "rail-row"
                    is RailEntry.Header -> "rail-header"
                    RailEntry.Divider -> "rail-divider"
                }
            },
        ) { entry ->
            when (entry) {
                is RailEntry.Row -> RailRow(
                    modifier = ringSafe,
                    label = entry.label,
                    count = entry.count,
                    icon = entry.icon,
                    selected = entry.selected,
                    onSelect = { onSelect(entry.key) },
                    onFocused = { onRowFocused(entry.key) },
                    onLongPress = if (entry.hasMenu) ({ onLongPress(entry.key) }) else null,
                    focusRequester = if (entry.key == entryKey) entryRequester else null,
                )
                is RailEntry.Header -> Text(
                    text = entry.label,
                    style = browseType().compactCaptionSemibold,
                    color = HappyTvColors.TextTertiary,
                    modifier = Modifier.padding(start = d.px(28), top = d.px(8), bottom = d.px(4)),
                )
                RailEntry.Divider -> RailDivider()
            }
        }
    }
}

/**
 * Where D-pad focus lands when the rail takes it (spec #7 section 3.2): an explicit [override]
 * (the row a closed menu belonged to), else the selected row, else the first row.
 */
internal fun railEntryKeyOf(entries: List<RailEntry>, override: String? = null): String? {
    if (override != null && entries.any { it.key == override }) return override
    return entries.firstOrNull { it is RailEntry.Row && it.selected }?.key
        ?: entries.firstOrNull { it.isFocusable() }?.key
}

/**
 * #114: the rail row that takes focus when the row [removedKey] is hidden: the next focusable row, else the
 * previous one. Null when nothing else can take it (or the key is not in the rail).
 */
internal fun railKeyAfterHide(entries: List<RailEntry>, removedKey: String): String? =
    entries.focusTargetAfterRemoval(entries.indexOfFirst { it.key == removedKey }) { it.isFocusable() }?.key

/**
 * Brings the rail row at [index] into the viewport. With [keepScroll] (focus is going to a row the user
 * was already looking at, #114) a row that is on screen is left where it is, so the list does not jump.
 */
internal suspend fun LazyListState.scrollRailTo(index: Int, keepScroll: Boolean) {
    if (keepScroll && layoutInfo.visibleItemsInfo.any { it.index == index }) return
    scrollToItem(index)
}

/**
 * Rail keys (spec #7 section 3.2): Right selects, same as OK; Left has nothing to the left of
 * the rail and is swallowed so focus can't jump to the list behind the scrim.
 */
internal fun Modifier.railKeys(onRight: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    when (event.key) {
        Key.DirectionRight -> {
            if (event.type == KeyEventType.KeyDown) onRight()
            true
        }
        Key.DirectionLeft -> true
        else -> false
    }
}

/** 400 x 64 pill item (spec #7 section 3.2): optional 32 px icon, name 28/36, count 24/32 Tertiary. */
@Composable
private fun RailRow(
    label: String,
    count: Int,
    icon: ImageVector?,
    selected: Boolean,
    onSelect: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val d = browseDesign()
    val type = browseType()
    Row(
        modifier = modifier
            .width(d.px(RAIL_ROW_WIDTH_PX))
            .height(d.px(RAIL_ROW_HEIGHT_PX))
            .railKeys(onRight = onSelect)
            // FocusRequester BEFORE the clickable surface: a FocusRequester only finds
            // focus targets that come *after* it in the chain, so placed after
            // htvClickableSurface it would find none and requestFocus() would
            // silently do nothing (F-40's sibling failure).
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .htvClickableSurface(
                radiusPx = RAIL_ROW_HEIGHT_PX / 2,
                background = HappyTvColors.Surface,
                onClick = onSelect,
                onLongClick = onLongPress,
                onFocused = onFocused,
                selected = selected,
            )
            .padding(start = if (icon != null) d.px(20) else d.px(28), end = d.px(28)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) HappyTvColors.Accent else HappyTvColors.TextSecondary,
                modifier = Modifier.size(d.px(32)),
            )
            Spacer(Modifier.width(d.px(16)))
        }
        Text(
            text = label,
            style = type.compactRowName,
            color = if (selected) HappyTvColors.TextPrimary else HappyTvColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Spacer(Modifier.width(d.px(16)))
            Text(
                text = formatCount(count),
                style = type.compactCaptionSemibold.tabular(),
                color = HappyTvColors.TextTertiary,
                maxLines = 1,
            )
        }
    }
}

/** 2 px Loading divider, 8 px above and below, inset 28 px. */
@Composable
private fun RailDivider(modifier: Modifier = Modifier) {
    val d = browseDesign()
    Box(
        modifier = modifier
            .padding(top = d.px(8), bottom = d.px(8), start = d.px(28), end = d.px(28))
            .fillMaxWidth()
            .height(d.px(2))
            .background(HappyTvColors.Loading),
    )
}

internal const val RAIL_X_PX = 56
internal const val RAIL_WIDTH_PX = 600
internal const val RAIL_PADDING_PX = 40
internal const val RAIL_GAP_PX = 8
internal const val RAIL_ROW_WIDTH_PX = 400
internal const val RAIL_ROW_HEIGHT_PX = 64

/**
 * Room kept around the focused row inside the rail's viewport: the focus ring (4 px) and its glow
 * (28 px blur, mostly faint). Focus-scroll reveals the row plus this margin, top and bottom, so a
 * focused row is never cut by the viewport edge (the technique of #70 in SearchScreen).
 */
internal const val RAIL_RING_PX = 24

/**
 * The rail's viewport starts this far below the container's top: the container's padding minus the
 * ring room. Nothing scrolls through the padding above it, so no row can sit under the header.
 */
internal const val RAIL_VIEWPORT_INSET_PX = RAIL_PADDING_PX - RAIL_RING_PX

/** Fold / fan animation (200 ms ease-in-out). */
internal const val RAIL_ANIMATION_MILLIS = 200
internal const val RAIL_SCRIM_ALPHA = 0.92f

/** Where "Keep categories open" puts the body's left edge: right of the permanent rail. */
internal const val BODY_X_KEEP_OPEN_PX = 520
