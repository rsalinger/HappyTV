package com.rysal.happytv.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The leading quality tag is split off for display (#93); real-looking provider titles. */
class QualityPrefixTest {

    @Test
    fun `separator variants all give the tag and the clean title`() {
        listOf(
            "4K The Conjuring: The Devil Made Me Do It",
            "4K: The Conjuring: The Devil Made Me Do It",
            "4K | The Conjuring: The Devil Made Me Do It",
            "4K|The Conjuring: The Devil Made Me Do It",
            "4K - The Conjuring: The Devil Made Me Do It",
            "[4K] The Conjuring: The Devil Made Me Do It",
            "[4K]The Conjuring: The Devil Made Me Do It",
            "(4K) The Conjuring: The Devil Made Me Do It",
            "  4k   The Conjuring: The Devil Made Me Do It",
        ).forEach {
            assertEquals(it, "4K" to "The Conjuring: The Devil Made Me Do It", splitQuality(it))
        }
    }

    @Test
    fun `every known tag is recognised in any case and reported upper case`() {
        listOf("4K", "UHD", "FHD", "HD", "SD", "HEVC").forEach { tag ->
            assertEquals(tag to "Dune", splitQuality("$tag: Dune"))
            assertEquals(tag to "Dune", splitQuality("${tag.lowercase()} | Dune"))
            assertEquals(tag to "Dune", splitQuality("[${tag.lowercase()}] Dune"))
        }
    }

    @Test
    fun `words that merely start with a tag are left whole`() {
        listOf("4Kids Movie Night", "HDTV Classics", "SDK Story", "Uhdal", "HEVCs", "4K9", "HD-Remastered").forEach {
            assertEquals(it, null to it, splitQuality(it))
        }
    }

    @Test
    fun `a title that is only a tag keeps its name`() {
        listOf("HD", "4K", "[4K]", "4K:", "HD - ", "UHD").forEach {
            assertNull(it, splitQuality(it).first)
            assertEquals(it, splitQuality(it).second)
        }
    }

    @Test
    fun `a tag in the middle or at the end is not a prefix`() {
        assertEquals(null to "Dune 4K", splitQuality("Dune 4K"))
        assertEquals(null to "The HD Files", splitQuality("The HD Files"))
    }

    @Test
    fun `only one tag is taken`() {
        assertEquals("4K" to "HD Hunters", splitQuality("4K HD Hunters"))
    }

    @Test
    fun `the year still splits off the title that is left`() {
        val (tag, rest) = splitQuality("[4K] Dark Matter (2024)")
        assertEquals("4K", tag)
        assertEquals("Dark Matter" to "2024", splitTitleYear(rest))
    }
}
