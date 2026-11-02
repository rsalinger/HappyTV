package com.rysal.happytv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

/** #150: where Right from the category rail sends the D-pad once the list or guide has scrolled. */
class RailReturnTargetTest {
    private val all = LiveCategorySelection.AllChannels
    private val favorites = LiveCategorySelection.Favorites
    private val ids = (1..40).map { "ch$it" }

    private fun target(
        guideShown: Boolean = false,
        guideFocused: Boolean = true,
        last: String? = "ch17",
        lastSelection: LiveCategorySelection? = all,
        selection: LiveCategorySelection = all,
    ) = railReturnTarget(guideShown, guideFocused, ids.asSequence(), last, lastSelection, selection)

    @Test fun guide_returnsThroughTheGuideNotRowZero() {
        assertEquals(RailReturn.Guide, target(guideShown = true))
    }

    @Test fun guide_firstEntryWithNothingFocusedUsesTheFirstRow() {
        assertEquals(RailReturn.FirstRow, target(guideShown = true, guideFocused = false))
    }

    @Test fun guide_inAnotherCategoryStartsAtTheTop() {
        assertEquals(RailReturn.FirstRow, target(guideShown = true, lastSelection = favorites))
    }

    @Test fun list_returnsToTheChannelItLeftEvenWhenRowZeroIsScrolledAway() {
        assertEquals(RailReturn.Row("ch17"), target())
    }

    @Test fun list_inAnotherCategoryStartsAtTheTop() {
        assertEquals(RailReturn.FirstRow, target(lastSelection = favorites))
    }

    @Test fun list_withNothingFocusedYetStartsAtTheTop() {
        assertEquals(RailReturn.FirstRow, target(last = null, lastSelection = null))
    }

    @Test fun list_withAChannelThatLeftTheListStartsAtTheTop() {
        assertEquals(RailReturn.FirstRow, target(last = "gone"))
    }
}
