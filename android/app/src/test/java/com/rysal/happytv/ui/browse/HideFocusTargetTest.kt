package com.rysal.happytv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #114: which rail row takes focus when a category is hidden, for both rail models. */
class HideFocusTargetTest {

    private fun liveRail(vararg ids: String): List<LiveCategoryItem> = buildList {
        add(LiveCategoryItem.Pinned("fav", "Favorites", 0, PinnedIconKind.STAR, false, LiveCategorySelection.Favorites))
        add(LiveCategoryItem.Divider)
        ids.forEach { add(LiveCategoryItem.ProviderCategory(it, it, 1, false, LiveCategorySelection.Category(it, it))) }
        add(LiveCategoryItem.AllChannels("all", "All channels", 9, false))
    }

    @Test fun live_hidingAMiddleCategoryFocusesTheNextOne() {
        assertEquals("b", railKeyAfterHide(liveRail("a", "b", "c"), "a"))
        assertEquals("c", railKeyAfterHide(liveRail("a", "b", "c"), "b"))
    }

    @Test fun live_hidingTheLastCategoryFocusesTheRowAfterIt() {
        assertEquals("all", railKeyAfterHide(liveRail("a", "b", "c"), "c"))
    }

    @Test fun live_unknownCategoryHasNoTarget() {
        assertNull(railKeyAfterHide(liveRail("a"), "zzz"))
    }

    private fun entries(vararg keys: String): List<RailEntry> = buildList {
        add(RailEntry.Row("fav", "Favorites", 0, null, false))
        add(RailEntry.Divider)
        keys.forEach { add(RailEntry.Row(it, it, 1, null, false, hasMenu = true)) }
    }

    @Test fun catalog_hidingAMiddleCategoryFocusesTheNextOne() {
        assertEquals("c", railKeyAfterHide(entries("a", "b", "c"), "b"))
    }

    @Test fun catalog_hidingTheLastCategoryFocusesThePreviousOne() {
        assertEquals("a", railKeyAfterHide(entries("a", "b"), "b"))
    }

    @Test fun catalog_hidingTheOnlyCategoryFallsBackToThePinnedRowAbove() {
        assertEquals("fav", railKeyAfterHide(entries("a"), "a"))
    }

    @Test fun catalog_unknownKeyHasNoTarget() {
        assertNull(railKeyAfterHide(entries("a"), "zzz"))
    }
}
