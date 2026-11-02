package com.rysal.happytv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

/** #10: [formatCount] is hand-rolled (no NumberFormat per call); it must keep the en-US grouping. */
class FormatCountTest {
    @Test
    fun `groups thousands like en-US`() {
        val expected = mapOf(
            0 to "0", 7 to "7", 999 to "999", 1_000 to "1,000", 12_345 to "12,345",
            123_456 to "123,456", 1_234_567 to "1,234,567", 21_492 to "21,492",
            -1_234 to "-1,234", -12 to "-12", Int.MAX_VALUE to "2,147,483,647",
        )
        expected.forEach { (value, text) ->
            assertEquals(text, formatCount(value))
            assertEquals(java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value), formatCount(value))
        }
    }

    /** #173: exactly one is singular; zero and many are plural. */
    @Test
    fun `unit is singular for exactly one`() {
        assertEquals("1 movie", formatCount(1, "movies"))
        assertEquals("1 show", formatCount(1, "shows"))
        assertEquals("1 channel", formatCount(1, "channels"))
        assertEquals("1 category", formatCount(1, "categories"))
        assertEquals("0 movies", formatCount(0, "movies"))
        assertEquals("2 categories", formatCount(2, "categories"))
        assertEquals("1,204 movies", formatCount(1_204, "movies"))
    }
}
