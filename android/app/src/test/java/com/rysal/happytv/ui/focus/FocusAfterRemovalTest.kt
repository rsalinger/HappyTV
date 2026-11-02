package com.rysal.happytv.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusAfterRemovalTest {

    @Test fun middleItemHandsFocusToTheNextOne() {
        assertEquals(3, indexAfterRemoval(removedIndex = 2, size = 5))
    }

    @Test fun firstItemHandsFocusToTheNextOne() {
        assertEquals(1, indexAfterRemoval(removedIndex = 0, size = 5))
    }

    @Test fun lastItemHandsFocusToThePreviousOne() {
        assertEquals(3, indexAfterRemoval(removedIndex = 4, size = 5))
    }

    @Test fun onlyItemLeavesNothing() {
        assertNull(indexAfterRemoval(removedIndex = 0, size = 1))
    }

    @Test fun emptyOrOutOfRangeLeavesNothing() {
        assertNull(indexAfterRemoval(removedIndex = 0, size = 0))
        assertNull(indexAfterRemoval(removedIndex = -1, size = 3))
        assertNull(indexAfterRemoval(removedIndex = 3, size = 3))
    }

    private val rail = listOf("fav", "recent", "---", "a", "b", "c", "all")
    private val focusable: (String) -> Boolean = { it != "---" }

    @Test fun skipsDividersGoingForward() {
        assertEquals("a", rail.focusTargetAfterRemoval(1, focusable))
    }

    @Test fun takesTheNextRow() {
        assertEquals("c", rail.focusTargetAfterRemoval(4, focusable))
    }

    @Test fun lastRowFallsBackToThePreviousFocusable() {
        assertEquals("c", rail.focusTargetAfterRemoval(6, focusable))
    }

    @Test fun previousSkipsDividerWhenNothingFollows() {
        assertEquals("recent", listOf("fav", "recent", "---", "a").focusTargetAfterRemoval(3, focusable))
    }

    @Test fun nothingFocusableLeftIsNull() {
        assertNull(listOf("---", "a").focusTargetAfterRemoval(1, focusable))
        assertNull(emptyList<String>().focusTargetAfterRemoval(0, focusable))
        assertNull(rail.focusTargetAfterRemoval(99, focusable))
    }
}
