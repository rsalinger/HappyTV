package com.rysal.happytv.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import com.rysal.happytv.ui.browse.HtSecondaryButton
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.browse.htvClickableSurface
import com.rysal.happytv.ui.focus.requestWhenAttached
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.tabular
import kotlinx.coroutines.launch

/*
 * Long text on the details page (#93): the title that gets the width, the plot that opens in full,
 * and the episode's own description under the poster.
 */

/**
 * The title across the text column, to the right safe edge: up to two lines at [base]; when that
 * still overflows it steps down to the heading size and may use three lines before it ends in an
 * ellipsis.
 */
@Composable
internal fun DetailsTitle(text: String, base: TextStyle, modifier: Modifier = Modifier) {
    var smaller by remember(text, base) { mutableStateOf(false) }
    Text(
        text = text,
        style = if (smaller) browseType().heading else base,
        color = HappyTvColors.TextPrimary,
        maxLines = if (smaller) TITLE_SMALL_LINES else TITLE_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!smaller && it.hasVisualOverflow) smaller = true },
        modifier = modifier,
    )
}

internal const val TITLE_LINES = 2
internal const val TITLE_SMALL_LINES = 3

/**
 * A plot. When [maxLines] cut it short it becomes a focus stop (ring like the other focusables)
 * and OK calls [onOpen]; when it fits it is plain text. The padding is always there, so the text
 * does not move when it turns focusable, and [bleedPx] pulls the padded box out to the left so
 * the text keeps its column.
 */
@Composable
internal fun PlotText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    focusRequester: FocusRequester,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    bleedPx: Int = 0,
) {
    val d = browseDesign()
    var truncated by remember(text, maxLines) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .offset(x = -d.px(bleedPx))
            .then(if (truncated) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (truncated) {
                    Modifier.htvClickableSurface(radiusPx = PLOT_RADIUS_PX, background = Color.Transparent, onClick = onOpen)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = d.px(PLOT_PAD_X_PX), vertical = d.px(PLOT_PAD_Y_PX)),
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { truncated = it.hasVisualOverflow },
        )
    }
}

internal const val PLOT_PAD_X_PX = 12
internal const val PLOT_PAD_Y_PX = 8
private const val PLOT_RADIUS_PX = 12

/**
 * The full plot over the page: a Raised card in the guide details dialog's style, the text, and a
 * Close button that holds focus. Up and Down scroll a long text; Left and Right stay put; Back
 * closes. [onClosed] runs after it goes, so the page can put focus back on the plot.
 */
@Composable
internal fun PlotDialog(title: String, plot: String, onDismiss: () -> Unit) {
    val d = browseDesign()
    val type = browseType()
    val close = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { d.px(PLOT_SCROLL_STEP_PX).toPx() }
    LaunchedEffect(Unit) { close.requestWhenAttached() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.Back -> {
                        if (event.type == KeyEventType.KeyDown) onDismiss()
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) {
                            scope.launch {
                                scroll.animateScrollBy(if (event.key == Key.DirectionDown) stepPx else -stepPx)
                            }
                        }
                        true
                    }
                    // The page behind stays where it is.
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            }
            .background(HappyTvColors.Scrim),
    ) {
        Column(
            modifier = Modifier
                .offset(d.px(PLOT_DIALOG_X_PX), d.px(PLOT_DIALOG_Y_PX))
                .width(d.px(PLOT_DIALOG_WIDTH_PX))
                .background(HappyTvColors.Raised, RoundedCornerShape(d.px(24)))
                .padding(start = d.px(48), top = d.px(40), end = d.px(48), bottom = d.px(40)),
            verticalArrangement = Arrangement.spacedBy(d.px(20)),
        ) {
            Text(
                text = title,
                style = type.compactHeading,
                color = HappyTvColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = plot,
                style = type.compactBody,
                color = HappyTvColors.TextSecondary,
                modifier = Modifier
                    .heightIn(max = d.px(PLOT_DIALOG_TEXT_MAX_PX))
                    .verticalScroll(scroll),
            )
            HtSecondaryButton(label = "Close", onClick = onDismiss, focusRequester = close)
        }
    }
}

private const val PLOT_DIALOG_X_PX = 480
private const val PLOT_DIALOG_Y_PX = 140
private const val PLOT_DIALOG_WIDTH_PX = 960
private const val PLOT_DIALOG_TEXT_MAX_PX = 520
private const val PLOT_SCROLL_STEP_PX = 120

/**
 * What the area under the poster shows while focus is in the episode list: "S1 E3 · Title" with
 * its runtime, then the description. Room is about 250 px, so the description takes five lines.
 */
@Composable
internal fun EpisodeNote(episode: EpisodeItem, modifier: Modifier = Modifier) {
    val d = browseDesign()
    val type = browseType()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(d.px(6))) {
        Text(
            text = buildAnnotatedString {
                append("${episode.label} · ${episode.title}")
                episode.runtimeLabel?.let {
                    withStyle(SpanStyle(color = HappyTvColors.TextTertiary)) { append("   $it") }
                }
            },
            style = type.compactCaptionSemibold.tabular(),
            color = HappyTvColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = episode.plot ?: "No description from the provider.",
            style = type.compactCaption,
            color = if (episode.plot != null) HappyTvColors.TextSecondary else HappyTvColors.TextTertiary,
            maxLines = EPISODE_NOTE_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal const val EPISODE_NOTE_LINES = 5
