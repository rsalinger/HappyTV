package com.rysal.happytv.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #26: the glow is drawn outside the element only (it used to be an elevation shadow that
 * also darkened the inside of Settings rows and guide blocks). The "nothing inside the
 * outline" part is structural (a ClipOp.Difference of the element's outline) and needs a real
 * canvas, so it is verified on the TV; the fade is testable here.
 */
class FocusGlowTest {
    @Test
    fun `glow fades monotonically from the element outward and stays visible at the edge`() {
        val bands = 8
        val alphas = (0 until bands).map { focusGlowFalloff(it, bands) }
        assertTrue(alphas.first() in 0.8f..1f)
        assertTrue(alphas.last() in 0f..0.1f)
        assertTrue(alphas.zipWithNext().all { (a, b) -> b < a })
    }
}
