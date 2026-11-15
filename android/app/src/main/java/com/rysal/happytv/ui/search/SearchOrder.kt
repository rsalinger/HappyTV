package com.rysal.happytv.ui.search

import com.rysal.happytv.data.dao.SearchChannelRow
import com.rysal.happytv.data.dao.SearchPosterRow

/**
 * The order Search lists matches in: `num`, then name ignoring case (#241).
 *
 * This reproduces the SQL the search-row queries used to carry, `ORDER BY num, name COLLATE NOCASE`, so it
 * can run on the capped matches instead of sorting a whole table in SQLite:
 *
 *  - NULL `num` sorts before every number (SQLite treats NULL as smaller than any value in ASC).
 *  - NOCASE folds only ASCII `A-Z` to lowercase; every other character, accented letters included, is
 *    compared as it is. Kotlin's `ignoreCase` would fold Unicode, so it is not used here.
 *  - The comparison is by code point: SQLite compares UTF-8 bytes, which orders exactly as code points do
 *    (UTF-16 `char` order would put a supplementary character before U+E000..U+FFFF). A name that is a
 *    prefix of another sorts first.
 *  - Ties keep the table's own order ([sortedWith] is stable).
 */
internal object SearchOrder {

    val channels: Comparator<SearchChannelRow> =
        compareBy<SearchChannelRow, Int?>(nullsFirst(naturalOrder())) { it.num }
            .thenComparator { a, b -> compareNoCase(a.name, b.name) }

    val posters: Comparator<SearchPosterRow> =
        compareBy<SearchPosterRow, Int?>(nullsFirst(naturalOrder())) { it.num }
            .thenComparator { a, b -> compareNoCase(a.name, b.name) }

    /** SQLite `COLLATE NOCASE`: ASCII-only case folding, then code point (UTF-8 byte) order. */
    fun compareNoCase(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) {
                val fa = foldAscii(ca)
                val fb = foldAscii(cb)
                if (fa != fb) return if (fa < fb) -1 else 1
            }
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }

    private fun foldAscii(cp: Int): Int = if (cp in 'A'.code..'Z'.code) cp + ('a'.code - 'A'.code) else cp
}
