package com.rysal.happytv.ui.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.browse.HtPosterImage
import com.rysal.happytv.ui.browse.HtSecondaryButton
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.browse.htvClickableSurface
import com.rysal.happytv.ui.search.ringSafeBringIntoView
import com.rysal.happytv.ui.search.ringSafeBringIntoViewX
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.rysal.happytv.ui.theme.HappyTvColors

/**
 * The show details page's episode area (#74): a row of season chips, then one row per episode of
 * the focused season.
 *
 * Focus: Down from the actions enters the chips on the **selected** one (not whichever is nearest),
 * Down again reaches the list, Up from the list returns to the selected chip. Focusing a chip
 * selects its season and swaps the list under it; focus never moves. Every focusable has its ring
 * inside the viewport (the list pads its ends by the ring, and [ringSafeBringIntoView] keeps a
 * focused row clear of the bottom safe area, as in #70).
 */
class EpisodeFocus {
    private val chips = HashMap<Int, FocusRequester>()
    private val rows = HashMap<String, FocusRequester>()

    fun chip(season: Int): FocusRequester = chips.getOrPut(season) { FocusRequester() }
    fun row(episodeId: String): FocusRequester = rows.getOrPut(episodeId) { FocusRequester() }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeArea(
    state: EpisodesState,
    marks: Map<String, EpisodeMark>,
    /** The season whose chip is selected; null picks the first. */
    selectedSeason: Int?,
    onSeasonSelected: (Int) -> Unit,
    onPlayEpisode: (EpisodeItem) -> Unit,
    onRetry: () -> Unit,
    focus: EpisodeFocus,
    listState: LazyListState,
    /** The episode row that just took focus (#93): the page shows its description under the poster. */
    onEpisodeFocused: (EpisodeItem) -> Unit = {},
    /** Whether focus is on an episode row (not the chips). */
    onRowsFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    when (state) {
        EpisodesState.Idle -> Unit
        EpisodesState.Loading -> EpisodeSkeleton(modifier)
        EpisodesState.Failed -> Column(modifier) {
            Text(
                text = "Could not load episodes.",
                style = browseType().body,
                color = HappyTvColors.TextSecondary,
            )
            Spacer(Modifier.height(d.px(16)))
            HtSecondaryButton(label = "Try again", onClick = onRetry)
        }
        is EpisodesState.Loaded -> {
            if (state.seasons.isEmpty()) return
            val season = state.seasons.firstOrNull { it.number == selectedSeason } ?: state.seasons.first()
            // Details open -> episodes painted: ends once, on the first composition of the list.
            SideEffect { HtvPerf.end(HtvPerf.SHOW_EPISODES, "seasons=${state.seasons.size} n=${season.episodes.size}") }
            Column(modifier) {
                SeasonChips(
                    seasons = state.seasons,
                    selected = season.number,
                    onSelect = onSeasonSelected,
                    focus = focus,
                )
                Spacer(Modifier.height(d.px(8)))
                EpisodeList(
                    season = season,
                    marks = marks,
                    onPlay = onPlayEpisode,
                    focus = focus,
                    listState = listState,
                    onEpisodeFocused = onEpisodeFocused,
                    onRowsFocusChanged = onRowsFocusChanged,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeasonChips(
    seasons: List<SeasonItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    focus: EpisodeFocus,
) {
    val d = browseDesign()
    val type = browseType()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // A focused chip is revealed this far clear of the viewport edges: past the ring and the fade.
    val edgePx = with(density) { d.px(CHIP_EDGE_PX).toPx() }
    val fadePx = with(density) { d.px(CHIP_EDGE_PX).toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Offscreen, so the fade below erases pixels of this row only.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // A soft fade where the chips run on, instead of a cut through a glyph.
                if (scroll.value > 0) {
                    drawRect(
                        brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, endX = fadePx),
                        size = Size(fadePx, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
                if (scroll.value < scroll.maxValue) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Black, 1f to Color.Transparent, startX = size.width - fadePx, endX = size.width,
                        ),
                        topLeft = Offset(size.width - fadePx, 0f),
                        size = Size(fadePx, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .horizontalScroll(scroll)
            // Entering the row from above or below lands on the selected chip.
            .focusProperties { enter = { focus.chip(selected) } }
            .focusGroup()
            .padding(horizontal = d.px(RING_PX), vertical = d.px(RING_PX)),
        horizontalArrangement = Arrangement.spacedBy(d.px(16)),
    ) {
        seasons.forEach { season ->
            val isSelected = season.number == selected
            Box(
                modifier = Modifier
                    .height(d.px(CHIP_HEIGHT_PX))
                    .ringSafeBringIntoViewX(edgePx)
                    .focusRequester(focus.chip(season.number))
                    .htvClickableSurface(
                        radiusPx = CHIP_HEIGHT_PX / 2,
                        background = if (isSelected) HappyTvColors.Overlay else HappyTvColors.Surface,
                        onClick = { onSelect(season.number) },
                        // The list follows the focused chip; focus itself stays put.
                        onFocused = { onSelect(season.number) },
                    )
                    .padding(horizontal = d.px(28)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = season.label,
                    style = type.captionBold,
                    color = if (isSelected) HappyTvColors.Accent else HappyTvColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeList(
    season: SeasonItem,
    marks: Map<String, EpisodeMark>,
    onPlay: (EpisodeItem) -> Unit,
    focus: EpisodeFocus,
    listState: LazyListState,
    onEpisodeFocused: (EpisodeItem) -> Unit,
    onRowsFocusChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val d = browseDesign()
    val density = LocalDensity.current
    val ringPx = with(density) { d.px(RING_PX).toPx() }
    val bottomPx = with(density) { d.px(SAFE_BOTTOM_PX + RING_PX).toPx() }
    val thumbSize = remember(density.density) {
        with(density) { IntSize(d.px(THUMB_WIDTH_PX).roundToPx(), d.px(THUMB_HEIGHT_PX).roundToPx()) }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.onFocusChanged { onRowsFocusChanged(it.hasFocus) },
        // Room for the first and last row's ring.
        contentPadding = PaddingValues(horizontal = d.px(RING_PX), vertical = d.px(RING_PX)),
        verticalArrangement = Arrangement.spacedBy(d.px(8)),
    ) {
        items(items = season.episodes, key = { it.id }, contentType = { "episode" }) { episode ->
            EpisodeRow(
                episode = episode,
                mark = marks[episode.id],
                thumbSize = thumbSize,
                onClick = { onPlay(episode) },
                onFocused = { onEpisodeFocused(episode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .ringSafeBringIntoView(ringPx, bottomPx)
                    .focusRequester(focus.row(episode.id)),
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeItem,
    mark: EpisodeMark?,
    thumbSize: IntSize,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier,
) {
    val d = browseDesign()
    val type = browseType()
    Box(
        modifier = modifier
            .height(d.px(ROW_HEIGHT_PX))
            .htvClickableSurface(radiusPx = 16, background = HappyTvColors.Surface, onClick = onClick, onFocused = onFocused),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = d.px(16), vertical = d.px(10)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (episode.thumbnailUrl != null) {
                HtPosterImage(
                    posterUrl = episode.thumbnailUrl,
                    name = episode.title,
                    sizePx = thumbSize,
                    flatPlaceholder = true,
                    modifier = Modifier
                        .size(width = d.px(THUMB_WIDTH_PX), height = d.px(THUMB_HEIGHT_PX))
                        .background(HappyTvColors.Overlay, RoundedCornerShape(d.px(8))),
                )
                Spacer(Modifier.width(d.px(20)))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = episode.heading,
                        style = type.bodyBold,
                        color = HappyTvColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (episode.runtimeLabel != null) {
                        Spacer(Modifier.width(d.px(20)))
                        Text(
                            text = episode.runtimeLabel,
                            style = type.caption,
                            color = HappyTvColors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                    if (mark?.watched == true) {
                        Spacer(Modifier.width(d.px(20)))
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = HappyTvColors.StateSuccess,
                            modifier = Modifier.size(d.px(28)),
                        )
                        Spacer(Modifier.width(d.px(6)))
                        Text(text = "Watched", style = type.caption, color = HappyTvColors.StateSuccess, maxLines = 1)
                    }
                }
                if (episode.plot != null) {
                    Text(
                        text = episode.plot,
                        style = type.compactCaption,
                        color = HappyTvColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        mark?.fraction?.let { fraction ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = d.px(16))
                    .fillMaxWidth()
                    .height(d.px(PROGRESS_HEIGHT_PX))
                    .background(HappyTvColors.Overlay),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(d.px(PROGRESS_HEIGHT_PX))
                        .background(HappyTvColors.Accent),
                )
            }
        }
    }
}

/** Pills and rows at rest while `get_series_info` is in flight. Not focusable. */
@Composable
private fun EpisodeSkeleton(modifier: Modifier) {
    val d = browseDesign()
    Column(modifier.padding(horizontal = d.px(RING_PX))) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.px(16))) {
            repeat(3) {
                Box(
                    Modifier
                        .size(width = d.px(176), height = d.px(CHIP_HEIGHT_PX))
                        .background(HappyTvColors.Loading, RoundedCornerShape(d.px(CHIP_HEIGHT_PX / 2))),
                )
            }
        }
        Spacer(Modifier.height(d.px(20)))
        repeat(3) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(d.px(ROW_HEIGHT_PX))
                    .background(HappyTvColors.Loading, RoundedCornerShape(d.px(16))),
            )
            Spacer(Modifier.height(d.px(12)))
        }
    }
}

internal const val RING_PX = 8
/** How far clear of a scrolling row's edges a focused chip is brought; also the edge fade's width. */
private const val CHIP_EDGE_PX = 56
internal const val CHIP_HEIGHT_PX = 56
internal const val ROW_HEIGHT_PX = 116
internal const val THUMB_WIDTH_PX = 160
internal const val THUMB_HEIGHT_PX = 90
private const val PROGRESS_HEIGHT_PX = 6
