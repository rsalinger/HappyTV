package com.rysal.happytv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.rysal.happytv.data.guide.GuideProgramState
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.browse.HtChannelMark
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.browse.htvClickableSurface
import com.rysal.happytv.ui.focus.requestWhenAttached
import com.rysal.happytv.ui.focus.tryRequest
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.tabular

/**
 * Program details dialog (design spec "Program details dialog", compact sizes of spec
 * #7 section 1): scrim 60%, a Raised card 960 wide at left 480, top 200, radius 24,
 * padding 40 48, items 20 apart.
 *
 * Buttons follow the conditional set: **Watch now** on now only; **Watch from the
 * start** on now/ended x replay channels (secondary next to Watch now, primary for
 * an ended program); **Remind me** for future programs (disabled-looking, v1.1
 * chip, still a focus stop).
 *
 * The description is not part of [dialog]: it is one single-row read for this
 * program ([loadDescription], off the main thread), filled in when it lands, so
 * the dialog opens on the first frame whatever the guide holds. No heavy shadow
 * either: the open and close budgets are 150 ms each (`guide.details.open` /
 * `guide.details.close`).
 *
 * Focus is Compose's: the first button takes it, Left/Right walk the buttons,
 * Up/Down are trapped inside, Back and a tap on the scrim close.
 */
@Composable
fun GuideDetailsDialog(
    dialog: GuideDialogUi,
    loadDescription: suspend () -> String?,
    onWatchNow: () -> Unit,
    onWatchFromStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val d = browseDesign()
    val type = browseType()
    val requesters = remember { HashMap<GuideDialogButton, FocusRequester>() }
    fun requesterFor(button: GuideDialogButton) = requesters.getOrPut(button) { FocusRequester() }

    var description by remember(dialog.program) { mutableStateOf<String?>(null) }
    var descriptionLoaded by remember(dialog.program) { mutableStateOf(false) }
    LaunchedEffect(dialog.program) {
        description = loadDescription()
        descriptionLoaded = true
    }

    // `guide.details.open`: OK pressed -> the dialog's first drawn frame. Focus
    // goes to the primary button, frame by frame until it lands.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        HtvPerf.end(HtvPerf.GUIDE_DETAILS_OPEN)
        requesterFor(dialog.initialButton).requestWhenAttached()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // Back closes even when no button holds focus.
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown) onDismiss()
                    true
                } else {
                    false
                }
            },
    ) {
        // Scrim - a tap closes (Back does the same).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HappyTvColors.Scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onDismiss()
                },
        )

        Column(
            modifier = Modifier
                .offset(d.px(480), d.px(200))
                .width(d.px(960))
                .background(HappyTvColors.Raised, RoundedCornerShape(d.px(24)))
                .padding(start = d.px(48), top = d.px(40), end = d.px(48), bottom = d.px(40)),
            verticalArrangement = Arrangement.spacedBy(d.px(20)),
        ) {
            // 1. Channel: monogram + "101 - Northbay News".
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.px(16)),
            ) {
                HtChannelMark(
                    name = dialog.channel.name,
                    logoUrl = dialog.channel.logoUrl,
                    widthPx = 72,
                    heightPx = 48,
                    radiusPx = 8,
                    textStyle = type.compactCaptionSemibold,
                )
                Text(
                    text = dialog.channelLine,
                    style = type.compactCaptionSemibold.tabular(),
                    color = HappyTvColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 2. Title over details.
            Column(verticalArrangement = Arrangement.spacedBy(d.px(4))) {
                Text(
                    text = dialog.title,
                    style = type.compactHeading,
                    color = HappyTvColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = dialog.metaLine,
                    style = type.compactCaption.tabular(),
                    color = HappyTvColors.TextSecondary,
                )
            }

            // 3. Progress through the current program (6 px, 12% track).
            val progress = dialog.progressFraction
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(d.px(6))
                        .clip(RoundedCornerShape(d.px(3)))
                        .background(HappyTvColors.GuideDetailsTrack),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(HappyTvColors.Accent, RoundedCornerShape(d.px(3))),
                    )
                }
            }

            // 4. Description (or the tertiary placeholder) once the single-row read lands.
            if (descriptionLoaded) {
                Text(
                    text = description ?: "No description from the guide.",
                    style = type.compactBody,
                    color = if (description != null) HappyTvColors.TextSecondary else HappyTvColors.TextTertiary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 5. Buttons, 20 px gaps.
            Row(horizontalArrangement = Arrangement.spacedBy(d.px(20))) {
                dialog.buttons.forEach { button ->
                    DialogButton(
                        button = button,
                        dialog = dialog,
                        requester = requesterFor(button),
                        onKey = { key ->
                            when (key) {
                                GuideKey.LEFT, GuideKey.RIGHT ->
                                    moveDialogFocus(button, key, dialog)?.let { requesterFor(it).tryRequest() }
                                GuideKey.UP, GuideKey.DOWN -> Unit // trapped inside the dialog
                            }
                        },
                        onClick = {
                            when (button) {
                                GuideDialogButton.WATCH_NOW -> onWatchNow()
                                GuideDialogButton.WATCH_FROM_START -> onWatchFromStart()
                                GuideDialogButton.REMIND -> Unit // v1.1 chip - reminders land later
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    button: GuideDialogButton,
    dialog: GuideDialogUi,
    requester: FocusRequester,
    onKey: (GuideKey) -> Unit,
    onClick: () -> Unit,
) {
    val d = browseDesign()
    val type = browseType()

    // Visual variant - the prototype's replayBg/replayFg + remind styling.
    val background: Color
    val labelColor: Color
    val weight: FontWeight
    when (button) {
        GuideDialogButton.WATCH_NOW -> {
            background = HappyTvColors.Accent
            labelColor = HappyTvColors.OnAccent
            weight = FontWeight.W800
        }
        GuideDialogButton.WATCH_FROM_START -> {
            // Secondary (Overlay) beside Watch now; primary (accent) for an ended program.
            val primary = dialog.state != GuideProgramState.LIVE
            background = if (primary) HappyTvColors.Accent else HappyTvColors.Overlay
            labelColor = if (primary) HappyTvColors.OnAccent else HappyTvColors.TextPrimary
            weight = FontWeight.W700
        }
        GuideDialogButton.REMIND -> {
            background = HappyTvColors.Overlay
            labelColor = HappyTvColors.TextTertiary
            weight = FontWeight.W700
        }
    }

    Row(
        modifier = Modifier
            .height(d.px(64))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val key = when (event.key) {
                    Key.DirectionLeft -> GuideKey.LEFT
                    Key.DirectionRight -> GuideKey.RIGHT
                    Key.DirectionUp -> GuideKey.UP
                    Key.DirectionDown -> GuideKey.DOWN
                    else -> null
                }
                if (key == null) {
                    false
                } else {
                    onKey(key)
                    true
                }
            }
            // Before the surface: a FocusRequester only finds focus targets after it.
            .focusRequester(requester)
            .htvClickableSurface(radiusPx = 32, background = background, onClick = onClick)
            .padding(start = d.px(32), end = if (button == GuideDialogButton.REMIND) d.px(20) else d.px(36)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.px(12)),
    ) {
        when (button) {
            GuideDialogButton.WATCH_NOW -> Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = HappyTvColors.OnAccent,
                modifier = Modifier.size(d.px(32)),
            )
            GuideDialogButton.WATCH_FROM_START -> Icon(
                imageVector = GuideReplayIcon,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(d.px(32)),
            )
            GuideDialogButton.REMIND -> Unit
        }
        Text(
            text = when (button) {
                GuideDialogButton.WATCH_NOW -> "Watch now"
                GuideDialogButton.WATCH_FROM_START -> "Watch from the start"
                GuideDialogButton.REMIND -> "Remind me"
            },
            style = type.compactRowName.copy(fontWeight = weight),
            color = labelColor,
        )
        if (button == GuideDialogButton.REMIND) {
            Box(
                modifier = Modifier
                    .height(d.px(36))
                    .background(HappyTvColors.Raised, RoundedCornerShape(d.px(18)))
                    .padding(horizontal = d.px(12)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "v1.1",
                    style = type.compactCaptionSemibold,
                    color = HappyTvColors.TextSecondary,
                )
            }
        }
    }
}

