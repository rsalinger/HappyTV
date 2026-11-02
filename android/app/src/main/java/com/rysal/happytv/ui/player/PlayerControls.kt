package com.rysal.happytv.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.rysal.happytv.ui.browse.HtChannelLogo
import com.rysal.happytv.ui.theme.HappyTvDarkColors
import com.rysal.happytv.ui.theme.HappyTvIcons
import com.rysal.happytv.ui.theme.happyTvFocusRing
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography
import com.rysal.happytv.ui.theme.tabular
import kotlin.math.roundToInt

/**
 * The player's chrome — design spec §"Screens: player" (layout + per-mode table)
 * and §"Components" (round buttons, Go live pill, scrubber, spinner). Every
 * measurement is a handoff px through [LocalHappyTvDesign]; the caller
 * (PlayerScreen) places these on the 1920 × 1080 artboard at the coordinates
 * the handoff names.
 */

// ------------------------------------------------------------------ gradients

/** Top scrim (#131): 240 px, black 0.80 easing to 0.75 behind the title block, then transparent. */
@Composable
fun PlayerTopGradient() {
    val d = LocalHappyTvDesign.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.px(PlayerScrim.TOP_HEIGHT_PX))
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to PlayerColors.scrim(PlayerScrim.TOP_ALPHA_AT_EDGE),
                        PlayerScrim.TOP_HOLD_FRACTION to PlayerColors.scrim(PlayerScrim.TOP_ALPHA_AT_HOLD),
                        1f to Color.Transparent,
                    ),
                ),
            ),
    )
}

/**
 * Bottom scrim (#131): backs the control row and scrubber only (352 px, was 560) with a 72 px
 * soft fade above the controls, so the picture stays visible above them.
 */
@Composable
fun PlayerBottomGradient() {
    val d = LocalHappyTvDesign.current
    val fade = PlayerScrim.BOTTOM_FADE_PX.toFloat() / PlayerScrim.BOTTOM_HEIGHT_PX
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.px(PlayerScrim.BOTTOM_HEIGHT_PX))
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        fade to PlayerColors.scrim(PlayerScrim.BOTTOM_ALPHA_AT_FADE_END),
                        1f to PlayerColors.scrim(PlayerScrim.BOTTOM_ALPHA_AT_EDGE),
                    ),
                ),
            ),
    )
}

// ----------------------------------------------------------------- top block

/**
 * Top-left block (top 54, left 96): 168 × 112 monogram (channels), 32 px gap,
 * then "101 · Northbay News" (28/36 600 Secondary) over the program title
 * (64/72 700, truncates). Movies drop the monogram and show "2023 · PG-13".
 */
@Composable
fun PlayerTopBlock(
    topLine: String?,
    title: String,
    monogram: String?,
    monogramPalette: MonogramPalette?,
    logoUrl: String? = null,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Row(verticalAlignment = Alignment.Top) {
        if (monogram != null && monogramPalette != null) {
            HtChannelLogo(
                logoUrl = logoUrl,
                widthPx = 168,
                heightPx = 112,
                radiusPx = 16,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(monogramPalette.background, RoundedCornerShape(d.px(16))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = monogram,
                        style = typography.heading,
                        color = monogramPalette.letters,
                    )
                }
            }
            Spacer(Modifier.width(d.px(32)))
        }
        Column {
            if (topLine != null) {
                Text(
                    text = topLine,
                    style = typography.captionSemibold,
                    color = HappyTvDarkColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                style = typography.title,
                color = HappyTvDarkColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The right-hand clock (44/52 600 with the handoff's soft text shadow). */
@Composable
fun PlayerClock(utcMillis: Long) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Text(
        text = PlayerFormatting.clockLabel(utcMillis),
        style = typography.heading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600).tabular(),
        color = HappyTvDarkColors.TextPrimary,
        // "0 2px 12px rgba(0, 0, 0, 0.60)" — Compose draws elevation shadows
        // under a layer, which is the closest match to the CSS blur.
        modifier = Modifier.shadow(
            elevation = d.px(12),
            shape = RectangleShape,
            ambientColor = HappyTvDarkColors.shadow(0.60f),
            spotColor = HappyTvDarkColors.shadow(0.60f),
        ),
    )
}

// ---------------------------------------------------------------- info card

/**
 * The Program info card (#131; channels only, toggled by Program info): the channel line, the
 * program's title, its time range with the time left, the guide's description, and Next with its
 * time. 800 wide, padding 32/40, radius 24, [PlayerColors.Card]. The caller anchors its bottom
 * above the control row.
 */
@Composable
fun PlayerInfoCardView(card: PlayerInfoCard) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Column(
        modifier = Modifier
            .width(d.px(INFO_CARD_WIDTH_PX))
            .clip(RoundedCornerShape(d.px(24)))
            .background(PlayerColors.Card)
            .padding(horizontal = d.px(40), vertical = d.px(32)),
        verticalArrangement = Arrangement.spacedBy(d.px(12)),
    ) {
        if (card.channelLine != null) {
            Text(
                text = card.channelLine,
                style = typography.captionSemibold,
                color = HappyTvDarkColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = card.title,
            style = typography.heading,
            color = HappyTvDarkColors.TextPrimary,
            maxLines = INFO_CARD_TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.timeRange != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(d.px(20))) {
                Text(
                    text = card.timeRange,
                    style = typography.captionSemibold.tabular(),
                    color = HappyTvDarkColors.TextSecondary,
                )
                if (card.timeLeft != null) {
                    Text(
                        text = card.timeLeft,
                        style = typography.captionSemibold.tabular(),
                        color = HappyTvDarkColors.Accent,
                    )
                }
            }
        }
        Text(
            text = card.description ?: "No description from the guide.",
            style = typography.caption,
            color = if (card.description != null) HappyTvDarkColors.TextSecondary else HappyTvDarkColors.TextTertiary,
            maxLines = INFO_CARD_DESCRIPTION_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.nextTitle != null) {
            Row(
                modifier = Modifier.padding(top = d.px(8)),
                horizontalArrangement = Arrangement.spacedBy(d.px(20)),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "Next",
                    style = typography.captionSemibold,
                    color = HappyTvDarkColors.TextTertiary,
                )
                if (card.nextRange != null) {
                    Text(
                        text = card.nextRange,
                        style = typography.captionSemibold.tabular(),
                        color = HappyTvDarkColors.TextSecondary,
                    )
                }
                Text(
                    text = card.nextTitle,
                    style = typography.captionSemibold,
                    color = HappyTvDarkColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

internal const val SCRUBBER_TRACK_TAG = "player.scrubber.track"
internal const val SCRUBBER_DOT_TAG = "player.scrubber.dot"
internal const val SCRUBBER_FILL_TAG = "player.scrubber.fill"
internal const val SCRUBBER_TARGET_TAG = "player.scrubber.target"
internal const val SCRUBBER_FOCUS_TAG = "player.scrubber.focus"
internal const val INFO_CARD_DESCRIPTION_MAX_LINES = 4
internal const val INFO_CARD_TITLE_MAX_LINES = 2
internal const val INFO_CARD_WIDTH_PX = 800

// ------------------------------------------------------------- control row

/** Which control currently has the D-pad focus (drives the label under it). */
enum class PlayerControl { PlayPause, GoLive, StartOver, ProgramInfo, Tracks, Favorite }

/**
 * The control row (left 96, top 800, 24 apart), in the order [playerControlOrder] gives:
 * 88 px round buttons on rgba(28, 24, 21, 0.90); **Start over** (live) and **Go live** (replay)
 * are the 220 px pill in the same slot; the focused control gets its 28/36 600 label 16 px
 * below (top 904). Program info and Start over stay as greyed-out focus stops when there is no
 * guide data or no catch-up, so the reason shows under them (#131); the tracks button hides
 * entirely with no track information.
 */
@Composable
fun PlayerControlRow(
    content: PlayerContent,
    controlsVisible: Boolean,
    focused: PlayerControl?,
    requesters: Map<PlayerControl, FocusRequester> = emptyMap(),
    onFocus: (PlayerControl) -> Unit,
    onPlayPause: () -> Unit,
    onGoLive: () -> Unit,
    onStartOver: () -> Unit = {},
    onProgramInfo: () -> Unit,
    onTracks: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val startOverReason = PlayerStartOver.unavailableReason(content)
    val infoAvailable = content.nowNext != null

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(d.px(24)),
        verticalAlignment = Alignment.Top,
    ) {
        for (control in playerControlOrder(content)) {
            when (control) {
                PlayerControl.PlayPause -> ControlItem(
                    control = control,
                    label = playPauseLabel(content.isPlaying),
                    requester = requesters[control],
                    focusable = controlsVisible,
                    onFocus = onFocus,
                    onClick = onPlayPause,
                ) {
                    Icon(
                        imageVector = playPauseIcon(content.isPlaying),
                        contentDescription = null,
                        tint = HappyTvDarkColors.TextPrimary,
                        modifier = Modifier.size(d.px(40)),
                    )
                }

                // Replay only; no label under it.
                PlayerControl.GoLive -> PillButton(
                    control = control,
                    label = null,
                    enabled = true,
                    requester = requesters[control],
                    focusable = controlsVisible,
                    onFocus = onFocus,
                    onClick = onGoLive,
                    contentDescription = "Go live",
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(d.px(16)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(d.px(16))
                                .clip(CircleShape)
                                .background(PlayerColors.LiveDot),
                        )
                        Text(
                            text = "Go live",
                            style = typography.captionBold,
                            color = HappyTvDarkColors.TextPrimary,
                        )
                    }
                }

                // Live only: replays the current program from its start (#131).
                PlayerControl.StartOver -> PillButton(
                    control = control,
                    label = startOverReason,
                    enabled = startOverReason == null,
                    requester = requesters[control],
                    focusable = controlsVisible,
                    onFocus = onFocus,
                    onClick = onStartOver,
                    contentDescription = if (startOverReason == null) "Start over" else "Start over, not available",
                ) {
                    Text(
                        text = "Start over",
                        style = typography.captionBold,
                        color = HappyTvDarkColors.TextPrimary,
                    )
                }

                PlayerControl.ProgramInfo -> ControlItem(
                    control = control,
                    label = if (infoAvailable) "Program info" else PROGRAM_INFO_UNAVAILABLE,
                    enabled = infoAvailable,
                    requester = requesters[control],
                    focusable = controlsVisible,
                    onFocus = onFocus,
                    onClick = onProgramInfo,
                ) {
                    Icon(
                        imageVector = HappyTvIcons.Info,
                        contentDescription = null,
                        tint = HappyTvDarkColors.TextPrimary,
                        modifier = Modifier.size(d.px(40)),
                    )
                }

                // Hidden entirely when tracks are unknown ([playerControlOrder]).
                PlayerControl.Tracks -> ControlItem(
                    control = control,
                    label = "Audio and subtitles",
                    requester = requesters[control],
                    focusable = controlsVisible,
                    onFocus = onFocus,
                    onClick = onTracks,
                ) {
                    Icon(
                        imageVector = PlayerIcons.Subtitles,
                        contentDescription = null,
                        tint = HappyTvDarkColors.TextPrimary,
                        modifier = Modifier.size(d.px(40)),
                    )
                }

                PlayerControl.Favorite -> ControlItem(
                    control = control,
                    label = if (content.isFavorite) "Remove favorite" else "Favorite",
                    requester = requesters[control],
                    focusable = controlsVisible,
                    onFocus = onFocus,
                    onClick = onFavorite,
                ) {
                    Icon(
                        imageVector = if (content.isFavorite) PlayerIcons.StarFilled else PlayerIcons.StarOutline,
                        contentDescription = null,
                        tint = if (content.isFavorite) HappyTvDarkColors.Accent else HappyTvDarkColors.TextSecondary,
                        modifier = Modifier.size(d.px(44)),
                    )
                }
            }
        }
    }
}

/** The button shows the action: pause bars while playing, the triangle while paused. */
internal fun playPauseIcon(isPlaying: Boolean) = if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play

internal fun playPauseLabel(isPlaying: Boolean) = if (isPlaying) "Pause" else "Play"

/** Greyed-out controls keep their shape and ring; only the content dims. */
internal const val DISABLED_CONTENT_ALPHA = 0.45f

/**
 * One round button + its label under the focus (top 904).
 *
 * Chain order is the F-33 contract ([htvPlayerFocusable]): size, then the focus ring, then clip
 * and background. The ring and glow draw outside the bounds, so anything that clips must come
 * after them or the ring is erased (the player used to draw it after `.clip`, #131). Focus
 * plumbing is the app's usual one too: a single [MutableInteractionSource] shared by `clickable`
 * and `focusable`, so the ring and the label follow the real focus whichever of the two targets the
 * D-pad lands on (a separate `onFocusChanged` on the outer target never fired for the one inside
 * the clickable, which left a focused button with no cue at all on the TV).
 */
@Composable
private fun ControlItem(
    control: PlayerControl,
    label: String?,
    requester: FocusRequester?,
    focusable: Boolean,
    onFocus: (PlayerControl) -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val source = remember { MutableInteractionSource() }
    val isFocused by source.collectIsFocusedAsState()
    val showFocus = isFocused && focusable
    LaunchedEffect(showFocus) { if (showFocus) onFocus(control) }
    Column(modifier = Modifier.width(d.px(88)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(d.px(88))
                .playerRing(showFocus, d.px(44))
                .clip(CircleShape)
                .background(PlayerColors.Card)
                // The label on the focusable node itself, for TalkBack and scripts/tv-ok.sh.
                .semantics { if (label != null) this.contentDescription = label }
                .playerFocusTarget(focusable, requester, source, if (enabled) onClick else NO_CLICK),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA)) { icon() }
        }
        ControlLabel(label = if (showFocus) label else null)
    }
}

/**
 * Fixed-height label slot keeps row metrics identical whether or not a control's label shows. The
 * column is as wide as its button, so a label wider than the button ("Audio and subtitles") runs
 * past it, centred, and never pushes the buttons beside it.
 */
@Composable
private fun ControlLabel(label: String?) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Spacer(Modifier.height(d.px(16)))
    Box(modifier = Modifier.height(d.px(36))) {
        if (label != null) {
            Text(
                text = label,
                style = typography.captionSemibold,
                color = HappyTvDarkColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                // The pill's reason line is wider than the pill; let it run past instead of wrapping.
                modifier = Modifier.wrapContentWidth(unbounded = true),
            )
        }
    }
}

/** The 220 × 88 pill: Go live and Start over — Components §"Buttons > Go live". Same chain as [ControlItem]. */
@Composable
private fun PillButton(
    control: PlayerControl,
    label: String?,
    enabled: Boolean,
    requester: FocusRequester?,
    focusable: Boolean,
    onFocus: (PlayerControl) -> Unit,
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val source = remember { MutableInteractionSource() }
    val isFocused by source.collectIsFocusedAsState()
    val showFocus = isFocused && focusable
    LaunchedEffect(showFocus) { if (showFocus) onFocus(control) }
    Column(modifier = Modifier.width(d.px(220)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = d.px(220), height = d.px(88))
                .playerRing(showFocus, d.px(44))
                .clip(RoundedCornerShape(d.px(44)))
                .background(PlayerColors.Card)
                .semantics { this.contentDescription = contentDescription }
                .playerFocusTarget(focusable, requester, source, if (enabled) onClick else NO_CLICK),
            contentAlignment = Alignment.Center,
        ) {
            // Screen readers get the pill's name from [contentDescription] on the
            // caller's semantics; visuals come from [content].
            Box(modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA)) { content() }
        }
        ControlLabel(label = if (showFocus) label else null)
    }
}

// ------------------------------------------------------------ progress row

/**
 * The progress row (top 966, x 96, 1728 wide, 44 tall, items 28 apart): start
 * label, the 8 px bar (track rgba(255, 255, 255, 0.20), buffered 0.32,
 * accent fill, 24 px dot; replay adds the 3 × 28 cream live-edge marker),
 * end label, and the per-mode badge.
 */
@Composable
fun PlayerProgressRow(
    progress: PlayerProgress,
    badge: PlayerBadge?,
    /** Movies and episodes: the bar takes the D-pad focus and seeks (#102). */
    seekable: Boolean = false,
    /** The pending seek target ("1:02:14"), drawn above the dot while seeking. */
    seekTargetLabel: String? = null,
    scrubberFocus: FocusRequester? = null,
    onScrubberFocusChange: (Boolean) -> Unit = {},
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Row(
        modifier = Modifier
            .width(d.px(1728))
            .height(d.px(44)),
        horizontalArrangement = Arrangement.spacedBy(d.px(28)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress.startLabel != null) {
            Text(
                text = progress.startLabel,
                style = typography.captionSemibold.tabular(),
                color = HappyTvDarkColors.TextSecondary,
            )
        }
        Scrubber(
            progress = progress,
            seekable = seekable,
            seekTargetLabel = seekTargetLabel,
            focusRequester = scrubberFocus,
            onFocusChange = onScrubberFocusChange,
            modifier = Modifier
                .weight(1f)
                .height(d.px(44)),
        )
        if (progress.endLabel != null) {
            Text(
                text = progress.endLabel,
                style = typography.captionSemibold.tabular(),
                color = HappyTvDarkColors.TextSecondary,
            )
        }
        if (badge != null) {
            ProgressBadge(badge)
        }
    }
}

@Composable
private fun Scrubber(
    progress: PlayerProgress,
    seekable: Boolean,
    seekTargetLabel: String?,
    focusRequester: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusChange(focused) }
    // The whole bar is the focus target; the ring is drawn on the dot so the
    // bar itself never changes size (focus rules: no scale, no reflow).
    val focusModifier = if (seekable) {
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .testTag(SCRUBBER_FOCUS_TAG)
    } else {
        Modifier
    }
    BoxWithConstraints(modifier = modifier.then(focusModifier), contentAlignment = Alignment.Center) {
        val barWidth = constraints.maxWidth.toFloat()
        val trackHeight = d.px(8)
        val dotSize = d.px(24)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(PlayerColors.ScrubberTrack)
                .testTag(SCRUBBER_TRACK_TAG),
        )
        // Buffered part (behind the fill).
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(progress.bufferedFraction.coerceIn(0f, 1f))
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(PlayerColors.ScrubberBuffered),
        )
        // Accent fill.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(progress.fillFraction.coerceIn(0f, 1f))
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(HappyTvDarkColors.Accent)
                .testTag(SCRUBBER_FILL_TAG),
        )
        // Replay's live-edge marker: 3 × 28 px cream, before the dot layer.
        progress.liveEdgeFraction?.let { edge ->
            if (edge in 0f..1f) {
                Box(
                    modifier = Modifier
                        // The box centers its children, so pin the marker and the dot to
                        // the start edge before offsetting them (#94: the dot was
                        // displaced by half the bar and landed on the "Live" badge).
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((barWidth * edge - d.px(3).toPx() / 2f).roundToInt(), 0) }
                        .size(width = d.px(3), height = d.px(28))
                        .background(PlayerColors.Cream),
                )
            }
        }
        // The 24 px dot, centered on its fraction.
        val dotColor = when (progress.dot) {
            ScrubberDot.PulsingRed -> PlayerColors.LiveDot
            ScrubberDot.Cream -> PlayerColors.Cream
        }
        val dotModifier = Modifier
            .align(Alignment.CenterStart)
            .offset {
                IntOffset(
                    (barWidth * progress.fillFraction.coerceIn(0f, 1f) - dotSize.toPx() / 2f).roundToInt(),
                    0,
                )
            }
            .size(dotSize)
            .happyTvFocusRing(
                focused = seekable && focused,
                radius = dotSize / 2,
                focusRingColor = HappyTvDarkColors.Accent,
                glowColor = HappyTvDarkColors.AccentGlow,
            )
            .clip(CircleShape)
            .background(dotColor)
            .testTag(SCRUBBER_DOT_TAG)
        if (progress.dot == ScrubberDot.PulsingRed) {
            PulsingDot(modifier = dotModifier)
        } else {
            Box(modifier = dotModifier)
        }
        // The pending seek time, centred over the dot and kept inside the bar.
        if (seekTargetLabel != null) {
            val dotCenter = barWidth * progress.fillFraction.coerceIn(0f, 1f)
            Text(
                text = seekTargetLabel,
                style = typography.captionSemibold.tabular(),
                color = HappyTvDarkColors.TextPrimary,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
                        layout(placeable.width, placeable.height) {
                            val x = (dotCenter - placeable.width / 2f)
                                .coerceIn(0f, (barWidth - placeable.width).coerceAtLeast(0f))
                            placeable.place(x.roundToInt(), -d.px(36).roundToPx())
                        }
                    }
                    .clip(RoundedCornerShape(d.px(14)))
                    .background(HappyTvDarkColors.Raised)
                    .padding(horizontal = d.px(14), vertical = d.px(4))
                    .testTag(SCRUBBER_TARGET_TAG),
            )
        }
    }
}

/** The Live badge: red tint, 12 px pulsing red dot, "Live" 28/36 800 red. */
@Composable
private fun ProgressBadge(badge: PlayerBadge) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    when (badge.kind) {
        BadgeKind.Live -> Row(
            modifier = Modifier
                .height(d.px(44))
                .clip(RoundedCornerShape(d.px(22)))
                .background(PlayerColors.LiveBadgeTint)
                .padding(horizontal = d.px(18)),
            horizontalArrangement = Arrangement.spacedBy(d.px(12)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulsingDot(
                modifier = Modifier
                    .size(d.px(12))
                    .clip(CircleShape)
                    .background(PlayerColors.LiveDot),
            )
            Text(
                text = badge.text,
                style = typography.captionBold,
                color = HappyTvDarkColors.StateError,
            )
        }
        BadgeKind.MovieEnds -> Box(
            modifier = Modifier
                .height(d.px(44))
                .clip(RoundedCornerShape(d.px(22)))
                .background(PlayerColors.Card)
                .padding(horizontal = d.px(18)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = badge.text,
                style = typography.captionSemibold,
                color = HappyTvDarkColors.TextSecondary,
            )
        }
        BadgeKind.Replay -> Text(
            text = badge.text,
            style = typography.captionBold,
            color = HappyTvDarkColors.Accent,
        )
    }
}

// ----------------------------------------------------------------- spinner

/** Buffering spinner: 120 px, 19-unit circle track + quarter arc, 1000 ms loop. */
@Composable
fun PlayerBufferingSpinner(modifier: Modifier = Modifier) {
    val d = LocalHappyTvDesign.current
    val transition = rememberInfiniteTransition(label = "buffering")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "buffering-angle",
    )
    Canvas(modifier = modifier.size(d.px(120))) {
        val stroke = Stroke(width = size.minDimension * 0.08f)
        val inset = stroke.width / 2f
        val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(
            color = PlayerColors.SpinnerTrack,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = HappyTvDarkColors.Accent,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}

// ------------------------------------------------------------------- shared

/** The handoff's pulsing dot: opacity 0.35 ↔ 1, ease-in-out, ~1.4 s loop. */
@Composable
private fun PulsingDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = androidx.compose.animation.core.EaseInOut), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Box(modifier = modifier.graphicsLayer(alpha = alpha))
}

// ------------------------------------------------- focus / click plumbing
//
// The control row borrows the same focus treatment as every other screen
// (design spec "Focus ring": 4 px accent ring + glow, never changes size), and
// only takes focus while the controls are showing — hidden controls are
// unfocusable so a stray D-pad press can't land on an invisible button.

/** The app's standard focus ring and glow ([happyTvFocusRing]); place it before any clip. */
@Composable
private fun Modifier.playerRing(focused: Boolean, radius: Dp): Modifier =
    happyTvFocusRing(
        focused = focused,
        radius = radius,
        focusRingColor = HappyTvDarkColors.focusRingOn(PlayerColors.Card),
        glowColor = HappyTvDarkColors.AccentGlow,
    )

private val NO_CLICK: () -> Unit = {}

/**
 * Requester first, then `clickable` and `focusable` on the same interaction source (the
 * [htvPlayerFocusable] plumbing). Hidden controls ([enabled] false) take no focus, so a stray
 * D-pad press can't land on an invisible button.
 */
private fun Modifier.playerFocusTarget(
    enabled: Boolean,
    requester: FocusRequester?,
    source: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier =
    if (enabled) {
        this
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .focusable(interactionSource = source)
    } else {
        this
    }
