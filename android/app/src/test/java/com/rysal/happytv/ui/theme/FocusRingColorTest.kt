package com.rysal.happytv.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-33: the focus ring must never be drawn in the colour of the fill it
 * frames, and the ring it does draw has to read on a dark TV screen in a
 * dark room (3 m viewing distance).
 */
class FocusRingColorTest {

    @Test
    fun `an accent fill never gets an accent ring`() {
        // The F-33 bug itself: Sign in drew an Accent ring on an Accent fill.
        assertNotEquals(HappyTvColors.Accent, HappyTvColors.focusRingOn(HappyTvColors.Accent))
        assertEquals(HappyTvColors.TextPrimary, HappyTvColors.focusRingOn(HappyTvColors.Accent))
    }

    @Test
    fun `every other fill keeps the accent ring`() {
        assertEquals(HappyTvColors.Accent, HappyTvColors.focusRingOn(HappyTvColors.Overlay))
        assertEquals(HappyTvColors.Accent, HappyTvColors.focusRingOn(HappyTvColors.Surface))
        assertEquals(HappyTvColors.Accent, HappyTvColors.focusRingOn(HappyTvColors.Raised))
        assertEquals(HappyTvColors.Accent, HappyTvColors.focusRingOn(Color.Transparent))
    }

    @Test
    fun `the ring reads on every background it can land on`() {
        val ring = HappyTvColors.focusRingOn(HappyTvColors.Accent)
        // The ring is drawn just outside the button, i.e. over the screen
        // surfaces and the accent glow — 7:1 is AAA at any distance.
        assertTrue("Ground: ${contrast(ring, HappyTvColors.Ground)}", contrast(ring, HappyTvColors.Ground) >= 7.0)
        assertTrue("Surface: ${contrast(ring, HappyTvColors.Surface)}", contrast(ring, HappyTvColors.Surface) >= 7.0)
        assertTrue("Raised: ${contrast(ring, HappyTvColors.Raised)}", contrast(ring, HappyTvColors.Raised) >= 7.0)
        assertTrue(
            "accent glow: ${contrast(ring, accentGlowOverGround())}",
            contrast(ring, accentGlowOverGround()) >= 4.5,
        )
    }
}

/** WCAG 2.1 relative luminance. */
private fun luminance(color: Color): Double {
    fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}

/** WCAG 2.1 contrast ratio, 1..21. */
private fun contrast(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** AccentGlow composited over Ground — what sits behind a focused pill. */
private fun accentGlowOverGround(): Color {
    val glow = HappyTvColors.AccentGlow
    val ground = HappyTvColors.Ground
    val a = glow.alpha
    return Color(
        red = glow.red * a + ground.red * (1f - a),
        green = glow.green * a + ground.green * (1f - a),
        blue = glow.blue * a + ground.blue * (1f - a),
    )
}
