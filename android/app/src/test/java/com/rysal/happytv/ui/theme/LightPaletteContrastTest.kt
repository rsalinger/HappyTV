package com.rysal.happytv.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #126: the light palette's contrast, pinned with WCAG 2.1 relative luminance. */
class LightPaletteContrastTest {

    private val p = LightPalette
    private val surfaces = mapOf("Ground" to p.Ground, "Surface" to p.Surface, "Raised" to p.Raised, "Overlay" to p.Overlay)

    @After
    fun resetMode() {
        HappyTvThemeState.light = false
    }

    private fun assertAtLeast(min: Double, label: String, fg: Color, bg: Color) {
        val ratio = contrast(fg, bg)
        assertTrue("$label is %.2f:1, needs $min:1".format(ratio), ratio >= min)
    }

    @Test
    fun `primary and secondary text read 4_5 to 1 on every surface`() {
        for ((name, bg) in surfaces) {
            assertAtLeast(4.5, "TextPrimary on $name", p.TextPrimary, bg)
            assertAtLeast(4.5, "TextSecondary on $name", p.TextSecondary, bg)
        }
    }

    @Test
    fun `tertiary and placeholder text read 4_5 to 1 on every surface`() {
        for ((name, bg) in surfaces) {
            assertAtLeast(4.5, "TextTertiary on $name", p.TextTertiary, bg)
            assertAtLeast(4.5, "TextPlaceholder on $name", p.TextPlaceholder, bg)
        }
    }

    @Test
    fun `the focus ring reads 3 to 1 on every surface`() {
        for ((name, bg) in surfaces) assertAtLeast(3.0, "Accent ring on $name", p.Accent, bg)
    }

    @Test
    fun `the ring on an accent fill reads on the surfaces around it`() {
        // Accent-filled buttons get TextPrimary as their ring (F-33).
        for ((name, bg) in surfaces) assertAtLeast(3.0, "TextPrimary ring on $name", p.TextPrimary, bg)
        HappyTvThemeState.light = true
        assertEquals(p.TextPrimary, HappyTvColors.focusRingOn(HappyTvColors.Accent))
    }

    @Test
    fun `on-accent text reads 4_5 to 1 on the accent`() {
        assertAtLeast(4.5, "OnAccent on Accent", p.OnAccent, p.Accent)
    }

    @Test
    fun `state colors read 4_5 to 1 on the main surfaces and their tints`() {
        val states = mapOf("Success" to p.StateSuccess, "Error" to p.StateError, "Stale" to p.StateStale)
        for ((n, c) in states) {
            for ((name, bg) in surfaces - "Overlay") assertAtLeast(4.5, "$n on $name", c, bg)
        }
        assertAtLeast(4.5, "Error on its tint", p.StateError, p.StateErrorTint)
        assertAtLeast(4.5, "Stale on its tint", p.StateStale, p.StateStaleTint)
    }

    @Test
    fun `accent keeps the sunset hue`() {
        fun hue(c: Color): Float {
            val max = maxOf(c.red, c.green, c.blue)
            val min = minOf(c.red, c.green, c.blue)
            val d = max - min
            return 60f * (((c.green - c.blue) / d) % 6f)
        }
        assertEquals(hue(DarkPalette.Accent).toDouble(), hue(p.Accent).toDouble(), 6.0)
    }

    @Test
    fun `dark is the default and its values are unchanged`() {
        HappyTvThemeState.light = false
        assertEquals(Color(0xFF13100E), HappyTvColors.Ground)
        assertEquals(Color(0xFFFFF1E2), HappyTvColors.TextPrimary)
        assertEquals(Color(0xFFFF7A45), HappyTvColors.Accent)
        assertEquals(Color(0xFF1A0F0A), HappyTvColors.OnAccent)
        assertEquals(Color(0xFF93877C), HappyTvColors.TextTertiary)
    }

    @Test
    fun `switching the state flips the tokens at once`() {
        HappyTvThemeState.light = true
        assertEquals(LightPalette.Ground, HappyTvColors.Ground)
        assertFalse(HappyTvColors.Ground == DarkPalette.Ground)
        HappyTvThemeState.light = false
        assertEquals(DarkPalette.Ground, HappyTvColors.Ground)
    }

    @Test
    fun `the dark set ignores the mode and the over-artwork tokens do not change`() {
        HappyTvThemeState.light = true
        assertEquals(DarkPalette.TextPrimary, HappyTvDarkColors.TextPrimary)
        assertEquals(DarkPalette.Accent, HappyTvDarkColors.Accent)
        assertEquals(HappyTvDarkColors.PosterBadge, HappyTvColors.PosterBadge)
        assertEquals(HappyTvDarkColors.PosterStripEnd, HappyTvColors.PosterStripEnd)
        assertEquals(Color(0x99080605), HappyTvDarkColors.Scrim)
        assertEquals(Color(0x8C080605), HappyTvDarkColors.ScrimMenu)
        assertNotEquals(HappyTvDarkColors.Ground, HappyTvColors.Ground)
    }

    @Test
    fun `scrims are a cream wash in light mode and the old dark scrims in dark mode`() {
        HappyTvThemeState.light = true
        assertEquals(LightPalette.Ground.copy(alpha = 0xA3 / 255f), HappyTvColors.Scrim)
        assertEquals(LightPalette.Ground.copy(alpha = 0x8C / 255f), HappyTvColors.ScrimMenu)
        HappyTvThemeState.light = false
        assertEquals(Color(0x99080605), HappyTvColors.Scrim)
        assertEquals(Color(0x8C080605), HappyTvColors.ScrimMenu)
    }

    @Test
    fun `tokens kt light values match the palette`() {
        assertEquals(Tokens.LightGround, p.Ground)
        assertEquals(Tokens.LightSurface, p.Surface)
        assertEquals(Tokens.LightRaised, p.Raised)
        assertEquals(Tokens.LightOverlay, p.Overlay)
        assertEquals(Tokens.LightLine, p.Line)
        assertEquals(Tokens.LightTextPrimary, p.TextPrimary)
        assertEquals(Tokens.LightTextSecondary, p.TextSecondary)
        assertEquals(Tokens.LightTextTertiary, p.TextTertiary)
        assertEquals(Tokens.LightTextOnAccent, p.OnAccent)
        assertEquals(Tokens.LightAccent, p.Accent)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
