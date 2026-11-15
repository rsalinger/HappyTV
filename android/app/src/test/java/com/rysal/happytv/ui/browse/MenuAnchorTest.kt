package com.rysal.happytv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #232: the popup menu sits beside the held item, inside the safe area. */
class MenuAnchorTest {

    private val w = 600
    private val h = 200

    @Test
    fun `sits to the right of the item with a 24 px gap, top edge level`() {
        val at = placeMenuBeside(ArtRect(96, 300, 296, 570), w, h)
        assertEquals(MenuPlacement(296 + 24, 300), at)
    }

    @Test
    fun `flips to the left of the item when the right side crosses 1824`() {
        val item = ArtRect(1600, 300, 1800, 570)
        val at = placeMenuBeside(item, w, h)
        assertEquals(1600 - 24 - w, at.x)
        assertTrue("clear of the item", at.x + w <= item.left)
    }

    @Test
    fun `fits on the right exactly at the safe edge`() {
        val item = ArtRect(500, 300, 1200, 570)
        assertEquals(1224, placeMenuBeside(item, w, h).x)
    }

    @Test
    fun `centers when neither side fits`() {
        val at = placeMenuBeside(ArtRect(300, 300, 1700, 570), w, h)
        assertEquals((1920 - w) / 2, at.x)
    }

    @Test
    fun `clamps the bottom so the whole menu stays above 1026`() {
        val at = placeMenuBeside(ArtRect(96, 900, 296, 1000), w, 300)
        assertEquals(1026 - 300, at.y)
    }

    @Test
    fun `clamps the top to 54`() {
        assertEquals(54, placeMenuBeside(ArtRect(96, 10, 296, 200), w, h).y)
    }

    @Test
    fun `a menu taller than the safe area starts at 54`() {
        assertEquals(54, placeMenuBeside(ArtRect(96, 500, 296, 700), w, 1200).y)
    }

    @Test
    fun `a wider menu flips sooner`() {
        val item = ArtRect(1000, 300, 1100, 570)
        assertEquals(1124, placeMenuBeside(item, 600, h).x)
        assertEquals(1000 - 24 - 840, placeMenuBeside(item, 840, h).x)
    }
}
