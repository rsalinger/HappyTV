package com.rysal.happytv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * HappyTV's 24 x 24 line icons, drawn from design spec's appendix ("Appendix:
 * interface icon SVG paths") so the app matches the handoff exactly rather
 * than borrowing Material Symbols.
 *
 * House rules from the handoff: one stroke weight (1.5 at large sizes, 1.75
 * small, 2 for the check), square line ends, 2-unit corners. The vectors are
 * baked black and tinted at the call site through `Icon(imageVector, tint)`,
 * which is how the handoff's "colored by the text they sit with" works.
 *
 * More icons land here as the other screens need them (the dashboard task adds
 * refresh/info next).
 */
object HappyTvIcons {

    /** Eye — "Hold OK to show password" on the sign-in form (44 px, stroke 1.75). */
    val Eye: ImageVector by lazy { eye(slashed = false) }

    /** Eye with the slash — used when the password is revealed. */
    val EyeSlashed: ImageVector by lazy { eye(slashed = true) }

    /**
     * Alert — circle and exclamation. Always paired with error text (handoff
     * "State colors"): the 32 px icon on the sign-in status line and under
     * invalid fields.
     */
    val Alert: ImageVector by lazy {
        icon("HappyTV Alert", strokeWidth = 1.75f) {
            moveTo(12f, 3.25f)
            arcTo(8.75f, 8.75f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 20.75f)
            arcTo(8.75f, 8.75f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 3.25f)
            close()
            moveTo(12f, 7.5f)
            verticalLineTo(13f)
            moveTo(12f, 16.25f)
            verticalLineTo(16.75f)
        }
    }

    /**
     * Check — the one icon the handoff strokes at 2. Selected saved server
     * (40 px, accent) and the checked checkbox (32 px, on accent).
     */
    val Check: ImageVector by lazy {
        icon("HappyTV Check", strokeWidth = 2f) {
            moveTo(5f, 12.5f)
            lineTo(9.5f, 17f)
            lineTo(19f, 7.5f)
        }
    }

    /** Live TV — screen with rabbit-ear antenna and a stand (96 px tile icon). */
    val LiveTv: ImageVector by lazy {
        icon("HappyTV LiveTv", strokeWidth = 1.5f) {
            roundedRect(2.75f, 6.75f, 18.5f, 12.5f, 2f)
            moveTo(8.5f, 2.75f)
            lineTo(12f, 6.25f)
            lineTo(15.5f, 2.75f)
            moveTo(9f, 22.25f)
            lineTo(15f, 22.25f)
        }
    }

    /** Movies — film strip with sprocket marks (96 px tile icon). */
    val Movie: ImageVector by lazy {
        icon("HappyTV Movie", strokeWidth = 1.5f) {
            roundedRect(3.75f, 2.75f, 16.5f, 18.5f, 2f)
            // Sprocket rails
            moveTo(8.25f, 2.75f)
            lineTo(8.25f, 21.25f)
            moveTo(15.75f, 2.75f)
            lineTo(15.75f, 21.25f)
            // Perforations, both sides
            moveTo(3.75f, 7.5f)
            lineTo(8.25f, 7.5f)
            moveTo(3.75f, 12f)
            lineTo(8.25f, 12f)
            moveTo(3.75f, 16.5f)
            lineTo(8.25f, 16.5f)
            moveTo(15.75f, 7.5f)
            lineTo(20.25f, 7.5f)
            moveTo(15.75f, 12f)
            lineTo(20.25f, 12f)
            moveTo(15.75f, 16.5f)
            lineTo(20.25f, 16.5f)
        }
    }

    /** Shows — screen with a play triangle and a second screen edge behind. */
    val Show: ImageVector by lazy {
        icon("HappyTV Show", strokeWidth = 1.5f) {
            roundedRect(2.75f, 7.75f, 18.5f, 13.5f, 2f)
            moveTo(5.75f, 4.25f)
            lineTo(18.25f, 4.25f)
            moveTo(10.25f, 11.5f)
            lineTo(10.25f, 18f)
            lineTo(15.25f, 14.75f)
            close()
        }
    }

    /** TV guide — rectangle divided into a grid (56 px second-row icon). */
    val Guide: ImageVector by lazy {
        icon("HappyTV Guide", strokeWidth = 1.5f) {
            roundedRect(2.75f, 3.75f, 18.5f, 16.5f, 2f)
            moveTo(2.75f, 9f)
            lineTo(21.25f, 9f)
            moveTo(2.75f, 14.5f)
            lineTo(21.25f, 14.5f)
            moveTo(9.25f, 9f)
            lineTo(9.25f, 20.25f)
        }
    }

    /** Settings — two slider lines with knobs (56 px second-row icon). */
    val Sliders: ImageVector by lazy {
        icon("HappyTV Sliders", strokeWidth = 1.5f) {
            moveTo(3.75f, 7f)
            lineTo(13.25f, 7f)
            moveTo(18.25f, 7f)
            lineTo(20.25f, 7f)
            moveTo(3.75f, 17f)
            lineTo(7.25f, 17f)
            moveTo(12.25f, 17f)
            lineTo(20.25f, 17f)
            circle(15.75f, 7f, 2.5f)
            circle(9.75f, 17f, 2.5f)
        }
    }

    /** Search — magnifying glass (40 px header pill). */
    val Search: ImageVector by lazy {
        icon("HappyTV Search", strokeWidth = 1.5f) {
            circle(10.5f, 10.5f, 6.75f)
            moveTo(15.5f, 15.5f)
            lineTo(20.5f, 20.5f)
        }
    }

    /** Refresh — clockwise arc with an arrowhead (40 px tile status pills). */
    val Refresh: ImageVector by lazy {
        icon("HappyTV Refresh", strokeWidth = 1.5f) {
            // 7.25-radius arc sweeping clockwise from ~1:30 around to ~12:30.
            moveTo(19.25f, 12f)
            arcTo(7.25f, 7.25f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.13f, 6.87f)
            moveTo(19.25f, 4.25f)
            lineTo(19.25f, 8.75f)
            lineTo(14.75f, 8.75f)
        }
    }

    /** Clock — circle with hands (40 px "Updated … ago" status). */
    val Clock: ImageVector by lazy {
        icon("HappyTV Clock", strokeWidth = 1.5f) {
            circle(12f, 12f, 8.75f)
            moveTo(12f, 7.25f)
            lineTo(12f, 12f)
            lineTo(15f, 14f)
        }
    }

    /** Info — circle with an i (40 px "About this data"). */
    val Info: ImageVector by lazy {
        icon("HappyTV Info", strokeWidth = 1.5f) {
            circle(12f, 12f, 8.75f)
            moveTo(12f, 11f)
            lineTo(12f, 16.5f)
            moveTo(12f, 7.5f)
            lineTo(12f, 8f)
        }
    }

    /** Sun — disc with eight rays (44 px dashboard switch, shown in dark mode). */
    val Sun: ImageVector by lazy {
        icon("HappyTV Sun", strokeWidth = 1.5f) {
            circle(12f, 12f, 4f)
            moveTo(12f, 2.75f)
            lineTo(12f, 5f)
            moveTo(12f, 19f)
            lineTo(12f, 21.25f)
            moveTo(2.75f, 12f)
            lineTo(5f, 12f)
            moveTo(19f, 12f)
            lineTo(21.25f, 12f)
            moveTo(5.46f, 5.46f)
            lineTo(7.05f, 7.05f)
            moveTo(16.95f, 16.95f)
            lineTo(18.54f, 18.54f)
            moveTo(5.46f, 18.54f)
            lineTo(7.05f, 16.95f)
            moveTo(16.95f, 7.05f)
            lineTo(18.54f, 5.46f)
        }
    }

    /** Moon — crescent (44 px dashboard switch, shown in light mode). */
    val Moon: ImageVector by lazy {
        icon("HappyTV Moon", strokeWidth = 1.5f) {
            moveTo(20.25f, 14.5f)
            arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.5f, 3.75f)
            arcTo(6.75f, 6.75f, 0f, isMoreThanHalf = false, isPositiveArc = false, 20.25f, 14.5f)
            close()
        }
    }

    private fun eye(slashed: Boolean): ImageVector =
        icon("HappyTV Eye", strokeWidth = 1.75f) {
            // The appendix's eye outline: M2.75 12 S … S … S … S … z
            moveTo(2.75f, 12f)
            curveTo(2.75f, 12f, 6.25f, 5.75f, 12f, 5.75f)
            curveTo(17.75f, 5.75f, 21.25f, 12f, 21.25f, 12f)
            curveTo(21.25f, 12f, 17.75f, 18.25f, 12f, 18.25f)
            curveTo(6.25f, 18.25f, 2.75f, 12f, 2.75f, 12f)
            close()
            // Pupil: <circle cx="12" cy="12" r="3"/>
            moveTo(15f, 12f)
            arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9f, 12f)
            arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15f, 12f)
            close()
            if (slashed) {
                moveTo(4f, 20f)
                lineTo(20f, 4f)
            }
        }

    private fun icon(
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

    /** `<rect rx>` equivalent — four lines joined by 2-unit-corner arcs. */
    private fun PathBuilder.roundedRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
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

    /** `<circle>` equivalent — two half-arcs. */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, cx + r, cy)
        close()
    }
}
