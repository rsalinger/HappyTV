package com.rysal.happytv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.LocalHappyTvDesign
import com.rysal.happytv.ui.theme.LocalHappyTvTypography
import com.rysal.happytv.ui.theme.happyTvFocusRing
import com.rysal.happytv.ui.theme.tabular

/**
 * "Common URL formats" — design spec §"Sign in" and the `formats` scenario of
 * `Login.dc.html`.
 *
 * Scrim 0.60 (drawn by the caller, so Back can dismiss); the panel sits at
 * top 96, centered, 1040 wide, on `raised` with the handoff's dialog shadow.
 * Picking an option fills Server URL, closes the dialog and returns focus to
 * the field; Close returns focus to the link that opened it.
 */
internal data class UrlFormatOption(val url: String, val note: String)

internal val UrlFormatOptions = listOf(
    UrlFormatOption(
        url = "http://provider.example:8080",
        note = "The most common. The port number comes after the colon.",
    ),
    UrlFormatOption(url = "http://provider.example", note = "No port"),
    UrlFormatOption(url = "https://provider.example:8443", note = "Encrypted, with a port"),
)

/** Focus handles owned by the screen, so the dialog can land focus on open. */
internal class UrlFormatsDialogHandles {
    val options = List(UrlFormatOptions.size) { FocusRequester() }
    val firstOption: FocusRequester get() = options.first()
    val close = FocusRequester()
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun UrlFormatsDialogPanel(
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    handles: UrlFormatsDialogHandles,
    modifier: Modifier = Modifier,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val shape = RoundedCornerShape(d.px(DIALOG_CORNER_PX))

    Column(
        modifier = modifier
            .width(d.px(DIALOG_WIDTH_PX))
            // #164: shadow before fill and clip (see LoginScreen's card).
            .shadow(
                elevation = d.px(DIALOG_SHADOW_PX),
                shape = shape,
                clip = false,
                ambientColor = HappyTvColors.shadow(DIALOG_SHADOW_ALPHA),
                spotColor = HappyTvColors.shadow(DIALOG_SHADOW_ALPHA),
            )
            .clip(shape)
            .background(HappyTvColors.Raised)
            // #161: the D-pad cannot leave the dialog whatever sits behind it. The screen also
            // makes the form unfocusable; this is the dialog's own guarantee. (Not
            // `canFocus = false`: that propagates to the buttons, see F-66.)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
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
                text = "Common URL formats",
                style = typography.heading,
                color = HappyTvColors.TextPrimary,
            )
            Text(
                text = "Your provider’s welcome email has the exact address. Most look like one of these.",
                style = typography.body,
                color = HappyTvColors.TextSecondary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(d.px(OPTION_GAP_PX))) {
            UrlFormatOptions.forEachIndexed { index, option ->
                UrlFormatOptionButton(
                    option = option,
                    onClick = { onPick(option.url) },
                    focusRequester = handles.options[index],
                    up = handles.options.getOrNull(index - 1) ?: FocusRequester.Cancel,
                    down = handles.options.getOrNull(index + 1) ?: handles.close,
                )
            }
        }

        Text(
            text = "You can leave off https://. HappyTV uses an encrypted connection unless you type http://.",
            style = typography.caption,
            color = HappyTvColors.TextTertiary,
        )

        TvSecondaryButton(
            label = "Close",
            onClick = onClose,
            focusRequester = handles.close,
            modifier = Modifier
                .align(Alignment.Start)
                .focusProperties {
                    up = handles.options.last()
                    down = FocusRequester.Cancel
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                },
        )
    }
}

/** One option — 120 tall, radius 24, overlay; a tabular URL over a note. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun UrlFormatOptionButton(
    option: UrlFormatOption,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    up: FocusRequester,
    down: FocusRequester,
) {
    val d = LocalHappyTvDesign.current
    val typography = LocalHappyTvTypography.current
    val radius = d.px(OPTION_RADIUS_PX)
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .height(d.px(OPTION_HEIGHT_PX))
            .fillMaxWidth()
            // Ring first — a clip above it would erase the ring and the glow
            // (F-33).
            .happyTvFocusRing(focused = focused, radius = radius)
            .clip(RoundedCornerShape(radius))
            .background(HappyTvColors.Overlay)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                this.up = up
                this.down = down
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = d.px(OPTION_PADDING_PX)),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = option.url, style = typography.bodyBold.tabular(), color = HappyTvColors.TextPrimary, maxLines = 1)
        Text(text = option.note, style = typography.caption, color = HappyTvColors.TextSecondary, maxLines = 1)
    }
}

// Handoff px constants for the URL formats dialog.
private const val DIALOG_WIDTH_PX = 1040
private const val DIALOG_SHADOW_PX = 40
private const val DIALOG_SHADOW_ALPHA = 0.55f
private const val DIALOG_CORNER_PX = 32
private const val PADDING_HORIZONTAL_PX = 64
private const val PADDING_TOP_PX = 56
private const val PADDING_BOTTOM_PX = 64
private const val SECTION_GAP_PX = 32
private const val TITLE_GAP_PX = 12
private const val OPTION_GAP_PX = 12
private const val OPTION_HEIGHT_PX = 120
private const val OPTION_RADIUS_PX = 24
private const val OPTION_PADDING_PX = 32
