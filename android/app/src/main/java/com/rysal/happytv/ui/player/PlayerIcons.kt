package com.rysal.happytv.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The player's icon set — the six drawings from design spec §"Interface icons"
 * that the theme's [com.rysal.happytv.ui.theme.HappyTvIcons] doesn't carry yet
 * (play, pause, subtitles, star outline/filled). Same
 * house rules: 24 × 24 grid, 1.5 stroke, square line ends, 2-unit corners;
 * filled shapes are filled so a tint colors them whole.
 *
 * The info and alert icons come from [com.rysal.happytv.ui.theme.HappyTvIcons]
 * — they already exist there and the player uses them as-is.
 */
object PlayerIcons {

    /** Play — filled triangle (40 px in the control button). */
    val Play: ImageVector by lazy {
        filled("HappyTV Play") {
            // Triangle nudged right of center so it reads optically centered.
            moveTo(8.5f, 5.5f)
            lineTo(19f, 12f)
            lineTo(8.5f, 18.5f)
            close()
        }
    }

    /** Pause — two filled bars (40 px). */
    val Pause: ImageVector by lazy {
        filled("HappyTV Pause") {
            roundedRect(6.5f, 5f, 4f, 14f, 1.5f)
            roundedRect(13.5f, 5f, 4f, 14f, 1.5f)
        }
    }

    /** Audio and subtitles — rectangle with caption lines (40 px track button). */
    val Subtitles: ImageVector by lazy {
        stroked("HappyTV Subtitles", strokeWidth = 1.5f) {
            roundedRect(2.75f, 4.75f, 18.5f, 14.5f, 2f)
            moveTo(6.5f, 12.5f)
            lineTo(11.5f, 12.5f)
            moveTo(13.5f, 12.5f)
            lineTo(17.5f, 12.5f)
            moveTo(6.5f, 16f)
            lineTo(10f, 16f)
            moveTo(12f, 16f)
            lineTo(17.5f, 16f)
        }
    }

    /** Favorite, off — star outline (24–56 px everywhere favorites appear). */
    val StarOutline: ImageVector by lazy {
        stroked("HappyTV Star", strokeWidth = 1.5f) {
            star()
        }
    }

    /** Favorite, on — filled star, tinted accent at every call site. */
    val StarFilled: ImageVector by lazy {
        filled("HappyTV Star filled") {
            star()
        }
    }

    private fun PathBuilder.star() {
        moveTo(12f, 3.5f)
        lineTo(14.55f, 9.1f)
        lineTo(20.6f, 9.75f)
        lineTo(16.1f, 13.85f)
        lineTo(17.35f, 19.85f)
        lineTo(12f, 16.75f)
        lineTo(6.65f, 19.85f)
        lineTo(7.9f, 13.85f)
        lineTo(3.4f, 9.75f)
        lineTo(9.45f, 9.1f)
        close()
    }

    private fun filled(
        name: String,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            stroke = null,
            pathBuilder = block,
        )
    }.build()

    private fun stroked(
        name: String,
        strokeWidth: Float,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

    /** `<rect rx>` equivalent — lines joined by corner arcs. */
    private fun PathBuilder.roundedRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        r: Float,
        fill: Boolean = false,
    ) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x + w, y + r)
        lineTo(x + w, y + h - r)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x + w - r, y + h)
        lineTo(x + r, y + h)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x, y + h - r)
        lineTo(x, y + r)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x + r, y)
        close()
    }
}
