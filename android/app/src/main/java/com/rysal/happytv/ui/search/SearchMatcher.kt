package com.rysal.happytv.ui.search

/**
 * Search's matching rule, in one place so it can be tested without a database.
 *
 * design spec §"Screens: search > Behavior": *every* word typed must match the
 * **start** of some word in the name —
 *
 *  - "co" → Copper Sky **and** Comet Crew
 *  - "co sk" → Copper Sky only (Comet Crew has no word starting with "sk")
 *
 * so matching is per-term AND, prefix-only, and words are split on
 * whitespace. Case never matters.
 */
object SearchMatcher {

    /** "  co   SK " -> ["co", "sk"]. Blank query -> no terms. */
    fun terms(query: String): List<String> = query.lowercase().split(WORD_SEPARATOR)
        .filter { it.isNotEmpty() }

    /**
     * True when every [term] (already lowercase, see [terms]) prefixes some
     * word of [name]. Allocation-free: Search runs this over the whole catalog
     * (tens of thousands of names) on every settled keystroke.
     */
    fun matches(name: String, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        for (term in terms) {
            if (!anyWordStartsWith(name, term)) return false
        }
        return true
    }

    private fun anyWordStartsWith(name: String, term: String): Boolean {
        val n = name.length
        var i = 0
        while (i < n) {
            while (i < n && name[i].isWhitespace()) i++
            if (i >= n) return false
            if (name.regionMatches(i, term, 0, term.length, ignoreCase = true)) return true
            while (i < n && !name[i].isWhitespace()) i++
        }
        return false
    }

    /** The matching subset of [rows], in their existing order. */
    fun <T> filter(rows: List<T>, terms: List<String>, nameOf: (T) -> String): List<T> =
        if (terms.isEmpty()) emptyList() else rows.filter { matches(nameOf(it), terms) }

    private val WORD_SEPARATOR = Regex("\\s+")
}
