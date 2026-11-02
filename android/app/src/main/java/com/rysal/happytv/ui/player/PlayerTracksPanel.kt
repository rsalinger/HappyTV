package com.rysal.happytv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.rysal.happytv.ui.theme.HappyTvDarkColors
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography

/**
 * Audio and subtitles — the side panel (design spec §"Player" other states):
 * 640 wide at the right edge, full height, padding 54 96 54 48, Raised, with
 * the −24 px leftward shadow. Two radio groups ("Audio", "Subtitles") plus a
 * secondary **Done**. Focus opens on the selected audio track; Done or Back
 * returns focus to the Audio and subtitles button (the caller passes its
 * [returnFocus]).
 */
@Composable
fun PlayerTracksPanel(
    tracks: PlayerTracks,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current

    val audioFocus = remember(tracks.audio.size) { List(tracks.audio.size) { FocusRequester() } }
    val subtitleFocus = remember(tracks.subtitles.size) { List(tracks.subtitles.size) { FocusRequester() } }
    val doneFocus = remember { FocusRequester() }

    // "Focus opens on the selected audio track" (falls back down the panel).
    LaunchedEffect(tracks.selectedAudio, tracks.selectedSubtitle) {
        val first = audioFocus.getOrNull(tracks.selectedAudio)
            ?: subtitleFocus.getOrNull(tracks.selectedSubtitle)
            ?: doneFocus
        runCatching { first.requestFocus() }
    }

    Column(
        modifier = modifier
            .width(d.px(640))
            .fillMaxHeight()
            .shadow(
                elevation = d.px(40),
                shape = RectangleShape,
                ambientColor = HappyTvDarkColors.shadow(0.50f),
                spotColor = HappyTvDarkColors.shadow(0.50f),
            )
            .background(HappyTvDarkColors.Raised)
            .padding(start = d.px(48), end = d.px(96), top = d.px(54), bottom = d.px(54)),
    ) {
        Text(
            text = "Audio and subtitles",
            style = typography.heading,
            color = HappyTvDarkColors.TextPrimary,
        )
        Spacer(Modifier.height(d.px(40)))

        if (tracks.audio.isNotEmpty()) {
            TrackGroup(
                title = "Audio",
                options = tracks.audio,
                selectedIndex = tracks.selectedAudio,
                focusRequesters = audioFocus,
                onSelect = onSelectAudio,
            )
            Spacer(Modifier.height(d.px(32)))
        }

        TrackGroup(
            title = "Subtitles",
            options = tracks.subtitles,
            selectedIndex = tracks.selectedSubtitle,
            focusRequesters = subtitleFocus,
            onSelect = onSelectSubtitle,
        )

        Spacer(Modifier.height(d.px(40)))

        // Secondary Done — Back does the same, so it may take the focus.
        Box(
            modifier = Modifier
                .height(d.px(88))
                .htvPlayerFocusable(
                    radius = d.px(44),
                    fill = HappyTvDarkColors.Overlay,
                    focusRequester = doneFocus,
                    onClick = onDone,
                )
                .padding(horizontal = d.px(48)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Done",
                style = typography.bodyBold,
                color = HappyTvDarkColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun TrackGroup(
    title: String,
    options: List<PlayerTrackOption>,
    selectedIndex: Int,
    focusRequesters: List<FocusRequester>,
    onSelect: (Int) -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(d.px(8))) {
        Text(
            text = title,
            style = typography.captionBold,
            color = HappyTvDarkColors.TextTertiary,
        )
        options.forEachIndexed { index, option ->
            RadioRow(
                label = option.label,
                selected = index == selectedIndex,
                focus = focusRequesters.getOrNull(index),
                onClick = { onSelect(index) },
            )
        }
    }
}

/**
 * One radio row — Components §"Selection controls > Radio row (player panel)":
 * 88 tall, radius 16; a 40 px ring (3 px inset, accent when selected, Tertiary
 * otherwise) holding a 20 px accent dot when selected; label 36/44 600.
 */
@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    focus: FocusRequester?,
    onClick: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Row(
        modifier = Modifier
            .height(d.px(88))
            // No fill of its own — the panel's Raised shows through, so the
            // ring takes the default Accent (focusRingOn(Transparent)) that
            // reads on Raised like it does on every other screen.
            .htvPlayerFocusable(
                radius = d.px(16),
                fill = androidx.compose.ui.graphics.Color.Transparent,
                focusRequester = focus,
                onClick = onClick,
            )
            .padding(horizontal = d.px(24)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(d.px(40))
                .clip(CircleShape)
                .background(HappyTvDarkColors.Ground)
                .ring(selected, d.px(3), if (selected) HappyTvDarkColors.Accent else HappyTvDarkColors.TextTertiary),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(d.px(20))
                        .clip(CircleShape)
                        .background(HappyTvDarkColors.Accent),
                )
            }
        }
        Spacer(Modifier.width(d.px(24)))
        Text(
            text = label,
            style = typography.bodySemibold,
            color = HappyTvDarkColors.TextPrimary,
        )
    }
}

/** 3 px inset ring — drawn inside the bounds so the radio keeps its size. */
private fun Modifier.ring(selected: Boolean, width: Dp, color: androidx.compose.ui.graphics.Color): Modifier =
    drawWithContent {
        drawContent()
        val inset = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(inset / 2f, inset / 2f),
            size = Size(size.width - inset, size.height - inset),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension / 2f),
            style = Stroke(width = inset),
        )
    }
