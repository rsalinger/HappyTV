package com.rysal.happytv.ui.browse

import com.rysal.happytv.ui.browse.LiveCategorySelection.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure pieces of the compact header: width sharing and the Live TV subtitle. */
class HeaderWidthShareTest {

    @Test
    fun `both keep their natural width when they fit`() {
        assertEquals(100 to 200, shareHeaderWidth(100, 200, 400))
    }

    @Test
    fun `the shorter keeps its width and the longer takes the rest`() {
        assertEquals(100 to 500, shareHeaderWidth(100, 900, 600))
        assertEquals(500 to 100, shareHeaderWidth(900, 100, 600))
    }

    @Test
    fun `two long texts split the room evenly`() {
        assertEquals(300 to 300, shareHeaderWidth(900, 900, 600))
    }

    @Test
    fun `live tv subtitle names the category and its count`() {
        val subtitle = liveTvHeaderSubtitle(
            selection = Category(categoryId = "1", label = "News"),
            selectionChannelCount = 86,
            hiddenChannelCount = 0,
            loading = false,
            totalChannels = 21_000,
            visibleCategoryCount = 90,
        )
        assertEquals("News · 86 channels", subtitle)
    }

    @Test
    fun `live tv subtitle mentions hidden channels and loading`() {
        assertEquals(
            "Favorites · 4 channels · 2 hidden",
            liveTvHeaderSubtitle(LiveCategorySelection.Favorites, 4, 2, false, 10, 3),
        )
        assertEquals(
            "All channels · …",
            liveTvHeaderSubtitle(LiveCategorySelection.AllChannels, 0, 0, true, 0, 0),
        )
    }

    @Test
    fun `without a selection the subtitle shows the totals`() {
        assertEquals(
            "21,000 channels · 90 categories",
            liveTvHeaderSubtitle(null, 0, 0, false, 21_000, 90),
        )
    }
}
