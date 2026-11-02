package com.rysal.happytv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.rysal.happytv.ui.theme.HappyTvDarkColors
import com.rysal.happytv.ui.theme.happyTvFocusRing

/**
 * The player lane's focusable pill — ring first, ring colour follows the fill.
 *
 * F-33's chain-order contract, applied to the player screens: the ring and its
 * glow are drawn *outside* the element's bounds, so [happyTvFocusRing] must
 * precede `.clip()`/`.background()` — a clip above it erases both and the
 * control ends up with no focus treatment at all, which is exactly what the
 * offline card, the leave dialog and the tracks panel used to do (zero ring,
 * the clickable ripple only flashing on press a remote never sends).
 *
 * Ring colour comes from [HappyTvDarkColors.focusRingOn]: an Accent fill ("Stop
 * watching", a primary "Try again") gets the near-white TextPrimary ring;
 * everything else keeps the Accent ring.
 *
 * Focus plumbing mirrors `GuideFocusBox` (pixel-verified on the Chromecast
 * HD): one [MutableInteractionSource] shared by `clickable` and `focusable`,
 * focusable innermost, so an OK/Enter press bubbles through the clickable no
 * matter which of the two targets the focus search landed on.
 *
 * @param radius the control's own corner radius, so the ring follows it.
 * @param fill the control's fill; pass [Color.Transparent] for a row that
 *   draws no background of its own (the ring still reads on the panel behind).
 * @param focusRequester optional requester for programmatic focus — dialogs
 *   and panels that pick their initially-focused control when they open.
 */
@Composable
fun Modifier.htvPlayerFocusable(
    radius: Dp,
    fill: Color,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    return this
        .happyTvFocusRing(
            focused = isFocused,
            radius = radius,
            focusRingColor = HappyTvDarkColors.focusRingOn(fill),
            glowColor = HappyTvDarkColors.AccentGlow,
        )
        .clip(RoundedCornerShape(radius))
        .background(fill)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        .focusable(interactionSource = interactionSource)
}
