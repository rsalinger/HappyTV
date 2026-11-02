package com.rysal.happytv.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rysal.happytv.ui.focus.awaitFocus
import com.rysal.happytv.ui.focus.requestWhenAttached
import com.rysal.happytv.ui.focus.tryRequest
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.browse.GenreTag
import com.rysal.happytv.ui.browse.HEADER_TOP_PX
import com.rysal.happytv.ui.browse.HtHeader
import com.rysal.happytv.ui.browse.HtMonogram
import com.rysal.happytv.ui.browse.HtPosterArt
import com.rysal.happytv.ui.browse.HtPosterImage
import com.rysal.happytv.ui.browse.HtPrimaryButton
import com.rysal.happytv.ui.browse.HtSecondaryButton
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.browse.offsetPx
import com.rysal.happytv.ui.theme.HappyTvColors
import androidx.compose.material.icons.automirrored.filled.ArrowBack

/**
 * Movie and show details (design spec "Screens: movies and shows > Details").
 * Both catalogs use this composable; [CatalogDetail.isSeries] picks the copy.
 *
 * Layout, in handoff px: backdrop art in the top right (from x = 760, 1160 x
 * 780, 40% opacity, fading into Ground toward the left and the bottom); the
 * 400 x 600 poster at (96, 190) with the handoff's shadow; the text column from
 * x = 560, 1264 wide, starting at the same top 190. Focus starts on Play, or on
 * the favorites button when Play is disabled.
 */
@Composable
fun DetailsScreen(
    streamType: StreamType,
    streamId: String,
    viewModelFactory: ViewModelProvider.Factory = CatalogViewModel.Factory(
        streamType = streamType,
        streamId = streamId,
    ),
    onBack: () -> Unit = {},
    /** The resume point to start from, or null to start at the beginning. */
    onPlay: (CatalogDetail, ResumePoint?) -> Unit = { _, _ -> },
) {
    val viewModel: CatalogViewModel = viewModel(factory = viewModelFactory)
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val marks by viewModel.episodeMarks.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    val current = detail ?: return
    val d = browseDesign()

    val playButton = rememberFocusRequester()
    val favoriteButton = rememberFocusRequester()

    // Episode area (#74): the chip that is selected, and the focus plumbing to come back to the
    // episode that was playing.
    var pickedSeason by rememberSaveable { mutableStateOf<Int?>(null) }
    val seasons = (episodes as? EpisodesState.Loaded)?.seasons.orEmpty()
    val shownSeason = pickedSeason?.takeIf { n -> seasons.any { it.number == n } }
        ?: initialSeason(seasons, current.resume?.episodeId)
    val episodeFocus = remember { EpisodeFocus() }
    val listState = rememberLazyListState()
    var playedEpisodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var resumeAtLaunch by rememberSaveable { mutableStateOf<String?>(null) }
    var playbackLaunched by rememberSaveable { mutableStateOf(false) }
    var resumedTick by rememberSaveable { mutableIntStateOf(0) }
    var listHasFocus by remember { mutableStateOf(false) }
    var actionsHaveFocus by remember { mutableStateOf(false) }
    // #93: the plot opens in full on OK when it is cut short; the episode row in focus lends its
    // description to the area under the poster.
    var plotOpen by rememberSaveable { mutableStateOf(false) }
    val plotFocus = remember { FocusRequester() }
    var focusedEpisode by remember { mutableStateOf<EpisodeItem?>(null) }
    // Rows only: the season chips share the area with the rows but do not keep the note.
    var rowsHaveFocus by remember { mutableStateOf(false) }
    var plotClosedTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(plotClosedTick) {
        if (plotClosedTick > 0) plotFocus.requestWhenAttached()
    }
    val launchPlayback: (CatalogDetail, ResumePoint?, String?) -> Unit = { detail, point, episodeId ->
        playedEpisodeId = episodeId
        resumeAtLaunch = current.resume?.episodeId
        playbackLaunched = true
        onPlay(detail, point)
    }
    // Back from the player: the page resumes with focus on Back. Put it on the episode that was
    // playing (when its season is the one shown), else on the primary action.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (playbackLaunched) {
            playbackLaunched = false
            resumedTick += 1
        }
    }
    val currentSeasons by rememberUpdatedState(seasons)
    val currentShownSeason by rememberUpdatedState(shownSeason)
    val primaryTarget = if (current.isSeries && !current.hasEpisodes) favoriteButton else playButton
    // The episode Continue watching points at now: after Next episode that is the one the viewer
    // left on, not the one playback started on (#99). Null until the saved row is read.
    val latestResumeEpisodeId by rememberUpdatedState(current.resume?.episodeId)
    LaunchedEffect(resumedTick) {
        if (resumedTick == 0) return@LaunchedEffect
        val started = playedEpisodeId
        val before = resumeAtLaunch
        suspend fun attempt(): String? {
            val episodeId = episodeToFocus(started, before, latestResumeEpisodeId)
            val index = currentSeasons.firstOrNull { it.number == currentShownSeason }
                ?.episodes?.indexOfFirst { it.id == episodeId } ?: -1
            if (episodeId != null && index >= 0) {
                listState.scrollToItem(index)
                awaitFocus(landed = { listHasFocus }, request = { episodeFocus.row(episodeId).tryRequest() })
            } else {
                awaitFocus(landed = { actionsHaveFocus }, request = { primaryTarget.tryRequest() })
            }
            return episodeId
        }
        val first = attempt()
        // The window can hand focus back to Back a moment after resume: look once more. The saved
        // row can also land just after resume, naming a later episode than the one first chosen;
        // move only if focus is still where this effect put it.
        delay(FOCUS_RECHECK_MS)
        if (!listHasFocus && !actionsHaveFocus) attempt()
        else if (first != null && focusedEpisode?.id == first && episodeToFocus(started, before, latestResumeEpisodeId) != first) attempt()
    }
    // Play first when it is enabled; the favorites button otherwise, per the
    // handoff's "Show details · no episodes yet".
    LaunchedEffect(current.id, current.hasEpisodes) {
        // Retries until the button is attached and placed (HtvFocus), not a one-shot request.
        (if (current.isSeries && !current.hasEpisodes) favoriteButton else playButton).requestWhenAttached()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HappyTvColors.Ground),
    ) {
        Backdrop(name = current.name, posterUrl = current.backdropUrl ?: current.posterUrl)

        // A long title must not run over the backdrop's subject: Ground, strongest behind the
        // text column's left edge, fading out toward the right.
        Box(
            modifier = Modifier
                .offsetPx(TEXT_SCRIM_X_PX, 0)
                .size(width = d.px(TEXT_SCRIM_WIDTH_PX), height = d.px(1080))
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.08f to HappyTvColors.Ground.copy(alpha = 0.96f),
                        0.70f to HappyTvColors.Ground.copy(alpha = 0.90f),
                        1f to Color.Transparent,
                    ),
                ),
        )

        // The clock sits over the backdrop's bright corner: a Ground scrim under the header.
        Box(
            modifier = Modifier
                .size(width = d.px(1920), height = d.px(HEADER_SCRIM_HEIGHT_PX))
                .background(
                    // Dark mode keeps its gradient. In light mode the clock is dark text, so
                    // the wash stays near-solid under the whole header row before it fades.
                    if (HappyTvColors.isLight) {
                        Brush.verticalGradient(
                            0f to HappyTvColors.Ground.copy(alpha = 0.94f),
                            0.75f to HappyTvColors.Ground.copy(alpha = 0.90f),
                            1f to Color.Transparent,
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to HappyTvColors.Ground.copy(alpha = 0.85f),
                            1f to Color.Transparent,
                        )
                    },
                ),
        )

        // Compact header (spec #7 section 2): back at the left, clock at the
        // right, no title.
        HtHeader(
            title = "",
            subtitle = "",
            onBack = onBack,
            modifier = Modifier.offsetPx(HEADER_X_PX, HEADER_Y_PX).width(d.px(HEADER_WIDTH_PX)),
        )

        HtPosterImage(
            posterUrl = current.posterUrl,
            name = current.name,
            modifier = Modifier
                .offsetPx(POSTER_X_PX, POSTER_Y_PX)
                .testTag("details-poster")
                .size(width = d.px(POSTER_WIDTH_PX), height = d.px(POSTER_HEIGHT_PX))
                .shadow(
                    elevation = d.px(32),
                    shape = RoundedCornerShape(d.px(24)),
                    clip = false,
                    ambientColor = HappyTvColors.shadow(0.50f),
                    spotColor = HappyTvColors.shadow(0.50f),
                ),
        )

        // A show with episodes: its genres and description sit under the poster, in the column the
        // episode area leaves free (x 96-496, from the poster's bottom plus a gap to the safe edge).
        if (current.isSeries && current.hasEpisodes) {
            Column(
                modifier = Modifier
                    .offsetPx(POSTER_X_PX, POSTER_Y_PX + POSTER_HEIGHT_PX + UNDER_POSTER_GAP_PX)
                    .size(
                        width = d.px(POSTER_WIDTH_PX),
                        height = d.px(1080 - 54 - (POSTER_Y_PX + POSTER_HEIGHT_PX + UNDER_POSTER_GAP_PX)),
                    )
                    .testTag("details-under-poster"),
            ) {
                val episodeInFocus = focusedEpisode?.takeIf { rowsHaveFocus }
                if (current.genres.isNotEmpty() && episodeInFocus == null) {
                    FittingChipRow(spacing = d.px(12)) {
                        current.genres.take(4).forEach { GenreTag(it) }
                    }
                    Spacer(Modifier.height(d.px(20)))
                }
                if (episodeInFocus != null) {
                    // Only the episode's own words while focus is in the list; the show's plot returns after.
                    EpisodeNote(episode = episodeInFocus, modifier = Modifier.testTag("details-episode-note"))
                } else current.plot?.let { plot ->
                    PlotText(
                        text = plot,
                        style = browseType().caption,
                        color = HappyTvColors.TextSecondary,
                        // Lines that fit under the chips (48 + 20) in the 252 px left of the column.
                        maxLines = if (current.genres.isNotEmpty()) 4 else 6,
                        focusRequester = plotFocus,
                        onOpen = { plotOpen = true },
                        modifier = Modifier.testTag("details-plot"),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .offsetPx(TEXT_X_PX, POSTER_Y_PX)
                .width(d.px(TEXT_WIDTH_PX))
                // Bounded, so the cast block can tell whether it fits above the bottom safe area.
                .height(d.px(1080 - SAFE_BOTTOM_PX - POSTER_Y_PX)),
        ) {
            DetailsTitle(
                text = current.name,
                // A show with episodes gives the room to them: the next size down.
                base = if (current.isSeries && current.hasEpisodes) browseType().title else browseType().display,
                // To the right safe edge, not just the 720 px column.
                modifier = Modifier.fitContentWidth().width(d.px(TITLE_WIDTH_PX)),
            )
            Spacer(Modifier.height(d.px(12)))
            val detailsLine = buildList {
                current.year?.let { add(it) }
                current.runtimeLabel?.let { add(it) }
                current.ageLabel?.let { add(it) }
                current.ratingLabel?.let { add(it) }
            }.joinToString(" · ")
            if (detailsLine.isNotEmpty()) {
                Text(
                    text = detailsLine,
                    style = browseType().body,
                    color = HappyTvColors.TextSecondary,
                )
            }
            val withEpisodes = current.isSeries && current.hasEpisodes
            // A show with episodes shows its genres and plot under the poster instead.
            if (current.genres.isNotEmpty() && !withEpisodes) {
                Spacer(Modifier.height(d.px(20)))
                FittingChipRow(spacing = d.px(12)) {
                    current.genres.take(4).forEach { GenreTag(it) }
                }
            }
            if (!withEpisodes) {
                Spacer(Modifier.height(d.px(24 - PLOT_PAD_Y_PX)))
                PlotText(
                    text = current.plot ?: "Plot not available.",
                    style = browseType().body.copy(lineHeight = browseType().body.lineHeight * 48f / 44f),
                    color = if (current.plot == null) HappyTvColors.TextTertiary else HappyTvColors.TextSecondary,
                    maxLines = 3,
                    // Nothing to open when there is no plot.
                    focusRequester = plotFocus,
                    onOpen = { if (current.plot != null) plotOpen = true },
                    modifier = Modifier.width(d.px(PLOT_WIDTH_PX + 2 * PLOT_PAD_X_PX)),
                    bleedPx = PLOT_PAD_X_PX,
                )
            }
            Spacer(Modifier.height(d.px(if (withEpisodes) 24 else 36 - PLOT_PAD_Y_PX)))
            Box(Modifier.onFocusChanged { actionsHaveFocus = it.hasFocus }) {
                DetailActions(
                    detail = current,
                    onPlay = { detail, point -> launchPlayback(detail, point, null) },
                    onToggleFavorite = { viewModel.toggleFavorite(current.id) },
                    playButton = playButton,
                    favoriteButton = favoriteButton,
                )
            }
            showNote(current)?.let { note ->
                Spacer(Modifier.height(d.px(16)))
                Text(text = note, style = browseType().caption, color = HappyTvColors.TextTertiary)
            }
            if (current.isSeries && current.hasEpisodes) {
                // Season chips and the episode list (#74), wider than the text column: they run to
                // the right safe edge, over the backdrop's faded left.
                Spacer(Modifier.height(d.px(16)))
                EpisodeArea(
                    state = episodes,
                    marks = marks,
                    selectedSeason = shownSeason,
                    onSeasonSelected = { pickedSeason = it },
                    onPlayEpisode = { episode ->
                        pickedSeason = episode.season
                        launchPlayback(current, episodePlayPoint(episode, marks[episode.id]), episode.id)
                    },
                    onRetry = {
                        viewModel.retryEpisodes()
                        // The button goes away with the error; keep focus on the page.
                        playButton.tryRequest()
                    },
                    focus = episodeFocus,
                    listState = listState,
                    onEpisodeFocused = { focusedEpisode = it },
                    onRowsFocusChanged = { rowsHaveFocus = it },
                    modifier = Modifier
                        .weight(1f)
                        // Start-aligned at the text column (x = 560), beside the poster: a bare
                        // required width would centre on the 720 px column and run under it.
                        .fitContentWidth()
                        .width(d.px(EPISODE_AREA_WIDTH_PX))
                        .onFocusChanged { listHasFocus = it.hasFocus },
                )
            }
            // Inside the 54 px bottom safe area, or not at all. Shows give the room to episodes.
            if (!(current.isSeries && current.hasEpisodes)) DropIfItDoesNotFit {
                if (current.cast.isNotEmpty()) {
                    Spacer(Modifier.height(d.px(48)))
                    Text(
                        text = "Cast",
                        style = browseType().captionBold,
                        color = HappyTvColors.TextTertiary,
                    )
                    Spacer(Modifier.height(d.px(16)))
                    // Each name at its natural width, out to the right safe edge; a name that does not fit
                    // whole is left out, never cut (#93).
                    FittingChipRow(
                        spacing = d.px(16),
                        modifier = Modifier.fitContentWidth().width(d.px(TITLE_WIDTH_PX)),
                    ) {
                        current.cast.take(CAST_MAX).forEach { CastAvatar(name = it) }
                    }
                } else if (!current.isSeries) {
                    Spacer(Modifier.height(d.px(48)))
                    Text(
                        text = "Cast not available.",
                        style = browseType().caption,
                        color = HappyTvColors.TextTertiary,
                    )
                }
            }
        }

        if (plotOpen && current.plot != null) {
            BackHandler {
                plotOpen = false
                plotClosedTick += 1
            }
            PlotDialog(
                title = current.name,
                plot = current.plot,
                onDismiss = {
                    plotOpen = false
                    plotClosedTick += 1
                },
            )
        }
    }
}

/**
 * The action row: Play (or Resume + Start over, or Play S2 E4) and the favorite button.
 *
 * With Resume and Start over there are three buttons and the favorite keeps its full label: the
 * row starts at the text column (x = 560) and ends around x = 1170 with the longest resume label
 * ("Resume from 1:05:09"), well inside the right safe edge (1824); `DetailActionsTest` pins it.
 */
@Composable
internal fun DetailActions(
    detail: CatalogDetail,
    onPlay: (CatalogDetail, ResumePoint?) -> Unit,
    onToggleFavorite: () -> Unit,
    playButton: FocusRequester,
    favoriteButton: FocusRequester,
) {
    val d = browseDesign()
    Row(
        modifier = Modifier.fitContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(d.px(24)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val playEnabled = !detail.isSeries || detail.hasEpisodes
        val resume = detail.resume
        // One primary button in one slot, whatever its label: when progress arrives after the
        // page opened (or after the player closed) a branch swap would dispose the node that
        // holds focus and leave nothing focused (#74).
        HtPrimaryButton(
            label = resume?.buttonLabel ?: if (detail.isSeries) "Play first episode" else "Play",
            icon = Icons.Filled.PlayArrow,
            onClick = { onPlay(detail, resume) },
            enabled = playEnabled,
            focusRequester = playButton,
        )
        if (resume != null && !resume.isUpNext) {
            HtSecondaryButton(
                label = "Start over",
                icon = Icons.Filled.Replay,
                onClick = { onPlay(detail, resume.asStartOver()) },
            )
        }
        HtSecondaryButton(
            label = if (detail.isFavorite) "In favorites" else "Add to favorites",
            icon = if (detail.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            iconTint = if (detail.isFavorite) HappyTvColors.Accent else HappyTvColors.TextPrimary,
            onClick = onToggleFavorite,
            focusRequester = favoriteButton,
        )
    }
}

/**
 * Lets a row be as wide as its content even when the text column is narrower, so a button label
 * is never clipped ("Add to" for "Add to favorites"). The wide screen has room to the right.
 */
internal fun Modifier.fitContentWidth(): Modifier =
    wrapContentWidth(align = Alignment.Start, unbounded = true)

/** Chips side by side; a chip that does not fit whole is left out, never cut. */
@Composable
internal fun FittingChipRow(spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        val placeables = ArrayList<androidx.compose.ui.layout.Placeable>()
        var used = 0
        for (m in measurables) {
            val p = m.measure(loose)
            val next = if (placeables.isEmpty()) p.width else used + gap + p.width
            if (next > constraints.maxWidth) break
            placeables += p
            used = next
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(used, height) {
            var x = 0
            placeables.forEach { p ->
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

/**
 * A column of content that is shown only when it fits in the height the parent still has left;
 * otherwise it takes no space and draws nothing.
 */
@Composable
internal fun DropIfItDoesNotFit(content: @Composable ColumnScope.() -> Unit) {
    Layout(content = { Column(content = content) }) { measurables, constraints ->
        val placeable = measurables.firstOrNull()
            ?.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        if (placeable == null || !constraints.hasBoundedHeight || placeable.height > constraints.maxHeight) {
            layout(0, 0) {}
        } else {
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }
}

/** The note line under the actions, when the page needs one. */
internal fun showNote(detail: CatalogDetail): String? = when {
    detail.isSeries && !detail.hasEpisodes -> "No episodes yet. Check back later."
    else -> null
}

/* -------------------------------------------------------------------------- */
/*  Pieces                                                                    */
/* -------------------------------------------------------------------------- */

/** Poster art in the top right, faded into Ground on the left and the bottom. */
@Composable
private fun Backdrop(name: String, posterUrl: String? = null) {
    val d = browseDesign()
    Box(
        modifier = Modifier
            .offsetPx(BACKDROP_X_PX, 0)
            .size(width = d.px(BACKDROP_WIDTH_PX), height = d.px(BACKDROP_HEIGHT_PX)),
    ) {
        HtPosterImage(
            posterUrl = posterUrl,
            name = name,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    // Toward the left: Ground at the edge, transparent by 55%.
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to HappyTvColors.Ground,
                            0.55f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    )
                    // Toward the bottom: Ground at the edge, transparent by 60% up.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.40f to Color.Transparent,
                            1f to HappyTvColors.Ground,
                        ),
                    )
                },
        )
    }
}

/** 96 px circle, Overlay, initials over the name — design spec "Cast avatar". */
@Composable
private fun CastAvatar(name: String) {
    val d = browseDesign()
    val type = browseType()
    Column(
        // Its natural width: the circle, or the whole name when that is wider.
        modifier = Modifier.widthIn(min = d.px(96)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(d.px(96))
                .background(HappyTvColors.Overlay, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = monogram(name),
                style = type.bodyBold,
                color = HappyTvColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(d.px(12)))
        Text(
            text = name,
            style = type.caption,
            color = HappyTvColors.TextSecondary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun monogram(name: String): String {
    val words = name.split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase(java.util.Locale.US)
        else -> "${words[0].first()}${words[1].first()}".uppercase(java.util.Locale.US)
    }
}

@Composable
private fun rememberFocusRequester(): FocusRequester = androidx.compose.runtime.remember { FocusRequester() }

/* -------------------------------------------------------------------------- */
/*  Geometry (design spec, px at 1920 x 1080)                                  */
/* -------------------------------------------------------------------------- */

private const val HEADER_X_PX = 96
private const val HEADER_Y_PX = HEADER_TOP_PX
private const val HEADER_WIDTH_PX = 1728

private const val TEXT_SCRIM_X_PX = 480
private const val TEXT_SCRIM_WIDTH_PX = 1344
private const val HEADER_SCRIM_HEIGHT_PX = 180

private const val BACKDROP_X_PX = 760
private const val BACKDROP_WIDTH_PX = 1160
private const val BACKDROP_HEIGHT_PX = 780

private const val POSTER_X_PX = 96
private const val POSTER_Y_PX = 150
private const val POSTER_WIDTH_PX = 400
private const val UNDER_POSTER_GAP_PX = 24
private const val POSTER_HEIGHT_PX = 600

private const val TEXT_X_PX = 560
/** The text column stays in the left of the screen: it never reaches the backdrop's right two-thirds. */
private const val TEXT_WIDTH_PX = 720
private const val PLOT_WIDTH_PX = 720
/** From the text column (x = 560) to the right safe edge (1824). */
private const val TITLE_WIDTH_PX = 1264
private const val CAST_MAX = 8
/** Season chips and episode rows: from the text column (x = 560) to the right safe edge (1824), less a ring each side. */
private const val EPISODE_AREA_WIDTH_PX = 1264
private const val FOCUS_RECHECK_MS = 400L

/**
 * Which episode row gets focus on coming back from the player (#99): the one Continue watching
 * points at now when playing moved it (Next episode saves a later episode), else the one that
 * was started (a glance under a minute saves nothing, and the resume point stays put). Null for
 * the Play button, which leaves focus on the primary action.
 */
internal fun episodeToFocus(startedEpisodeId: String?, resumeAtLaunch: String?, latestResumeEpisodeId: String?): String? =
    when {
        startedEpisodeId == null -> null
        latestResumeEpisodeId != null && latestResumeEpisodeId != resumeAtLaunch -> latestResumeEpisodeId
        else -> startedEpisodeId
    }

/**
 * What pressing OK on an episode row plays (#99). A watched episode starts at 0:00 but keeps its
 * watched row until a real save replaces it; only the explicit Start over action sets `startOver`.
 */
internal fun episodePlayPoint(episode: EpisodeItem, mark: EpisodeMark?): ResumePoint = ResumePoint(
    positionMillis = if (mark?.watched == true) 0L else mark?.positionMillis ?: 0L,
    episodeId = episode.id,
    label = episode.label,
)
