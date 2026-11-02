package com.rysal.happytv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * design spec §"Color > Placeholder colors" — the stand-ins for artwork the app
 * can't legally ship ("no real networks, logos or posters").
 *
 * Palettes live beside the design tokens rather than inside a screen so no
 * composable has to inline a hex: monograms and poster art pick a slot by
 * hashing the title, then stay with it forever.
 */
object HappyTvPlaceholders {

    /** Channel monograms: seven background / letter pairs, in order. */
    val MonogramPairs: List<Pair<Color, Color>> = listOf(
        Color(0xFF2F4058) to Color(0xFFCFE0F5), // blue
        Color(0xFF2E4A3A) to Color(0xFFCDEBD8), // green
        Color(0xFF5A3A4E) to Color(0xFFF3D3E6), // plum
        Color(0xFF5A4630) to Color(0xFFF5E1C4), // brown
        Color(0xFF3D3A5C) to Color(0xFFDAD6F7), // indigo
        Color(0xFF5A3333) to Color(0xFFF5D0D0), // red
        Color(0xFF2F4F52) to Color(0xFFCDEDEF), // teal
    )

    /**
     * Poster art stand-ins: the dusk/teal/plum tones the mockup's poster
     * palettes sit in (the handoff's mock content keeps them abstract).
     */
    val PosterPalette: List<Color> = listOf(
        Color(0xFF24425A),
        Color(0xFF6E6A62),
        Color(0xFFD29A5C),
        Color(0xFF1C2C3A),
        Color(0xFF3A2230),
        Color(0xFF2F4F52),
        Color(0xFF5A4630),
        Color(0xFF3D3A5C),
    )
}
