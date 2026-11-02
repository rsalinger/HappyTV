package com.rysal.happytv.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.browse.BODY_X_KEEP_OPEN_PX
import com.rysal.happytv.ui.browse.HEADER_TOP_PX
import com.rysal.happytv.ui.browse.HtBottomFade
import com.rysal.happytv.ui.browse.HtHeader
import com.rysal.happytv.ui.browse.HtMenuItem
import com.rysal.happytv.ui.browse.HtPopupMenu
import com.rysal.happytv.ui.browse.HtPosterImage
import com.rysal.happytv.ui.browse.RAIL_ANIMATION_MILLIS
import com.rysal.happytv.ui.browse.RailEntry
import com.rysal.happytv.ui.browse.RailGeometry
import com.rysal.happytv.ui.browse.HtRailLayer
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.browse.formatCount
import com.rysal.happytv.ui.browse.htvClickableSurface
import com.rysal.happytv.ui.browse.offsetPx
import com.rysal.happytv.ui.browse.railEntryKeyOf
import com.rysal.happytv.ui.browse.railKeyAfterHide
import com.rysal.happytv.ui.browse.scrollRailTo
import com.rysal.happytv.ui.focus.awaitFocus
import com.rysal.happytv.ui.focus.tryRequest
import com.rysal.happytv.ui.search.ringSafeBringIntoView
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.HappyTvIcons

/**
 * Movies and Shows on one screen, shaped like Live TV (spec #72): the compact header, the same
 * fold-out category rail on the left and a compact poster grid on the right. Details stay a
 * separate full screen, reached with OK on a poster.
 *
 * D-pad: OK or Right on a rail row selects it, loads its posters, folds the rail away (200 ms)
 * and focuses the first poster. Left from the grid's first column, or Back from the grid, fans
 * the rail out with focus on the selected row; Back from the rail leaves the screen. "Keep
 * categories open" makes the rail a permanent left pane. Back from details comes back to the
 * grid with focus on the poster that was opened.
 */
@Composable
fun CatalogScreen(
    streamType: StreamType,
    viewModelFactory: ViewModelProvider.Factory = CatalogViewModel.Factory(streamType, withRail = true),
    onBack: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onItemClick: (CatalogItem) -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    val viewModel: CatalogViewModel = viewModel(factory = viewModelFactory)
    val grid by viewModel.grid.collectAsStateWithLifecycle()
    val selected by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val restoring by viewModel.restoringSelection.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()
    val counts by viewModel.categoryCounts.collectAsStateWithLifecycle()
    val totalItems by viewModel.totalItems.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val selectionTotal by viewModel.itemsTotal.collectAsStateWithLifecycle()
    val keepOpen by viewModel.keepCategoriesOpen.collectAsStateWithLifecycle()
    val categoriesLoaded by viewModel.categoriesLoaded.collectAsStateWithLifecycle()
    val continueCount by viewModel.continueCount.collectAsStateWithLifecycle()

    val isMovies = streamType == StreamType.VOD
    val unit = CatalogViewModel.unitFor(streamType)
    val d = browseDesign()

    val items = grid.items
    val gridReady = grid.loaded && grid.categoryId == selected && !restoring
    // A cold start's rows land after the first (empty) answer: keep the skeleton up until the
    // refresh has settled, so "no movies" never flashes (F-60 / F-64).
    val showSkeleton = !gridReady || (items.isEmpty() && !categoriesLoaded)
    val gridEmpty = gridReady && items.isEmpty() && categoriesLoaded

    val entries = remember(categories, counts, totalItems, favorites.size, selected, streamType, continueCount) {
        catalogRailEntries(streamType, categories, counts, totalItems, favorites.size, selected, continueCount)
    }
    val currentEntries by rememberUpdatedState(entries)
    val currentItems by rememberUpdatedState(items)

    // ---- rail state (same machine as Live TV) ---------------------------------------
    var railOpen by remember { mutableStateOf(false) }
    val overlayShown = railOpen && !keepOpen
    var railHasFocus by remember { mutableStateOf(false) }
    var gridHasFocus by remember { mutableStateOf(false) }
    var screenHasFocus by remember { mutableStateOf(false) }
    var pendingSelect by remember { mutableStateOf<String?>(null) }
    var railFocusOverride by remember { mutableStateOf<String?>(null) }
    var railFocusTick by remember { mutableIntStateOf(0) }
    var gridFocusTick by remember { mutableIntStateOf(0) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf<Category?>(null) }
    // Long-press on a Continue watching poster: "Remove from Continue watching".
    var continueMenu by remember { mutableStateOf<CatalogItem?>(null) }
    val continueMenuFocus = remember { FocusRequester() }
    // The poster OK was pressed on; kept across the details screen so Back lands on it.
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    // The poster that carries [gridFocus]: the first one, or the one to come back to.
    var focusItemId by remember { mutableStateOf<String?>(null) }
    val railEntry = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val categoryMenuFocus = remember { FocusRequester() }
    val railListState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val entryKey = railEntryKeyOf(entries, railFocusOverride)

    fun fanOutRail() {
        if (!keepOpen) railOpen = true
        railFocusTick++
    }

    LaunchedEffect(railFocusTick) {
        if (railFocusTick == 0) return@LaunchedEffect
        val rows = currentEntries
        val key = railEntryKeyOf(rows, railFocusOverride)
        railListState.scrollRailTo(
            rows.indexOfFirst { it.key == key }.coerceAtLeast(0),
            keepScroll = railFocusOverride != null,
        )
        if (awaitFocus(landed = { railHasFocus }, request = { railEntry.tryRequest() })) {
            railFocusOverride = null
        }
    }

    // The first poster, or the one a visit to details started from.
    LaunchedEffect(gridFocusTick) {
        if (gridFocusTick == 0) return@LaunchedEffect
        val rows = currentItems
        val restore = openedId?.takeIf { id -> rows.any { it.id == id } }
        val target = restore ?: rows.firstOrNull()?.id
        if (target == null) {
            fanOutRail()
            return@LaunchedEffect
        }
        focusItemId = target
        val index = rows.indexOfFirst { it.id == target }
        val visible = gridState.layoutInfo.visibleItemsInfo.any { it.index == index }
        if (!visible) gridState.scrollToItem(index)
        if (awaitFocus(landed = { gridHasFocus }, request = { gridFocus.tryRequest() })) {
            openedId = null
        } else {
            fanOutRail()
        }
    }

    // Entry: once the screen has its first category's posters, the D-pad starts on the first
    // poster; with nothing to show it starts on the rail.
    var entryFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(gridReady, categoriesLoaded, items.isEmpty()) {
        if (entryFocusDone || !gridReady) return@LaunchedEffect
        if (items.isEmpty() && !categoriesLoaded) return@LaunchedEffect
        entryFocusDone = true
        withFrameNanos { }
        if (currentItems.isNotEmpty()) gridFocusTick++ else fanOutRail()
    }

    // OK / Right on a rail row: when its posters have landed, fold the rail away and move on.
    LaunchedEffect(pendingSelect, selected, gridReady, items.isEmpty()) {
        val target = pendingSelect ?: return@LaunchedEffect
        if (selected != target || !gridReady) return@LaunchedEffect
        pendingSelect = null
        if (currentItems.isEmpty()) return@LaunchedEffect
        if (!keepOpen) railOpen = false
        openedId = null
        gridFocusTick++
    }

    // catalog.open / catalog.category end on the first frame drawn once the posters are in.
    val painted = gridReady && (items.isNotEmpty() || categoriesLoaded)
    LaunchedEffect(selected, painted) {
        if (!painted) return@LaunchedEffect
        withFrameNanos { }
        // The row kind, never a provider category name: "recent" is the indexed Recently added query (#78).
        val kind = when (selected) {
            CatalogViewModel.RECENT_CATEGORY_ID -> "recent"
            CatalogViewModel.FAVORITES_CATEGORY_ID -> "favorites"
            CatalogViewModel.CONTINUE_CATEGORY_ID -> "continue"
            CatalogViewModel.ALL_CATEGORY_ID -> "all"
            else -> "category"
        }
        val detail = "n=${currentItems.size} kind=$kind"
        HtvPerf.end(HtvPerf.CATALOG_OPEN, detail)
        HtvPerf.end(HtvPerf.CATALOG_CATEGORY, detail)
    }

    // Header menu closed by one of its items: nothing else heals the D-pad (#59).
    var moreMenuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(moreMenuOpen) {
        val justClosed = !moreMenuOpen && moreMenuWasOpen
        moreMenuWasOpen = moreMenuOpen
        if (!justClosed) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (screenHasFocus) return@LaunchedEffect
        if (overlayShown || currentItems.isEmpty()) railFocusTick++ else gridFocusTick++
    }

    val menuOpen = moreMenuOpen || categoryMenu != null || continueMenu != null
    val dismissContinueMenu: () -> Unit = {
        continueMenu = null
        gridFocusTick++
    }
    val dismissCategoryMenu: () -> Unit = {
        railFocusOverride = categoryMenu?.id
        categoryMenu = null
        railFocusTick++
    }
    val handleBack: () -> Unit = {
        when {
            continueMenu != null -> dismissContinueMenu()
            categoryMenu != null -> dismissCategoryMenu()
            moreMenuOpen -> moreMenuOpen = false
            gridHasFocus -> fanOutRail()
            else -> onBack()
        }
    }
    BackHandler { handleBack() }

    val railProgress = animateFloatAsState(
        targetValue = if (overlayShown) 1f else 0f,
        animationSpec = tween(RAIL_ANIMATION_MILLIS),
        label = "catalog-rail",
    )
    val scrimProgress = animateFloatAsState(
        targetValue = if (overlayShown && items.isNotEmpty()) 1f else 0f,
        animationSpec = tween(RAIL_ANIMATION_MILLIS),
        label = "catalog-rail-scrim",
    )

    val title = if (isMovies) "Movies" else "Shows"
    val selectionLabel = categoryLabel(selected, isMovies, allCategories)
    val subtitle = if (!gridReady) {
        "Loading $unit"
    } else {
        catalogSubtitle(selected, selectionLabel, selectionTotal, unit)
    }
    val gridX = if (keepOpen) BODY_X_KEEP_OPEN_PX else GRID_X_PX
    val metrics = remember(keepOpen) { catalogGridMetrics(availableWidthPx = GRID_RIGHT_PX - gridX) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvColors.Ground)
            .onFocusChanged { screenHasFocus = it.hasFocus }
            // The Back key is handled here, on the key itself: left to the system dispatcher,
            // Compose consumes the first Back to clear focus (QA on #42).
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back || menuOpen) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyDown) handleBack()
                    true
                }
            },
    ) {
        HtHeader(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            onSearchClick = onSearchClick,
            onMoreClick = { moreMenuOpen = true },
            modifier = Modifier
                .offsetPx(HEADER_X_PX, HEADER_TOP_PX)
                .width(d.px(HEADER_WIDTH_PX))
                // Down from any header control goes to the body on purpose (QA on #51).
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionDown || menuOpen) {
                        false
                    } else {
                        if (event.type == KeyEventType.KeyDown) {
                            if (overlayShown || items.isEmpty()) railFocusTick++ else gridFocusTick++
                        }
                        true
                    }
                },
        )

        // The grid is placed as if the rail were folded away; the rail lays over it.
        Box(
            modifier = Modifier
                .offsetPx(gridX, GRID_VIEWPORT_TOP_PX)
                .size(width = d.px(GRID_RIGHT_PX - gridX), height = d.px(1080 - GRID_VIEWPORT_TOP_PX))
                // The grid keeps its focus until the rail has taken it (Left / Back); once the
                // rail has it, the grid behind takes none.
                .focusProperties { canFocus = !overlayShown || gridHasFocus }
                .onFocusChanged { gridHasFocus = it.hasFocus }
                .then(
                    if (overlayShown && items.isNotEmpty()) Modifier.clearAndSetSemantics { } else Modifier,
                )
                .graphicsLayer { alpha = if (gridEmpty) 1f else 1f - GRID_DIM_UNDER_RAIL * railProgress.value },
        ) {
            when {
                showSkeleton -> PosterSkeletonGrid(metrics)
                gridEmpty -> EmptyGridMessage(
                    selected = selected,
                    isMovies = isMovies,
                    label = selectionLabel,
                    // Clear of the rail pills when the rail lies over the grid's left edge.
                    startPx = if (keepOpen) BREATHING_ROOM_PX else BREATHING_ROOM_PX + (BODY_X_KEEP_OPEN_PX - GRID_X_PX),
                )
                else -> PosterGrid(
                    items = items,
                    metrics = metrics,
                    state = gridState,
                    focusItemId = focusItemId,
                    gridFocus = gridFocus,
                    onOpen = { item ->
                        openedId = item.id
                        onItemClick(item)
                    },
                    onLongPress = { item ->
                        if (selected == CatalogViewModel.CONTINUE_CATEGORY_ID) {
                            openedId = item.id
                            continueMenu = item
                        } else {
                            viewModel.toggleFavorite(item.id)
                        }
                    },
                    onLeftEdge = ::fanOutRail,
                    onLoadMore = viewModel::loadMorePosters,
                )
            }
        }

        HtBottomFade(modifier = Modifier.align(Alignment.BottomCenter))

        HtRailLayer(
            entries = entries,
            geometry = CATALOG_RAIL_GEOMETRY,
            listState = railListState,
            keepOpen = keepOpen,
            overlayShown = overlayShown,
            railProgress = railProgress,
            scrimProgress = scrimProgress,
            entryKey = entryKey,
            entryRequester = railEntry,
            onSelect = { key ->
                pendingSelect = key
                viewModel.selectCategory(key)
            },
            onRowFocused = { },
            onLongPress = { key -> categoryMenu = categories.firstOrNull { it.id == key } },
            onRailFocusChanged = { railHasFocus = it },
        )

        if (moreMenuOpen) {
            HtPopupMenu(
                title = title,
                onDismiss = { moreMenuOpen = false },
                anchorX = MORE_MENU_X_PX,
                anchorY = MORE_MENU_Y_PX,
            ) {
                HtMenuItem(
                    label = "Hide all empty categories",
                    icon = Icons.Filled.Folder,
                    onClick = {
                        categories.filter { (counts[it.id] ?: 0) == 0 }
                            .forEach { viewModel.toggleCategoryHidden(it.id) }
                        moreMenuOpen = false
                    },
                )
                HtMenuItem(
                    label = "Refresh",
                    icon = Icons.Filled.Refresh,
                    onClick = {
                        onRefresh()
                        moreMenuOpen = false
                    },
                )
            }
        }

        continueMenu?.let { item ->
            HtPopupMenu(
                title = item.name,
                onDismiss = dismissContinueMenu,
                anchorX = CONTINUE_MENU_X_PX,
                anchorY = MORE_MENU_Y_PX,
                widthPx = CONTINUE_MENU_WIDTH_PX,
                initialFocus = continueMenuFocus,
            ) {
                HtMenuItem(
                    label = "Remove from Continue watching",
                    icon = Icons.Filled.Close,
                    focusRequester = continueMenuFocus,
                    onClick = {
                        viewModel.removeFromContinue(item.id)
                        continueMenu = null
                        gridFocusTick++
                    },
                )
            }
        }

        categoryMenu?.let { category ->
            HtPopupMenu(
                title = category.name,
                onDismiss = dismissCategoryMenu,
                anchorX = CATEGORY_MENU_X_PX,
                anchorY = CATALOG_BODY_TOP_PX,
                initialFocus = categoryMenuFocus,
            ) {
                HtMenuItem(
                    label = "Hide category",
                    icon = HappyTvIcons.EyeSlashed,
                    focusRequester = categoryMenuFocus,
                    onClick = {
                        // #114: focus lands on the category that takes this one's place.
                        railFocusOverride = railKeyAfterHide(currentEntries, category.id)
                        viewModel.toggleCategoryHidden(category.id)
                        categoryMenu = null
                        fanOutRail()
                    },
                )
                HtMenuItem(
                    label = "About this category",
                    icon = Icons.Filled.Info,
                    caption = formatCount(counts[category.id] ?: 0, unit),
                    onClick = dismissCategoryMenu,
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*  Rail contents                                                             */
/* -------------------------------------------------------------------------- */

/**
 * The rail (spec #72): Favorites, Recently added and All movies / All shows pinned at the top,
 * then the provider's visible categories with their counts, in provider order.
 *
 * "Continue watching" leads them when something is in progress (#78), and is not listed otherwise.
 */
internal fun catalogRailEntries(
    streamType: StreamType,
    categories: List<Category>,
    counts: Map<String, Int>,
    totalItems: Int,
    favoriteCount: Int,
    selected: String,
    continueCount: Int = 0,
): List<RailEntry> = buildList {
    // Continue watching leads, and is only listed when it has something in it (#78).
    if (continueCount > 0) {
        add(
            RailEntry.Row(
                key = CatalogViewModel.CONTINUE_CATEGORY_ID,
                label = "Continue watching",
                count = continueCount,
                icon = Icons.Filled.PlayArrow,
                selected = selected == CatalogViewModel.CONTINUE_CATEGORY_ID,
            ),
        )
    }
    add(
        RailEntry.Row(
            key = CatalogViewModel.FAVORITES_CATEGORY_ID,
            label = "Favorites",
            count = favoriteCount,
            icon = Icons.Outlined.StarBorder,
            selected = selected == CatalogViewModel.FAVORITES_CATEGORY_ID,
        ),
    )
    add(
        RailEntry.Row(
            key = CatalogViewModel.RECENT_CATEGORY_ID,
            label = "Recently added",
            count = 0,
            icon = Icons.Filled.NewReleases,
            selected = selected == CatalogViewModel.RECENT_CATEGORY_ID,
        ),
    )
    add(
        RailEntry.Row(
            key = CatalogViewModel.ALL_CATEGORY_ID,
            label = if (streamType == StreamType.VOD) "All movies" else "All shows",
            count = totalItems,
            icon = null,
            selected = selected == CatalogViewModel.ALL_CATEGORY_ID,
        ),
    )
    add(RailEntry.Divider)
    categories.forEach { category ->
        add(
            RailEntry.Row(
                key = category.id,
                label = category.name,
                count = counts[category.id] ?: 0,
                icon = null,
                selected = selected == category.id,
                hasMenu = true,
            ),
        )
    }
}

private fun categoryLabel(selected: String, isMovies: Boolean, categories: List<Category>): String = when (selected) {
    CatalogViewModel.CONTINUE_CATEGORY_ID -> "Continue watching"
    CatalogViewModel.FAVORITES_CATEGORY_ID -> "Favorites"
    CatalogViewModel.RECENT_CATEGORY_ID -> "Recently added"
    CatalogViewModel.ALL_CATEGORY_ID -> if (isMovies) "All movies" else "All shows"
    CatalogViewModel.PICK_CATEGORY_ID -> ""
    else -> categories.firstOrNull { it.id == selected }?.name ?: if (isMovies) "Movies" else "Shows"
}

/* -------------------------------------------------------------------------- */
/*  Poster grid                                                               */
/* -------------------------------------------------------------------------- */

/**
 * The compact grid's cell geometry in artboard px (spec #72): [COLUMNS] columns across
 * [availableWidthPx], each cell a poster a little under 2:3 (200 x 270 at most), centred in an
 * equal-width column, so three full rows fit above the bottom safe area with room for the ring.
 */
internal data class CatalogGridMetrics(val columnWidthPx: Int, val cardWidthPx: Int, val cardHeightPx: Int)

internal fun catalogGridMetrics(availableWidthPx: Int): CatalogGridMetrics {
    val column = availableWidthPx / COLUMNS
    val width = minOf(MAX_CARD_WIDTH_PX, column - MIN_COLUMN_GAP_PX)
    val height = minOf(MAX_CARD_HEIGHT_PX, (width / CARD_ASPECT).toInt())
    return CatalogGridMetrics(column, width, height)
}

@Composable
private fun PosterGrid(
    items: List<CatalogItem>,
    metrics: CatalogGridMetrics,
    state: LazyGridState,
    focusItemId: String?,
    gridFocus: FocusRequester,
    onOpen: (CatalogItem) -> Unit,
    onLongPress: (CatalogItem) -> Unit,
    onLeftEdge: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val d = browseDesign()
    val density = LocalDensity.current
    val ringMarginPx = with(density) { d.px(RING_MARGIN_PX).toPx() }
    // The caption under the art is part of the poster: it stays above the bottom safe area too.
    val bottomMarginPx = with(density) { d.px(SAFE_BOTTOM_PX + RING_MARGIN_PX + CAPTION_GAP_PX + CAPTION_HEIGHT_PX).toPx() }
    val imageSize = remember(metrics, density.density) {
        with(density) { IntSize(d.px(metrics.cardWidthPx).roundToPx(), d.px(metrics.cardHeightPx).roundToPx()) }
    }
    // Paging (F-55): when the last visible poster closes in on the end of the paged-in window,
    // ask for the next page.
    val size = items.size
    LaunchedEffect(state, size) {
        androidx.compose.runtime.snapshotFlow {
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { last ->
            if (last >= 0 && last >= size - LOAD_MORE_LOOKAHEAD) onLoadMore()
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = state,
        modifier = Modifier.fillMaxSize(),
        // Room above the first row for its ring, and below the last for the safe area.
        contentPadding = PaddingValues(
            top = d.px(RING_MARGIN_PX),
            bottom = d.px(SAFE_BOTTOM_PX + RING_MARGIN_PX),
        ),
        verticalArrangement = Arrangement.spacedBy(d.px(ROW_GAP_PX)),
    ) {
        itemsIndexed(items = items, key = { _, item -> item.id }, contentType = { _, _ -> "poster" }) { index, item ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(d.px(metrics.cardHeightPx + CAPTION_GAP_PX + CAPTION_HEIGHT_PX))
                    // The focus-scroll rectangle includes the ring, the caption and the safe area (#70).
                    .ringSafeBringIntoView(ringMarginPx, bottomMarginPx),
                contentAlignment = Alignment.TopCenter,
            ) {
                PosterCard(
                    item = item,
                    widthPx = metrics.cardWidthPx,
                    heightPx = metrics.cardHeightPx,
                    imageSize = imageSize,
                    focusRequester = if (item.id == focusItemId) gridFocus else null,
                    firstColumn = index % COLUMNS == 0,
                    onLeftEdge = onLeftEdge,
                    onClick = { onOpen(item) },
                    onLongClick = { onLongPress(item) },
                )
            }
        }
    }
}

/**
 * One poster (compact): the art is the focus target; the caption (title over two lines, the year
 * after it) sits below on the screen background. The favorite star is top right, the quality
 * badge and the episode chip top left. Long-press OK toggles the star. The ring is the only focus
 * change: nothing resizes or moves.
 */
@Composable
private fun PosterCard(
    item: CatalogItem,
    widthPx: Int,
    heightPx: Int,
    imageSize: IntSize,
    focusRequester: FocusRequester?,
    firstColumn: Boolean,
    onLeftEdge: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val d = browseDesign()
    val type = browseType()
    var focused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.width(d.px(widthPx))) {
    Box(
        modifier = Modifier
            .size(width = d.px(widthPx), height = d.px(heightPx))
            // The caption is outside the focus target: the title still names it for TalkBack and QA.
            .semantics { contentDescription = item.name }
            // FocusRequester BEFORE the clickable surface (see HtvFocus.kt).
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (firstColumn) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionLeft) {
                            if (event.type == KeyEventType.KeyDown) onLeftEdge()
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                },
            )
            .onFocusChanged { focused = it.hasFocus }
            .htvClickableSurface(
                radiusPx = CARD_RADIUS_PX,
                background = HappyTvColors.Surface,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        HtPosterImage(
            posterUrl = item.posterUrl,
            name = item.name,
            modifier = Modifier.fillMaxSize(),
            sizePx = imageSize,
            flatPlaceholder = true,
        )

        item.progressFraction?.let { fraction ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(d.px(PROGRESS_BAR_HEIGHT_PX))
                    .background(HappyTvColors.Overlay),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(d.px(PROGRESS_BAR_HEIGHT_PX))
                        .background(HappyTvColors.Accent),
                )
            }
        }

        if (item.quality != null || item.episodeLabel != null) {
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(d.px(8)),
                verticalArrangement = Arrangement.spacedBy(d.px(6)),
            ) {
                item.quality?.let { QualityBadge(label = it) }
                item.episodeLabel?.let { label ->
                    Box(
                        modifier = Modifier
                            .height(d.px(32))
                            .background(HappyTvColors.PosterBadge, CircleShape)
                            .padding(horizontal = d.px(12)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = label, style = type.compactCaptionSemibold, color = HappyTvColors.TextPrimary)
                    }
                }
            }
        }

        if (item.isFavorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(d.px(8))
                    .size(d.px(40))
                    .background(HappyTvColors.PosterBadge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "In favorites",
                    tint = HappyTvColors.Accent,
                    modifier = Modifier.size(d.px(24)),
                )
            }
        }
    }
    Spacer(Modifier.height(d.px(CAPTION_GAP_PX)))
    PosterCaption(title = item.name, year = item.year, focused = focused, modifier = Modifier.fillMaxWidth())
    }
}

/** Placeholder cards while the posters load: flat, static, not focusable. */
@Composable
private fun PosterSkeletonGrid(metrics: CatalogGridMetrics) {
    val d = browseDesign()
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = d.px(RING_MARGIN_PX)),
        verticalArrangement = Arrangement.spacedBy(d.px(ROW_GAP_PX)),
    ) {
        items(count = SKELETON_CARDS, key = { "skeleton-$it" }, contentType = { "skeleton" }) {
            Box(
                modifier = Modifier.fillMaxWidth().height(d.px(metrics.cardHeightPx + CAPTION_GAP_PX + CAPTION_HEIGHT_PX)),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = d.px(metrics.cardWidthPx), height = d.px(metrics.cardHeightPx))
                        .background(HappyTvColors.Surface, androidx.compose.foundation.shape.RoundedCornerShape(d.px(CARD_RADIUS_PX))),
                )
            }
        }
    }
}

/**
 * The header line. "Recently added" holds at most [CatalogViewModel.RECENT_CAP] items, so a full
 * list says "newest 480" rather than presenting the cap as a count of everything.
 */
internal fun catalogSubtitle(selected: String, label: String, total: Int, unit: String): String =
    if (selected == CatalogViewModel.RECENT_CATEGORY_ID && total >= CatalogViewModel.RECENT_CAP) {
        "$label · newest ${formatCount(total)}"
    } else {
        "$label · ${formatCount(total, unit)}"
    }

/** The grid has nothing to show: say why, in the grid's place. The rail has the D-pad. */
@Composable
private fun EmptyGridMessage(selected: String, isMovies: Boolean, label: String, startPx: Int) {
    val d = browseDesign()
    val type = browseType()
    val unit = if (isMovies) "movies" else "shows"
    val (heading, body) = when (selected) {
        CatalogViewModel.FAVORITES_CATEGORY_ID ->
            "No favorite $unit yet" to "Hold OK on a poster to add a star."
        CatalogViewModel.CONTINUE_CATEGORY_ID ->
            "Nothing to continue" to "Start a title and it shows up here."
        CatalogViewModel.RECENT_CATEGORY_ID ->
            "Nothing added recently" to "Pick a category to browse $unit."
        CatalogViewModel.ALL_CATEGORY_ID ->
            "No $unit yet" to "Refresh from the more menu to check again."
        else -> "No $unit in $label" to "Pick another category to keep browsing."
    }
    Column(modifier = Modifier.padding(start = d.px(startPx), top = d.px(EMPTY_TOP_PX))) {
        Text(text = heading, style = type.compactHeading, color = HappyTvColors.TextPrimary)
        Spacer(Modifier.height(d.px(8)))
        Text(text = body, style = type.compactBody, color = HappyTvColors.TextSecondary)
    }
}

/* -------------------------------------------------------------------------- */
/*  Geometry (artboard px at 1920 x 1080)                                     */
/* -------------------------------------------------------------------------- */

private const val HEADER_X_PX = 96
private const val HEADER_WIDTH_PX = 1728

/** The body starts here: 24 below the 72 px header that ends at 126. */
internal const val CATALOG_BODY_TOP_PX = 150
/**
 * The rail's container starts at the header's bottom edge (126) so scrolled rows never show
 * through under the header: its first row sits at 166 (container 126 + 40 padding).
 */
private val CATALOG_RAIL_GEOMETRY = RailGeometry(bodyTopPx = 166)

/** The grid's viewport starts 8 above the first row so the first row's ring is not clipped. */
internal const val RING_MARGIN_PX = 8
internal const val GRID_VIEWPORT_TOP_PX = CATALOG_BODY_TOP_PX - RING_MARGIN_PX
private const val GRID_X_PX = 96
private const val GRID_RIGHT_PX = 1824

/** Bottom safe area. */
internal const val SAFE_BOTTOM_PX = 54

internal const val COLUMNS = 7
internal const val ROW_GAP_PX = 24
internal const val MIN_COLUMN_GAP_PX = 24
internal const val MAX_CARD_WIDTH_PX = 200
internal const val MAX_CARD_HEIGHT_PX = 270
private const val CARD_ASPECT = 0.74f
private const val CARD_RADIUS_PX = 12

/** The Continue watching progress bar along a poster's bottom edge. */
internal const val PROGRESS_BAR_HEIGHT_PX = 6
private const val SKELETON_CARDS = 21

/** Ask for the next page 3 poster rows before the window runs out (F-55). */
private const val LOAD_MORE_LOOKAHEAD = 3 * COLUMNS

/** The grid is dimmed by this much while the rail is up. */
private const val GRID_DIM_UNDER_RAIL = 0.5f

private const val BREATHING_ROOM_PX = 40
private const val EMPTY_TOP_PX = 96
private const val MORE_MENU_X_PX = 1224

/** "Remove from Continue watching" in full (#99): wider than the 600 px default, ending at the same right edge (1824). */
internal const val CONTINUE_MENU_WIDTH_PX = 840
private const val CONTINUE_MENU_X_PX = 1824 - CONTINUE_MENU_WIDTH_PX
private const val MORE_MENU_Y_PX = 136
private const val CATEGORY_MENU_X_PX = 520
