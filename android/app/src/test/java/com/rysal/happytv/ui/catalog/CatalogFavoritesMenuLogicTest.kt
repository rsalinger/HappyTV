package com.rysal.happytv.ui.catalog

import com.rysal.happytv.ui.focus.RemovalFocus
import com.rysal.happytv.ui.focus.resolveFocusAfterRemoval
import com.rysal.happytv.ui.search.favoriteMenuLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** #232: the grid menu's label and where focus goes after a favorite leaves Favorites (rule now in #242's holder). */
class CatalogFavoritesMenuLogicTest {

    @Test
    fun `menu label follows the favorite state`() {
        assertEquals("Add to favorites", favoriteMenuLabel(isFavorite = false))
        assertEquals("Remove from favorites", favoriteMenuLabel(isFavorite = true))
    }

    @Test
    fun `focus goes to the poster that took the removed one's place`() {
        // a, b, c with b removed: c now sits at index 1.
        assertEquals(RemovalFocus.Target("c"), resolveFocusAfterRemoval("b", 1, listOf("a", "c")))
        assertEquals(RemovalFocus.Target("c"), resolveFocusAfterRemoval("b0", 0, listOf("c", "d")))
    }

    @Test
    fun `focus goes to the one before when the removed one was last`() {
        assertEquals(RemovalFocus.Target("b"), resolveFocusAfterRemoval("c", 2, listOf("a", "b")))
    }

    @Test
    fun `no poster is left after the only favorite goes`() {
        assertEquals(RemovalFocus.ListEmpty, resolveFocusAfterRemoval("a", 0, emptyList<String>()))
    }
}
