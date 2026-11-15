package com.rysal.happytv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import com.rysal.happytv.ui.browse.HtChannelMark
import com.rysal.happytv.ui.theme.HappyTvDarkColors
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography
import com.rysal.happytv.ui.theme.happyTvFocusRing
import com.rysal.happytv.ui.theme.tabular

/**
 * What [PlayerChrome] needs to draw the recent channels row (#220): the cards, whether the row is
 * open, whether the "Recent channels" hint shows beside the controls, and the focus plumbing.
 */
internal class RecentRowUi(
    val cards: List<RecentChannelCard>,
    val open: Boolean,
    val hintVisible: Boolean,
    val requesters: List<FocusRequester>,
    val focusedIndex: Int,
    val nowUtcMillis: Long,
    val onCardFocused: (Int) -> Unit,
    val onPick: (Int) -> Unit,
)

internal const val RECENT_CARD_WIDTH_PX = 226
internal const val RECENT_SCRIM_HEIGHT_PX = 640
internal const val RECENT_SCRIM_FADE_PX = 230

/**
 * The hint at the right end of the control row: "Recent channels" and a down chevron
 * (left of the artboard edge by 96, top 800, 88 high).
 */
@Composable
internal fun RecentChannelsHint() {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    Row(
        modifier = Modifier.height(d.px(88)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.px(12)),
    ) {
        Text(text = "Recent channels", style = typography.captionSemibold, color = HappyTvDarkColors.TextSecondary)
        Icon(
            imageVector = PlayerIcons.ChevronDown,
            contentDescription = null,
            tint = HappyTvDarkColors.TextPrimary,
            modifier = Modifier.size(d.px(44)),
        )
    }
}

/**
 * The row itself (left 96, bottom 54, 1728 wide): heading and "Show controls" hint, the focused
 * channel's full line, then up to seven 226 x 236 cards 24 apart, left-aligned. Dark in every
 * theme: it sits over video, like the rest of the player.
 */
@Composable
internal fun RecentChannelsRow(ui: RecentRowUi, modifier: Modifier = Modifier) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val focused = ui.cards.getOrNull(ui.focusedIndex.coerceIn(0, (ui.cards.size - 1).coerceAtLeast(0)))
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Recent channels", style = typography.compactHeading, color = HappyTvDarkColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.px(10))) {
                Icon(
                    imageVector = PlayerIcons.ChevronUp,
                    contentDescription = null,
                    tint = HappyTvDarkColors.TextSecondary,
                    modifier = Modifier.size(d.px(32)),
                )
                Text(text = "Show controls", style = typography.compactCaptionSemibold, color = HappyTvDarkColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(d.px(6)))
        if (focused != null) {
            Text(
                text = leadLine(focused),
                style = typography.captionSemibold.tabular(),
                color = HappyTvDarkColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.height(d.px(36)))
        }
        Spacer(Modifier.height(d.px(20)))
        Row(horizontalArrangement = Arrangement.spacedBy(d.px(24))) {
            ui.cards.forEachIndexed { index, card ->
                RecentChannelCardView(
                    card = card,
                    requester = ui.requesters[index],
                    nowUtcMillis = ui.nowUtcMillis,
                    onFocused = { ui.onCardFocused(index) },
                    onClick = { ui.onPick(index) },
                )
            }
        }
    }
}

/** "104 · Summit Sports · Championship Highlights · 9:30 – 11:00", or "... · No guide info". */
@Composable
private fun leadLine(card: RecentChannelCard) = buildAnnotatedString {
    append(PlayerFormatting.channelLine(card.number, card.name))
    append(" · ")
    if (card.hasGuide) {
        withStyle(SpanStyle(fontWeight = FontWeight.W700, color = HappyTvDarkColors.TextPrimary)) { append(card.programTitle!!) }
        append(" · ${PlayerFormatting.clockLabel(card.startUtcMillis)} – ${PlayerFormatting.clockLabel(card.endUtcMillis)}")
    } else {
        withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(NO_GUIDE_INFO) }
    }
}

internal const val NO_GUIDE_INFO = "No guide info"

/**
 * One card: logo tile (the lettered mark until a logo loads), name, program on now and its progress
 * line. The ring is drawn outside the bounds and sits before the clip (the F-33 chain order); the
 * requester comes before the focusable it targets. A focused card never changes size.
 */
@Composable
private fun RecentChannelCardView(
    card: RecentChannelCard,
    requester: FocusRequester,
    nowUtcMillis: Long,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val source = remember { MutableInteractionSource() }
    val isFocused by source.collectIsFocusedAsState()
    LaunchedEffect(isFocused) { if (isFocused) onFocused() }
    Column(
        modifier = Modifier
            .width(d.px(RECENT_CARD_WIDTH_PX))
            .happyTvFocusRing(
                focused = isFocused,
                radius = d.px(20),
                focusRingColor = HappyTvDarkColors.focusRingOn(PlayerColors.Card),
                glowColor = HappyTvDarkColors.AccentGlow,
            )
            .clip(RoundedCornerShape(d.px(20)))
            .background(PlayerColors.Card)
            .semantics { contentDescription = card.name }
            .focusRequester(requester)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .focusable(interactionSource = source)
            .padding(d.px(16)),
    ) {
        HtChannelMark(
            name = card.name,
            logoUrl = card.logoUrl,
            widthPx = 194,
            heightPx = 108,
            radiusPx = 12,
        )
        Spacer(Modifier.height(d.px(14)))
        Text(
            text = card.name,
            style = typography.captionBold,
            color = HappyTvDarkColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = card.programTitle ?: NO_GUIDE_INFO,
            style = typography.compactCaption,
            color = if (card.hasGuide) HappyTvDarkColors.TextSecondary else HappyTvDarkColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(d.px(10)))
        val fraction = card.progressFraction(nowUtcMillis)
        // The 4 px slot is always there, so cards with and without guide data stay the same height.
        Box(modifier = Modifier.fillMaxWidth().height(d.px(4)).clip(RoundedCornerShape(d.px(2)))) {
            if (fraction != null) {
                Box(modifier = Modifier.fillMaxWidth().height(d.px(4)).background(PlayerColors.ScrubberTrack))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(d.px(4))
                        .background(HappyTvDarkColors.Accent),
                )
            }
        }
    }
}
