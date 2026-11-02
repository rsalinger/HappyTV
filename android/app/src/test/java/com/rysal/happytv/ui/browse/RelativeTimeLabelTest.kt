package com.rysal.happytv.ui.browse

import com.rysal.happytv.ui.catalog.CatalogViewModel
import com.rysal.happytv.ui.catalog.catalogSubtitle
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeLabelTest {
    private val now = 1_000_000_000_000L
    private fun ago(ms: Long) = relativeTimeLabel(now - ms, now)

    @Test
    fun singularAndPlural() {
        assertEquals("1 minute ago", ago(60_000L))
        assertEquals("5 minutes ago", ago(5 * 60_000L))
        assertEquals("1 hour ago", ago(3_600_000L))
        assertEquals("23 hours ago", ago(23 * 3_600_000L))
        assertEquals("1 day ago", ago(86_400_000L))
        assertEquals("2 days ago", ago(2 * 86_400_000L))
    }

    @Test
    fun recentlyAddedSaysNewestWhenCapped() {
        val recent = CatalogViewModel.RECENT_CATEGORY_ID
        assertEquals("Recently added · newest 480", catalogSubtitle(recent, "Recently added", CatalogViewModel.RECENT_CAP, "shows"))
        assertEquals("Recently added · 12 shows", catalogSubtitle(recent, "Recently added", 12, "shows"))
        assertEquals("Drama · 480 movies", catalogSubtitle("42", "Drama", 480, "movies"))
    }

    @Test
    fun singleItemHeaderIsSingular() {
        assertEquals("Continue watching · 1 movie", catalogSubtitle(CatalogViewModel.CONTINUE_CATEGORY_ID, "Continue watching", 1, "movies"))
        assertEquals("Continue watching · 1 show", catalogSubtitle(CatalogViewModel.CONTINUE_CATEGORY_ID, "Continue watching", 1, "shows"))
    }
}
