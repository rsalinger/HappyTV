package com.rysal.happytv.ui.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.rysal.happytv.ui.browse.browseDesign
import com.rysal.happytv.ui.browse.browseType
import com.rysal.happytv.ui.theme.HappyTvColors
import com.rysal.happytv.ui.theme.tabular

/*
 * Poster captions and the quality badge (#93), shared by the Movies / Shows grid and the search
 * poster rows so the two read the same.
 */

/** One caption line keeps the rows compact; a focused caption scrolls (see [focusMarquee]) so the whole name can be read. */
internal const val CAPTION_LINES = 1

/** Compact caption line height (24/32) times [CAPTION_LINES]. */
internal const val CAPTION_HEIGHT_PX = 32 * CAPTION_LINES

/** Gap between a poster's art and its caption. */
internal const val CAPTION_GAP_PX = 8

/** The caption's text: the title, then the year after it in Text secondary. */
internal fun posterCaptionText(
    title: String,
    year: String?,
    yearColor: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    append(title)
    if (!year.isNullOrBlank()) {
        append("  ")
        withStyle(SpanStyle(color = yearColor, fontWeight = FontWeight.W500)) { append(year) }
    }
}

/**
 * A poster's caption: sits under the art on the screen background, up to two lines, year after
 * the title. Always two lines tall, so a row of posters keeps one height whatever the titles.
 */
@Composable
internal fun PosterCaption(title: String, year: String?, focused: Boolean = false, modifier: Modifier = Modifier) {
    val type = browseType()
    val secondary = HappyTvColors.TextSecondary
    val text = remember(title, year, secondary) { posterCaptionText(title, year, secondary) }
    Text(
        text = text,
        style = type.compactCaptionSemibold.tabular(),
        color = HappyTvColors.TextPrimary,
        maxLines = CAPTION_LINES,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = modifier.focusMarquee(focused),
    )
}

/**
 * While [focused], a one-line text that is wider than its box scrolls (after about a second, at a
 * walking pace) so the whole name can be read. Size and position never change; at rest the text
 * ends in an ellipsis.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.focusMarquee(focused: Boolean): Modifier =
    if (focused) basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1000, velocity = 40.dp) else this

/** "4K" and the like: Overlay surface, caption style, tabular. Sits on a poster's top-left corner. */
@Composable
internal fun QualityBadge(label: String, modifier: Modifier = Modifier) {
    val d = browseDesign()
    Box(
        modifier = modifier
            .height(d.px(32))
            .background(HappyTvColors.Overlay, RoundedCornerShape(d.px(8)))
            .padding(horizontal = d.px(10)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = browseType().compactCaptionSemibold.tabular(),
            color = HappyTvColors.TextPrimary,
            maxLines = 1,
        )
    }
}
