package com.rysal.happytv.ui.guide

import org.junit.Assert.assertTrue
import org.junit.Test

/** #94: the channel cell's arithmetic, pinned so a later tweak can't bring the overflow back. */
class GuideChannelCellGeometryTest {

    @Test
    fun `two lines of the name fit the row without growing it`() {
        assertTrue(2 * CHANNEL_NAME_LINE_HEIGHT_PX <= ROW_HEIGHT_PX)
        assertTrue("24 px is the text floor", CHANNEL_NAME_LINE_HEIGHT_PX >= 24)
    }

    @Test
    fun `a five digit number has a gap of at least 8 px before the badge`() {
        // Five tabular digits at 24 px 600 measure about 75 px.
        assertTrue(CHANNEL_NUMBER_COLUMN_PX >= 75)
        assertTrue(CHANNEL_NUMBER_GAP_PX >= 8)
    }

    @Test
    fun `the name column is no narrower than before`() {
        val old = CHANNEL_CELL_WIDTH_PX - 12 - 16 - 76 - 72 - 12
        val now = CHANNEL_CELL_WIDTH_PX - CHANNEL_CELL_PAD_START_PX - CHANNEL_CELL_PAD_END_PX -
            CHANNEL_NUMBER_COLUMN_PX - CHANNEL_NUMBER_GAP_PX - 72 - 12
        assertTrue("name width $now vs $old", now >= old - 2)
    }
}
