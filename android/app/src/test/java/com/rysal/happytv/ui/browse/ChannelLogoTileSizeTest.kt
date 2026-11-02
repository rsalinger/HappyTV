package com.rysal.happytv.ui.browse

import android.app.Application
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The tile must not depend on the size a logo is decoded at (#139, QA of #140). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ChannelLogoTileSizeTest {
    private val transparent = 0
    private val white = 0xFFFFFFFF.toInt()
    private val pink = 0xFFD96A8A.toInt()

    /** A white wordmark (thin vertical strokes, about 30% of the frame) on transparent, at [w] x [h]. */
    private fun wordmark(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = (i % w).toFloat() / w
        val y = (i / w).toFloat() / h
        val inBand = x in 0.15f..0.85f && y in 0.35f..0.65f
        val stroke = ((x * 20).toInt() % 2) == 0
        if (inBand && stroke) white else transparent
    }

    /** A full-frame logo: pink background with a white block in the middle. */
    private fun fullFrame(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = (i % w).toFloat() / w
        val y = (i / w).toFloat() / h
        if (x in 0.3f..0.7f && y in 0.3f..0.7f) white else pink
    }

    private fun bitmap(px: IntArray, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }

    @Test
    fun `a white wordmark on transparent gets the dark tile at every size`() {
        for ((w, h) in listOf(64 to 43, 72 to 48, 168 to 112, 448 to 252)) {
            assertEquals("pick at ${w}x$h", ChannelLogoTile.DARK, ChannelLogoTile.pick(wordmark(w, h), w, h))
            assertEquals("of at ${w}x$h", ChannelLogoTile.DARK, ChannelLogoTile.of(bitmap(wordmark(w, h), w, h)))
        }
    }

    @Test
    fun `a full-frame logo gets the same tile at every size`() {
        val small = ChannelLogoTile.of(bitmap(fullFrame(64, 43), 64, 43))
        val large = ChannelLogoTile.of(bitmap(fullFrame(448, 252), 448, 252))
        assertEquals(pink, small)
        assertEquals(small, large)
        assertNotEquals(ChannelLogoTile.LIGHT, large)
    }
}
