package com.rysal.happytv.data.network

import com.rysal.happytv.data.Graph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probes how our shared [Graph.json] config (ignoreUnknownKeys + coerceInputValues
 * + isLenient) behaves on the wire quirks Xtream panels actually produce:
 * numbers sent where docs say strings and vice versa.
 *
 * These tests are the *reason* the DTO field types in `XtreamDtos.kt` are what
 * they are — if one of them fails, the wire contract assumption it encodes is
 * wrong and the DTOs must be adjusted (lenient serializers), not the test.
 */
class WireCoercionProbeTest {

    @Serializable
    data class NumberIntoNullableString(val stream_id: String? = null)

    @Serializable
    data class NumberIntoNonNullString(val stream_id: String = "")

    @Serializable
    data class StringIntoNullableInt(val num: Int? = null)

    @Serializable
    data class StringIntoNullableLong(val added: Long? = null)

    @Serializable
    data class StringIntoNullableDouble(val rating: Double? = null)

    @Serializable
    data class JsonPrimitiveField(val stream_id: JsonPrimitive? = null)

    /** Panels send `stream_id` as a bare number even though docs say string. */
    @Test
    fun numberIntoNullableString() {
        val parsed = Graph.json.decodeFromString<NumberIntoNullableString>("""{"stream_id":123}""")
        assertEquals("JSON number must decode into String as its literal form", "123", parsed.stream_id)
    }

    /** Non-null String with a default must not silently swallow the number. */
    @Test
    fun numberIntoNonNullString() {
        val parsed = Graph.json.decodeFromString<NumberIntoNonNullString>("""{"stream_id":123}""")
        assertEquals("JSON number must decode into non-null String", "123", parsed.stream_id)
    }

    /** `num`, `category_id`… come back as quoted numbers on most panels. */
    @Test
    fun stringIntoNullableInt() {
        val parsed = Graph.json.decodeFromString<StringIntoNullableInt>("""{"num":"42"}""")
        assertEquals("quoted number must decode into Int", 42, parsed.num)
    }

    /** `added` / `exp_date` are documented as string unix timestamps. */
    @Test
    fun stringIntoNullableLong() {
        val parsed = Graph.json.decodeFromString<StringIntoNullableLong>("""{"added":"1609459200"}""")
        assertEquals(1609459200L, parsed.added)
    }

    /** `rating` is a number in some responses, string in some public docs. */
    @Test
    fun stringIntoNullableDouble() {
        val parsed = Graph.json.decodeFromString<StringIntoNullableDouble>("""{"rating":"7.5"}""")
        assertEquals("quoted number must decode into Double", 7.5, parsed.rating!!, 0.0)
    }

    /** Null-handling: an explicit JSON null into a nullable String. */
    @Test
    fun nullIntoNullableString() {
        val parsed = Graph.json.decodeFromString<NumberIntoNullableString>("""{"stream_id":null}""")
        assertNull(parsed.stream_id)
    }

    /**
     * JsonPrimitive as a DTO field type (the fallback if plain String/Int
     * coercion turns out to lose data): reads either shape via `.content`.
     */
    @Test
    fun jsonPrimitiveAcceptsBothShapes() {
        val asNumber = Graph.json.decodeFromString<JsonPrimitiveField>("""{"stream_id":123}""")
        assertEquals("123", asNumber.stream_id?.content)
        val asString = Graph.json.decodeFromString<JsonPrimitiveField>("""{"stream_id":"123"}""")
        assertEquals("123", asString.stream_id?.content)
        // Sanity: jsonPrimitive helper from the element itself.
        assertTrue(asNumber.stream_id!!.jsonPrimitive.isString.not())
    }
}
