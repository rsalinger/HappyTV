package com.rysal.happytv.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JsonCoerce] is what keeps a panel that changes a field's JSON type from
 * crashing a refresh — every mapper routes through it, so its edge cases are
 * load-bearing.
 */
class JsonCoerceTest {

    @Test
    fun toIntOrNull_acceptsBothWireShapes() {
        assertEquals(42, JsonCoerce.toIntOrNull("42"))
        assertEquals(42, JsonCoerce.toIntOrNull(42))
        assertEquals(42, JsonCoerce.toIntOrNull(42L))
        assertEquals(42, JsonCoerce.toIntOrNull(42.0))
        assertEquals(42, JsonCoerce.toIntOrNull(" 42 "))
        assertEquals(42, JsonCoerce.toIntOrNull("42.0"))
    }

    @Test
    fun toIntOrNull_rejectsNonNumbersInsteadOfThrowing() {
        assertNull(JsonCoerce.toIntOrNull(null))
        assertNull(JsonCoerce.toIntOrNull(""))
        assertNull(JsonCoerce.toIntOrNull("   "))
        assertNull(JsonCoerce.toIntOrNull("abc"))
        assertNull(JsonCoerce.toIntOrNull("12abc"))
        assertNull(JsonCoerce.toIntOrNull(true))
        assertNull(JsonCoerce.toIntOrNull(1e10)) // out of Int range
        assertNull(JsonCoerce.toIntOrNull(Any()))
    }

    @Test
    fun toLongOrNull_handlesUnixTimestampStrings() {
        assertEquals(1609459200L, JsonCoerce.toLongOrNull("1609459200"))
        assertEquals(1609459200L, JsonCoerce.toLongOrNull(1609459200L))
        assertEquals(7L, JsonCoerce.toLongOrNull(7))
        assertEquals(7L, JsonCoerce.toLongOrNull(7.0))
        assertNull(JsonCoerce.toLongOrNull(""))
        assertNull(JsonCoerce.toLongOrNull(Double.MAX_VALUE))
    }

    @Test
    fun toDoubleOrNull_handlesRatingsInEitherShape() {
        assertEquals(8.7, JsonCoerce.toDoubleOrNull("8.7")!!, 0.0)
        assertEquals(8.7, JsonCoerce.toDoubleOrNull(8.7)!!, 0.0)
        assertEquals(8.0, JsonCoerce.toDoubleOrNull(8)!!, 0.0)
        assertEquals(7.5, JsonCoerce.toDoubleOrNull("7,5")!!, 0.0) // European decimal comma
        assertNull(JsonCoerce.toDoubleOrNull(""))
        assertNull(JsonCoerce.toDoubleOrNull("N/A"))
    }

    @Test
    fun toBooleanOrNull_coversTvArchiveShapes() {
        assertTrue(JsonCoerce.toBooleanOrNull(1))
        assertTrue(JsonCoerce.toBooleanOrNull("1"))
        assertTrue(JsonCoerce.toBooleanOrNull(true))
        assertTrue(JsonCoerce.toBooleanOrNull("yes"))
        assertFalse(JsonCoerce.toBooleanOrNull(0))
        assertFalse(JsonCoerce.toBooleanOrNull("0"))
        assertFalse(JsonCoerce.toBooleanOrNull(false))
        assertFalse(JsonCoerce.toBooleanOrNull(""))
        assertFalse(JsonCoerce.toBooleanOrNull(null))
        assertFalse(JsonCoerce.toBooleanOrNull("garbage"))
        // Panels that omit the flag entirely can opt into "assume catchup".
        assertTrue(JsonCoerce.toBooleanOrNull(null, default = true))
    }
}

/** How (host, port, useTls) as typed by a human becomes the URL we dial. */
class PanelOriginTest {

    @Test
    fun plainHostAndPort() {
        assertEquals("http://panel.example:8080", panelOrigin("panel.example", 8080, false))
        assertEquals("https://panel.example:8443", panelOrigin("panel.example", 8443, true))
    }

    @Test
    fun typedSchemeWinsOverUseTls() {
        assertEquals("https://panel.example:8443", panelOrigin("https://panel.example:8443", 80, false))
        assertEquals("http://panel.example:8080", panelOrigin("http://panel.example", 8080, true))
    }

    @Test
    fun embeddedPortWinsOverPortArgument() {
        assertEquals("http://panel.example:25461", panelOrigin("panel.example:25461", 80, false))
    }

    @Test
    fun pathAndQueryAreDropped() {
        assertEquals("http://panel.example:8000", panelOrigin("panel.example/get.php?x=1", 8000, false))
    }

    @Test
    fun whitespaceIsTrimmedAndZeroPortDisappears() {
        assertEquals("http://panel.example:8080", panelOrigin("  panel.example ", 8080, false))
        assertEquals("http://panel.example", panelOrigin("panel.example", 0, false))
    }
}
