package com.rysal.happytv.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.catalog.CAPTION_GAP_PX
import com.rysal.happytv.ui.catalog.CAPTION_HEIGHT_PX
import com.rysal.happytv.ui.catalog.PosterCaption
import com.rysal.happytv.ui.catalog.focusMarquee
import com.rysal.happytv.ui.catalog.QualityBadge
import com.rysal.happytv.ui.browse.HtChannelMark
import com.rysal.happytv.ui.browse.HtPosterImage
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.browse.htvClickableSurface
import com.rysal.happytv.ui.focus.tryRequest
import com.rysal.happytv.ui.settings.HtSegmented
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.tabular
import kotlinx.coroutines.launch

/**
 * Search — design spec §"Screens: search": a **modal overlay**, not a screen.
 * The activity's window is translucent, so whatever opened it is behind a
 * near-opaque scrim (#67: the handoff's 70% black let the dashboard tiles read
 * through the results); the field sits at the top with the scope chips beside
 * it, and the results scroll below, one group per type.
 *
 * Sizes follow the M1 compact density (the #40 header): a 80 px field, 24/28
 * text, poster and card sizes chosen so a row of four cards or eight posters
 * fits the 1728 px content width.
 *
 * Typing and voice both come from the system keyboard (Gboard), so there is
 * deliberately no mic button. v1.1: a mic that fires
 * `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` for panels whose users type on a
 * phone remote; not shipped in v1.
 */
@Composable
fun SearchOverlay(
    onBack: () -> Unit,
    onPlayChannel: (String) -> Unit,
    onOpenDetails: (String, StreamType) -> Unit,
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val d = browseDesign()
    val type = browseType()
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    // #59: the first card of the first visible group. Down from the field (or the
    // scope chips) goes here on purpose: left to Compose's geometric search the
    // Down key never left the text field. Up from that first row comes back.
    val firstResult = remember { FocusRequester() }
    // One requester per scope chip, so Right from the field can land on the selected one.
    val chipFocus = remember { SearchScope.entries.map { FocusRequester() } }
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    BackHandler { onBack() }

    // The field is focused on open and the system keyboard comes with it.
    LaunchedEffect(Unit) {
        fieldFocus.requestFocus()
        keyboard?.show()
    }

    // A new settled query (or scope) starts at the top of the list.
    LaunchedEffect(state.settledQuery, state.scope) { listState.scrollToItem(0) }

    val rows = remember(state) { buildResultRows(state) }

    // "See all" switches the scope, which removes the line that held focus. Once the
    // filtered results are in, focus goes to the first result (or the chip when empty).
    var focusAfterScopeChange by remember { mutableStateOf(false) }
    LaunchedEffect(state.scope, rows) {
        if (focusAfterScopeChange) {
            listState.scrollToItem(0)
            if (!firstResult.tryRequest()) chipFocus[state.scope.ordinal].tryRequest()
            focusAfterScopeChange = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(HappyTvColors.ScrimSearch))

        // Field row: the field takes the width the scope chips leave.
        Row(
            modifier = Modifier
                .offset(x = d.px(FIELD_X_PX), y = d.px(FIELD_Y_PX))
                .width(d.px(CONTENT_WIDTH_PX))
                .height(d.px(FIELD_H_PX))
                // The text field swallows Down (and focusProperties never gets a say), so
                // the row takes it first, on the key itself. Chips share the row, so Down
                // from them lands here too.
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionDown || state.phase != SearchPhase.RESULTS) {
                        false
                    } else {
                        if (event.type == KeyEventType.KeyDown) {
                            coroutineScope.launch {
                                // The list may be scrolled; the entry card only exists at the top.
                                listState.scrollToItem(0)
                                firstResult.tryRequest()
                            }
                        }
                        true
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                text = text,
                placeholder = state.scope.placeholder,
                focusRequester = fieldFocus,
                onValueChange = { value ->
                    text = value
                    viewModel.onQueryChange(value)
                },
                // The text field also swallows Right, which is how the chips went unreachable.
                onRight = { chipFocus[state.scope.ordinal].tryRequest() },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(d.px(SCOPE_GAP_PX)))
            HtSegmented(
                options = SearchScope.entries.map { it.label },
                selectedIndex = state.scope.ordinal,
                onSelect = { index -> viewModel.selectScope(SearchScope.entries[index]) },
                contentDescription = "Search in",
                wellColor = HappyTvColors.Surface,
                segmentHeightPx = SCOPE_SEGMENT_H_PX,
                segmentPaddingPx = SCOPE_SEGMENT_PAD_PX,
                compact = true,
                focusRequesters = chipFocus,
            )
        }

        // Results / states, from just under the field to the bottom edge; the list's
        // bottom padding keeps its last line clear of the fade.
        Box(
            modifier = Modifier
                .offset(x = d.px(FIELD_X_PX), y = d.px(RESULTS_Y_PX))
                .width(d.px(CONTENT_WIDTH_PX))
                // Runs to the window edge so a focused item's ring and glow are never clipped;
                // focus-scroll keeps items out of the bottom safe area (see ringSafeBringIntoView).
                .height(d.px(ARTBOARD_H_PX - RESULTS_Y_PX)),
        ) {
            when (state.phase) {
                SearchPhase.TYPING -> Text(
                    text = "Type to search channels, movies and shows.",
                    style = type.compactBody,
                    color = HappyTvColors.TextSecondary,
                )

                SearchPhase.EMPTY_LIBRARY -> Text(
                    // Same wording the dashboard's signed-out tiles use.
                    text = "Sign in to load channels",
                    style = type.compactBody,
                    color = HappyTvColors.TextTertiary,
                )

                SearchPhase.NO_MATCHES -> Column(verticalArrangement = Arrangement.spacedBy(d.px(8))) {
                    Text(
                        text = "No matches for “${state.settledQuery}”.",
                        style = type.compactHeading,
                        color = HappyTvColors.TextPrimary,
                    )
                    Text(
                        text = "Try fewer words.",
                        style = type.compactBody,
                        color = HappyTvColors.TextSecondary,
                    )
                }

                SearchPhase.RESULTS -> ResultsList(
                    rows = rows,
                    listState = listState,
                    firstResult = firstResult,
                    // #174: Up from the first row lands on the selected scope chip, never on the
                    // field: focusing the text field opens the system keyboard, which then
                    // swallows Down and Right and hides the chips. Left from the chips reaches
                    // the field on purpose.
                    upTarget = chipFocus[state.scope.ordinal],
                    onPlayChannel = onPlayChannel,
                    onOpenDetails = onOpenDetails,
                    onSeeAll = { scope ->
                        focusAfterScopeChange = true
                        viewModel.selectScope(scope)
                    },
                )
            }
        }

        // 120 px fade closing the overlay at the bottom.
        Spacer(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(d.px(FADE_H_PX))
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(HappyTvColors.SearchFadeStart, HappyTvColors.SearchFadeEnd),
                        ),
                    )
                },
        )
    }
}

// -------------------------------------------------------------- result rows

/** One lazily composed line of the results list. */
internal sealed interface ResultRow {
    val key: String

    data class Heading(val title: String, val count: Int, val first: Boolean) : ResultRow {
        override val key get() = "h:$title"
    }

    data class Channels(val index: Int, val cards: List<SearchChannelResult>) : ResultRow {
        override val key get() = "c:$index"
    }

    data class Posters(val type: StreamType, val index: Int, val posters: List<SearchPosterResult>) : ResultRow {
        override val key get() = "p:${type.name}:$index"
    }

    /** Shown when a group is cut short; OK switches the scope to that group. */
    data class SeeAll(val scope: SearchScope, val label: String) : ResultRow {
        override val key get() = "a:${scope.name}"
    }
}

/**
 * Flattens the groups into list lines: heading, card/poster rows, and a "See all"
 * line when the group holds more matches than are drawn. Only the lines on screen
 * are composed, so a long group costs nothing off-screen.
 */
internal fun buildResultRows(state: SearchUiState): List<ResultRow> {
    val rows = mutableListOf<ResultRow>()
    fun heading(title: String, count: Int) {
        rows += ResultRow.Heading(title, count, first = rows.isEmpty())
    }
    if (state.channels.isNotEmpty()) {
        heading("Channels", state.channelCount)
        state.channels.chunked(SearchViewModel.CHANNELS_PER_ROW)
            .forEachIndexed { i, cards -> rows += ResultRow.Channels(i, cards) }
        if (state.channelCount > state.channels.size) {
            rows += ResultRow.SeeAll(SearchScope.CHANNELS, "See all ${state.channelCount} channels")
        }
    }
    if (state.movies.isNotEmpty()) {
        heading("Movies", state.movieCount)
        state.movies.chunked(SearchViewModel.POSTERS_PER_ROW)
            .forEachIndexed { i, posters -> rows += ResultRow.Posters(StreamType.VOD, i, posters) }
        if (state.movieCount > state.movies.size) {
            rows += ResultRow.SeeAll(SearchScope.MOVIES, "See all ${state.movieCount} movies")
        }
    }
    if (state.shows.isNotEmpty()) {
        heading("Shows", state.showCount)
        state.shows.chunked(SearchViewModel.POSTERS_PER_ROW)
            .forEachIndexed { i, posters -> rows += ResultRow.Posters(StreamType.SERIES, i, posters) }
        if (state.showCount > state.shows.size) {
            rows += ResultRow.SeeAll(SearchScope.SHOWS, "See all ${state.showCount} shows")
        }
    }
    return rows
}

/**
 * The scrolling results (#67, #64). A LazyColumn keeps D-pad focus in view as it
 * moves down through every group; the very first card is the Down target from
 * the field and every card of the first row sends Up to the scope chip.
 */
@Composable
private fun ResultsList(
    rows: List<ResultRow>,
    listState: LazyListState,
    firstResult: FocusRequester,
    upTarget: FocusRequester,
    onPlayChannel: (String) -> Unit,
    onOpenDetails: (String, StreamType) -> Unit,
    onSeeAll: (SearchScope) -> Unit,
) {
    val d = browseDesign()
    val type = browseType()
    val firstCardRow = rows.indexOfFirst { it is ResultRow.Channels || it is ResultRow.Posters }
    val density = LocalDensity.current
    val ringMarginPx = with(density) { d.px(RING_MARGIN_PX).toPx() }
    val bottomMarginPx = with(density) { d.px(SAFE_BOTTOM_PX + RING_MARGIN_PX).toPx() }
    val ringSafe = Modifier.ringSafeBringIntoView(ringMarginPx, bottomMarginPx)
    // A poster's caption hangs under the focused art: it has to clear the safe area as well (#93).
    val captionPx = with(density) { d.px(CAPTION_GAP_PX + CAPTION_HEIGHT_PX).toPx() }
    val posterRingSafe = Modifier.ringSafeBringIntoView(ringMarginPx, bottomMarginPx + captionPx)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // The last line ends above the safe area plus its ring when scrolled to the end.
        contentPadding = PaddingValues(bottom = d.px(SAFE_BOTTOM_PX + RING_MARGIN_PX)),
        verticalArrangement = Arrangement.spacedBy(d.px(ROW_ITEMS_GAP_PX)),
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { position, row ->
            val isFirstRow = position == firstCardRow
            Box(modifier = (if (row is ResultRow.Posters) posterRingSafe else ringSafe).padding(vertical = d.px(RING_ROOM_PX))) {
            when (row) {
                is ResultRow.Heading -> Row(
                    modifier = Modifier.padding(top = d.px(if (row.first) 0 else RESULTS_GAP_PX)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = row.title, style = type.compactHeading, color = HappyTvColors.TextPrimary)
                    Spacer(Modifier.width(d.px(12)))
                    Text(text = row.count.toString(), style = type.compactBody, color = HappyTvColors.TextTertiary)
                }

                is ResultRow.Channels -> Row(horizontalArrangement = Arrangement.spacedBy(d.px(CHANNEL_GAP_PX))) {
                    row.cards.forEachIndexed { index, channel ->
                        ChannelCard(
                            channel = channel,
                            onClick = { onPlayChannel(channel.id) },
                            modifier = resultFocus(isFirstRow, isFirstRow && index == 0, firstResult, upTarget),
                        )
                    }
                }

                is ResultRow.Posters -> PosterRow(
                    posters = row.posters,
                    onOpen = { onOpenDetails(it, row.type) },
                    focusFor = { index -> resultFocus(isFirstRow, isFirstRow && index == 0, firstResult, upTarget) },
                )

                is ResultRow.SeeAll -> Box(
                    modifier = Modifier
                        .height(d.px(SEE_ALL_H_PX))
                        .htvClickableSurface(
                            radiusPx = SEE_ALL_H_PX / 2,
                            background = HappyTvColors.Surface,
                            onClick = { onSeeAll(row.scope) },
                        )
                        .padding(horizontal = d.px(SEE_ALL_PAD_PX)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = row.label, style = type.compactCaptionSemibold, color = HappyTvColors.TextPrimary)
                }
            }
            }
        }
    }
}

/**
 * Where focus-scroll parks a focused item (#67). The default puts the focused node
 * flush to the viewport edge, which clips its focus ring and glow (drawn outside
 * its bounds). Every list line asks its parent to reveal a rectangle [topMargin] px
 * taller above and [bottomMargin] px taller below than the focused node, so the
 * ring always stays inside the viewport; [bottomMargin] is the 54 px safe area
 * plus the ring. (Compose 1.6 has no `LocalBringIntoViewSpec`, which would do
 * this for the whole list.)
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.ringSafeBringIntoView(topMargin: Float, bottomMargin: Float): Modifier =
    bringIntoViewResponder(
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect =
                Rect(localRect.left, localRect.top - topMargin, localRect.right, localRect.bottom + bottomMargin)

            override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
        },
    )

/**
 * The sideways twin of [ringSafeBringIntoView] for a horizontally scrolling row (#93): the focused
 * item is revealed [margin] px clear of the viewport's left and right edges, so its ring is never
 * clipped and it never sits under an edge fade.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.ringSafeBringIntoViewX(margin: Float): Modifier =
    bringIntoViewResponder(
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect =
                Rect(localRect.left - margin, localRect.top, localRect.right + margin, localRect.bottom)

            override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
        },
    )

// ---------------------------------------------------------------------- field

/**
 * The compact pill: Overlay, text 32 600, a 36 px search icon, focused on
 * open. OK on it opens the system keyboard (the field is editable and
 * focused, so the platform does this for us). Right hands focus to the scope
 * chips via [onRight].
 */
@Composable
private fun SearchField(
    text: String,
    placeholder: String,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onRight: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    val type = browseType()
    val fieldText = type.compactHeading.copy(fontWeight = FontWeight.W600)
    Box(
        modifier = modifier
            .height(d.px(FIELD_H_PX))
            .background(HappyTvColors.Overlay, RoundedCornerShape(d.px(FIELD_H_PX / 2))),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The icon sits in the left padding; text and placeholder start after it and
        // stop short of the right edge, so they never overlap it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = d.px(FIELD_TEXT_PAD_PX), end = d.px(FIELD_END_PAD_PX)),
            contentAlignment = Alignment.CenterStart,
        ) {
        if (text.isEmpty()) {
            Text(
                text = placeholder,
                style = fieldText,
                color = HappyTvColors.TextPlaceholder,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = text,
            onValueChange = onValueChange,
            textStyle = fieldText.copy(color = HappyTvColors.TextPrimary).tabular(),
            cursorBrush = SolidColor(HappyTvColors.Accent),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionRight) {
                        false
                    } else {
                        if (event.type == KeyEventType.KeyDown) onRight()
                        true
                    }
                },
        )
        }
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = HappyTvColors.TextSecondary,
            modifier = Modifier
                .offset(x = d.px(SEARCH_ICON_X_PX))
                .size(d.px(SEARCH_ICON_PX)),
        )
    }
}

// --------------------------------------------------------------------- cards

/**
 * Focus wiring for a result card (#59). The very first card of the first group
 * is where Down from the field lands ([entry]); every card of that group's first
 * row sends Up to [up] (the scope chip, #174; the field would reopen the keyboard). Goes first in the chain, ahead of the
 * clickable it targets.
 */
private fun resultFocus(firstRow: Boolean, isFirst: Boolean, entry: FocusRequester, up: FocusRequester): Modifier =
    Modifier
        .then(if (isFirst) Modifier.focusRequester(entry) else Modifier)
        .then(if (firstRow) Modifier.focusProperties { this.up = up } else Modifier)

/** Compact card: monogram, name over "Channel 101". OK plays the channel. */
@Composable
private fun ChannelCard(
    channel: SearchChannelResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = browseDesign()
    val type = browseType()
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .size(width = d.px(CHANNEL_CARD_W_PX), height = d.px(CHANNEL_CARD_H_PX))
            .onFocusChanged { focused = it.hasFocus }
            .htvClickableSurface(
                radiusPx = 20,
                background = HappyTvColors.Surface,
                onClick = onClick,
            )
            .padding(start = d.px(16), end = d.px(20)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HtChannelMark(
            name = channel.name,
            logoUrl = channel.logoUrl,
            widthPx = CHANNEL_MONOGRAM_W_PX,
            heightPx = CHANNEL_MONOGRAM_H_PX,
            radiusPx = 10,
        )
        Spacer(Modifier.width(d.px(16)))
        // Two lines for the name; the number takes the right edge on its own, so names that share
        // their first 18 characters still tell apart.
        Text(
            text = channel.name,
            style = type.compactRowName,
            color = HappyTvColors.TextPrimary,
            maxLines = 1,
            // Resting: an ellipsis. Focused: the whole name scrolls past (the end tells channels apart).
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).focusMarquee(focused),
        )
        channelNumberText(channel.numberLabel)?.let { number ->
            Spacer(Modifier.width(d.px(12)))
            Text(
                text = number,
                style = type.compactCaption.tabular(),
                color = HappyTvColors.TextTertiary,
                maxLines = 1,
            )
        }
    }
}

/** "Channel 101" shortens to "101"; a channel without a number shows none. */
internal fun channelNumberText(numberLabel: String): String? =
    numberLabel.removePrefix("Channel").trim().takeIf { it.isNotEmpty() }

/** Up to eight posters; the caption (title over two lines, year after it) under the art. OK opens details. */
@Composable
private fun PosterRow(
    posters: List<SearchPosterResult>,
    onOpen: (String) -> Unit,
    focusFor: (Int) -> Modifier,
) {
    val d = browseDesign()
    Row(
        // Room under the captions so the next group's ring never touches them.
        modifier = Modifier.padding(bottom = d.px(POSTER_ROW_BOTTOM_GAP_PX)),
        horizontalArrangement = Arrangement.spacedBy(d.px(POSTER_GAP_PX)),
    ) {
        posters.forEachIndexed { index, poster ->
            Column(
                modifier = Modifier.width(d.px(POSTER_W_PX)),
                verticalArrangement = Arrangement.spacedBy(d.px(CAPTION_GAP_PX)),
            ) {
                var focused by remember { mutableStateOf(false) }
                Box {
                    HtPosterImage(
                        posterUrl = poster.posterUrl,
                        name = poster.name,
                        modifier = focusFor(index)
                            .size(width = d.px(POSTER_W_PX), height = d.px(POSTER_H_PX))
                            .semantics { contentDescription = poster.name }
                            .onFocusChanged { focused = it.hasFocus }
                            .htvClickableSurface(
                                radiusPx = 16,
                                background = HappyTvColors.Surface,
                                onClick = { onOpen(poster.id) },
                            ),
                    )
                    poster.quality?.let { QualityBadge(label = it, modifier = Modifier.padding(d.px(8))) }
                }
                PosterCaption(title = poster.name, year = poster.year, focused = focused)
            }
        }
    }
}

// ------------------------------------------------------------------ geometry

private const val ARTBOARD_H_PX = 1080
private const val CONTENT_WIDTH_PX = 1728
private const val FIELD_X_PX = 96
/** Top safe-area edge (54), same as the compact inner-screen header. */
private const val FIELD_Y_PX = 54
private const val FIELD_H_PX = 80
private const val FIELD_TEXT_PAD_PX = 80
private const val FIELD_END_PAD_PX = 32
private const val SEARCH_ICON_X_PX = 24
private const val SEARCH_ICON_PX = 36
private const val SCOPE_GAP_PX = 24
private const val SCOPE_SEGMENT_H_PX = 56
private const val SCOPE_SEGMENT_PAD_PX = 28
private const val RESULTS_Y_PX = FIELD_Y_PX + FIELD_H_PX + 40
private const val RESULTS_GAP_PX = 32
private const val ROW_ITEMS_GAP_PX = 0
private const val CHANNEL_GAP_PX = 24
/** (1728 − 3 × 24) / 4: four cards fill the content width. */
private const val CHANNEL_CARD_W_PX = 414
private const val CHANNEL_CARD_H_PX = 88
private const val CHANNEL_MONOGRAM_W_PX = 96
private const val CHANNEL_MONOGRAM_H_PX = 60
/** 8 × 180 + 7 × 41 = 1727: eight posters fill the content width. */
private const val POSTER_GAP_PX = 41
private const val POSTER_ROW_BOTTOM_GAP_PX = 16
private const val POSTER_W_PX = 180
private const val POSTER_H_PX = 270
private const val SEE_ALL_H_PX = 64
private const val SEE_ALL_PAD_PX = 32
/** Ring (6 px outer stroke) plus glow (14 px): the most a focused item draws outside its bounds, rounded up. */
private const val RING_MARGIN_PX = 24
/** The 54 px bottom safe area; also the height of the closing fade. */
private const val SAFE_BOTTOM_PX = 54
private const val FADE_H_PX = SAFE_BOTTOM_PX
private const val RING_ROOM_PX = 4
