package com.rysal.happytv.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #68: series cover and the two (really three) `backdrop_path` wire shapes. */
class SeriesArtMapperTest {

    private val url = "https://img.example/b1.jpg"

    private fun entity(backdrop: kotlinx.serialization.json.JsonElement?, cover: String? = "https://img.example/c.jpg") =
        SeriesStreamDto(series_id = "7", name = "Show", cover = cover, backdrop_path = backdrop)
            .toEntity()!!

    @Test
    fun `backdrop as an array yields its first url`() {
        val e = entity(JsonArray(listOf(JsonPrimitive(url), JsonPrimitive("https://img.example/b2.jpg"))))
        assertEquals(url, e.backdropUrl)
    }

    @Test
    fun `backdrop as a plain string yields that url`() {
        assertEquals(url, entity(JsonPrimitive(url)).backdropUrl)
    }

    @Test
    fun `backdrop as a json-encoded array inside a string yields its first url`() {
        assertEquals(url, entity(JsonPrimitive("[\"$url\"]")).backdropUrl)
    }

    @Test
    fun `empty array, empty string and missing backdrop yield null`() {
        assertNull(entity(JsonArray(emptyList())).backdropUrl)
        assertNull(entity(JsonPrimitive("")).backdropUrl)
        assertNull(entity(null).backdropUrl)
    }

    @Test
    fun `blank cover collapses to null poster`() {
        assertNull(entity(null, cover = "  ").posterUrl)
        assertNull(entity(null, cover = "").posterUrl)
    }

    @Test
    fun `garbage stored backdrop does not throw`() {
        assertEquals(emptyList<String>(), decodeStoredBackdrops("[not json"))
        assertEquals(emptyList<String>(), decodeStoredBackdrops(null))
    }
}
