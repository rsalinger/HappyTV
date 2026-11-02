package com.rysal.happytv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography
import com.rysal.happytv.ui.theme.tabular
import androidx.compose.ui.unit.dp

/**
 * "Unencrypted-server sheet" — design spec §"Sign in" and the `cleartext`
 * scenario of `Login.dc.html`.
 *
 * Shown when Sign in is pressed with a URL starting `http://` for a server
 * the user has not answered for before. Bottom sheet over a 0.60 scrim, top
 * corners 32, padding 56 64 86, `raised`, upward shadow.
 *
 * **Never skipped silently.** The answer is remembered per server
 * (`CleartextConsentStore`), and this is also the moment the host would be
 * added to `network_security_config.xml`'s cleartext allow-list (see the
 * note there for why that is app-side in v1).
 *
 * Buttons, in the handoff's order: **Use HTTPS** (primary, focused; switches
 * the URL to https:// and signs in), **Continue anyway** (signs in over http),
 * **Cancel** (returns to Server URL).
 */
@Composable
internal fun CleartextSheetPanel(
    hostLabel: String,
    onUseHttps: () -> Unit,
    onContinueAnyway: () -> Unit,
    onCancel: () -> Unit,
    handles: CleartextSheetHandles,
    modifier: Modifier = Modifier,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val shape = RoundedCornerShape(
        topStart = d.px(SHEET_CORNER_PX),
        topEnd = d.px(SHEET_CORNER_PX),
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )

    Column(
        modifier = modifier
            .width(d.px(SHEET_WIDTH_PX))
            // #164: shadow before fill and clip (see LoginScreen's card).
            .shadow(
                elevation = d.px(SHEET_SHADOW_PX),
                shape = shape,
                clip = false,
                ambientColor = HappyTvColors.shadow(SHEET_SHADOW_ALPHA),
                spotColor = HappyTvColors.shadow(SHEET_SHADOW_ALPHA),
            )
            .clip(shape)
            .background(HappyTvColors.Raised)
            .padding(
                start = d.px(PADDING_HORIZONTAL_PX),
                end = d.px(PADDING_HORIZONTAL_PX),
                top = d.px(PADDING_TOP_PX),
                bottom = d.px(PADDING_BOTTOM_PX),
            ),
        verticalArrangement = Arrangement.spacedBy(d.px(SECTION_GAP_PX)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(d.px(TITLE_GAP_PX))) {
            Text(
                text = "This provider doesn’t use encryption",
                style = typography.heading,
                color = HappyTvColors.TextPrimary,
            )
            Text(
                text = "Your password will be sent unencrypted over the network. Continue?",
                style = typography.body,
                color = HappyTvColors.TextSecondary,
            )
            Text(
                text = "$hostLabel · HappyTV remembers your answer for this server",
                style = typography.caption.tabular(),
                color = HappyTvColors.TextTertiary,
                maxLines = 1,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(d.px(BUTTON_GAP_PX))) {
            TvPrimaryButton(label = "Use HTTPS", onClick = onUseHttps, focusRequester = handles.useHttps)
            TvSecondaryButton(label = "Continue anyway", onClick = onContinueAnyway, focusRequester = handles.continueAnyway)
            TvSecondaryButton(label = "Cancel", onClick = onCancel, focusRequester = handles.cancel)
        }
    }
}

/** Focus handles owned by the screen: Use HTTPS takes focus on open. */
internal class CleartextSheetHandles {
    val useHttps = FocusRequester()
    val continueAnyway = FocusRequester()
    val cancel = FocusRequester()
}

// Handoff px constants for the unencrypted-server sheet.
private const val SHEET_WIDTH_PX = 1728
private const val SHEET_SHADOW_PX = 24
private const val SHEET_SHADOW_ALPHA = 0.50f
private const val SHEET_CORNER_PX = 32
private const val PADDING_HORIZONTAL_PX = 64
private const val PADDING_TOP_PX = 56
private const val PADDING_BOTTOM_PX = 86
private const val SECTION_GAP_PX = 40
private const val TITLE_GAP_PX = 16
private const val BUTTON_GAP_PX = 24
