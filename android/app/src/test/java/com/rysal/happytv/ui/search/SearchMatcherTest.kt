package com.rysal.happytv.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design spec §"Screens: search > Behavior" matching rule, pinned with the
 * artboard's own examples:
 *
 *  - "co" finds Copper Sky **and** Comet Crew
 *  - "co sk" finds Copper Sky **only**
 */
class SearchMatcherTest {

    @Test
    fun `one term matches the start of any word`() {
        val terms = SearchMatcher.terms("co")
        // The artboard's own examples for "co".
        assertTrue(SearchMatcher.matches("Copper Sky", terms))
        assertTrue(SearchMatcher.matches("Comet Crew", terms))
        assertTrue(SearchMatcher.matches("Little Comets", terms))
        assertFalse(SearchMatcher.matches("Courtside Live", SearchMatcher.terms("cu")))
    }

    @Test
    fun `every term must match a word start`() {
        val terms = SearchMatcher.terms("co sk")
        assertTrue(SearchMatcher.matches("Copper Sky", terms))
        assertFalse(SearchMatcher.matches("Comet Crew", terms))
    }

    @Test
    fun `terms match in any order across words`() {
        val terms = SearchMatcher.terms("sky copper")
        assertTrue(SearchMatcher.matches("Copper Sky", terms))
    }

    @Test
    fun `matching is case insensitive and whitespace tolerant`() {
        val terms = SearchMatcher.terms("  CO   Sk ")
        assertEquals(listOf("co", "sk"), terms)
        assertTrue(SearchMatcher.matches("COPPER sky", terms))
    }

    @Test
    fun `a term that only appears inside a word does not match`() {
        val terms = SearchMatcher.terms("per")
        assertFalse(SearchMatcher.matches("Copper Sky", terms))
    }

    @Test
    fun `empty or blank query has no terms and matches nothing`() {
        assertEquals(emptyList<String>(), SearchMatcher.terms(""))
        assertEquals(emptyList<String>(), SearchMatcher.terms("   "))
        assertFalse(SearchMatcher.matches("Copper Sky", SearchMatcher.terms("")))
        assertFalse(SearchMatcher.matches("Copper Sky", SearchMatcher.terms("  ")))
    }

    @Test
    fun `filter keeps catalog order and reports every match`() {
        val rows = listOf("Little Comets", "Courtside", "County Weather", "Copper Sky", "Nightly News")
        val found = SearchMatcher.filter(rows, SearchMatcher.terms("co")) { it }
        assertEquals(listOf("Little Comets", "Courtside", "County Weather", "Copper Sky"), found)
    }

    @Test
    fun `filter with no terms returns nothing rather than everything`() {
        val rows = listOf("Copper Sky", "Comet Crew")
        assertEquals(emptyList<String>(), SearchMatcher.filter(rows, emptyList()) { it })
    }

    @Test
    fun `matching ignores case, extra spaces and mid-word hits`() {
        val terms = SearchMatcher.terms("  AG   rai ")
        assertTrue(SearchMatcher.matches("  Agatha   Raisin (2016) ", terms))
        // "ais" sits inside "Raisin" but starts no word.
        assertFalse(SearchMatcher.matches("Agatha Raisin", SearchMatcher.terms("ais")))
        assertFalse(SearchMatcher.matches("", terms))
    }
}
