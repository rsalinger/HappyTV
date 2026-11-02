package com.rysal.happytv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelLogoTileTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** A transparent frame with [mark] in the middle [markShare] of its pixels. */
    private fun frame(mark: Int, markShare: Float, size: Int = 16): IntArray {
        val pixels = IntArray(size * size)
        val n = (pixels.size * markShare).toInt()
        for (i in 0 until n) pixels[pixels.size / 2 - n / 2 + i] = mark
        return pixels
    }

    @Test
    fun `a white mark on transparent sits on the dark tile`() {
        val px = frame(argb(255, 255, 255, 255), 0.3f)
        assertEquals(ChannelLogoTile.DARK, ChannelLogoTile.pick(px, 16, 16))
    }

    @Test
    fun `a black mark on transparent sits on the light tile`() {
        val px = frame(argb(255, 0, 0, 0), 0.3f)
        assertEquals(ChannelLogoTile.LIGHT, ChannelLogoTile.pick(px, 16, 16))
    }

    @Test
    fun `a mid red mark sits on the dark tile`() {
        val px = frame(argb(255, 200, 30, 40), 0.3f)
        assertEquals(ChannelLogoTile.DARK, ChannelLogoTile.pick(px, 16, 16))
    }

    @Test
    fun `an empty or invisible image defaults to the light tile`() {
        assertEquals(ChannelLogoTile.LIGHT, ChannelLogoTile.pick(IntArray(0), 0, 0))
        assertEquals(ChannelLogoTile.LIGHT, ChannelLogoTile.pick(IntArray(256), 16, 16))
    }

    @Test
    fun `a fully opaque logo sits on its own corner colour`() {
        val blue = argb(255, 10, 20, 200)
        val px = IntArray(256) { blue }
        assertEquals(blue, ChannelLogoTile.pick(px, 16, 16))
    }

    private val sizes = listOf(64 to 43, 448 to 252)
    private val white = argb(255, 255, 255, 255)
    private val black = argb(255, 0, 0, 0)
    private val red = argb(255, 215, 30, 45)

    /** [draw] gets unit coordinates (0..1) and returns the pixel there; transparent outside the mark. */
    private fun render(w: Int, h: Int, draw: (Float, Float) -> Int) =
        IntArray(w * h) { draw((it % w).toFloat() / w, (it / w).toFloat() / h) }

    private fun assertTileAtBothSizes(expected: Int, draw: (Float, Float) -> Int) {
        for ((w, h) in sizes) {
            assertEquals("${w}x$h", expected, ChannelLogoTile.pick(render(w, h, draw), w, h))
        }
    }

    @Test
    fun `a red mark with a white wordmark is dark`() = assertTileAtBothSizes(ChannelLogoTile.DARK) { x, y ->
        when {
            x in 0.35f..0.65f && y in 0.05f..0.55f -> red
            x in 0.1f..0.9f && y in 0.7f..0.85f && ((x * 24).toInt() % 2 == 0) -> white
            else -> 0
        }
    }

    @Test
    fun `a black disc with white text is light`() = assertTileAtBothSizes(ChannelLogoTile.LIGHT) { x, y ->
        val dx = x - 0.5f
        val dy = (y - 0.5f) * 1.5f
        when {
            dx * dx + dy * dy > 0.2f -> 0
            x in 0.3f..0.7f && y in 0.42f..0.58f && ((x * 20).toInt() % 2 == 0) -> white
            else -> black
        }
    }

    @Test
    fun `a plain black wordmark is light`() = assertTileAtBothSizes(ChannelLogoTile.LIGHT) { x, y ->
        if (x in 0.1f..0.9f && y in 0.35f..0.65f && ((x * 20).toInt() % 2 == 0)) black else 0
    }

    @Test
    fun `a plain white wordmark is dark`() = assertTileAtBothSizes(ChannelLogoTile.DARK) { x, y ->
        if (x in 0.1f..0.9f && y in 0.35f..0.65f && ((x * 20).toInt() % 2 == 0)) white else 0
    }

    @Test
    fun `a saturated colour mark alone is dark`() = assertTileAtBothSizes(ChannelLogoTile.DARK) { x, y ->
        if (x in 0.2f..0.8f && y in 0.2f..0.8f) red else 0
    }
}
