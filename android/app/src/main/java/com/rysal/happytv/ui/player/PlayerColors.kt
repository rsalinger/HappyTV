package com.rysal.happytv.ui.player

import androidx.compose.ui.graphics.Color

/**
 * The player's translucent layers — design spec §"Color > Translucent layers" and
 * the per-mode table. Solid surfaces/text stay on [HappyTvDarkColors]; everything
 * that only exists over video lives here.
 */
object PlayerColors {

    /** rgba(28, 24, 21, 0.90) — round control buttons, Now/Next card, movie badge. */
    val Card: Color = Color(0xE61C1815)

    /** The scrims' black at [alpha] (#131); stops and alphas live in [PlayerScrim]. */
    fun scrim(alpha: Float): Color = Color(0f, 0f, 0f, alpha)

    /** rgba(255, 255, 255, 0.20) — scrubber track. */
    val ScrubberTrack: Color = Color(0x33FFFFFF)

    /** rgba(255, 255, 255, 0.32) — the buffered part of the scrubber. */
    val ScrubberBuffered: Color = Color(0x52FFFFFF)

    /** Cream (#FFF1E2) — movie + replay scrubber dot and the replay live-edge marker. */
    val Cream: Color = Color(0xFFFFF1E2)

    /** The player black — behind video; same as `Color.Black`, named for parity. */
    val VideoBlack: Color = Color(0xFF000000)

    /** rgba(255, 255, 255, 0.14) — buffering spinner track (player value). */
    val SpinnerTrack: Color = Color(0x24FFFFFF)

    /** rgba(242, 102, 107, 0.18) — the Live badge's red tint fill. */
    val LiveBadgeTint: Color = Color(0x2EF2666B)

    /** The pulsing Live dot / Go-live pill dot. */
    val LiveDot: Color = com.rysal.happytv.ui.theme.HappyTvDarkColors.StateError
}

/**
 * Channel monogram placeholder swatches — design spec §"Placeholder colors":
 * seven background/letter pairs, picked by a stable hash of the channel name
 * so a channel keeps its color everywhere (browse rows, player top block).
 */
data class MonogramPalette(val background: Color, val letters: Color)

object PlayerMonograms {

    private val PALETTES = listOf(
        MonogramPalette(Color(0xFF2F4058), Color(0xFFCFE0F5)),
        MonogramPalette(Color(0xFF2E4A3A), Color(0xFFCDEBD8)),
        MonogramPalette(Color(0xFF5A3A4E), Color(0xFFF3D3E6)),
        MonogramPalette(Color(0xFF5A4630), Color(0xFFF5E1C4)),
        MonogramPalette(Color(0xFF3D3A5C), Color(0xFFDAD6F7)),
        MonogramPalette(Color(0xFF5A3333), Color(0xFFF5D0D0)),
        MonogramPalette(Color(0xFF2F4F52), Color(0xFFCDEDEF)),
    )

    /** First letters of the first two words ("Northbay News" -> "NN"). */
    fun initials(name: String): String {
        val parts = name.split(' ', '-', '_').filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
            else -> (parts.firstOrNull()?.take(2) ?: "??").uppercase()
        }
    }

    fun paletteFor(name: String): MonogramPalette {
        val hash = name.hashCode().let { if (it < 0) -it else it }
        return PALETTES[hash % PALETTES.size]
    }
}
