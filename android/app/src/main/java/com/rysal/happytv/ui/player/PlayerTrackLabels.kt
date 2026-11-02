package com.rysal.happytv.ui.player

import java.util.Locale

/**
 * Names for the Audio and subtitles panel (#170). Streams give a bare language code ("en"), a
 * provider label, or nothing; a family member needs "English", or "Subtitles 1" when the stream
 * says nothing at all.
 */
internal object PlayerTrackLabels {

    /** Which group heading a track sits under; [noun] is the fallback name's first word. */
    enum class Kind(val noun: String) { Audio("Audio"), Subtitles("Subtitles") }

    /**
     * Best name for one track: a provider label that is words rather than a language code, else
     * the language's name in the device language, else "[Kind] [ordinal]". [ordinal] counts the
     * tracks of that kind across every group, starting at 1, so unnamed tracks stay distinct.
     */
    fun name(
        language: String?,
        label: String?,
        kind: Kind,
        ordinal: Int,
        locale: Locale = Locale.getDefault(),
    ): String {
        val trimmedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
        // A three-letter all-caps label ("SDH", "CC") is a name, not an ISO language code.
        val readableLabel = trimmedLabel?.takeIf {
            !it.equals("und", true) && (languageName(it, locale) == null || (it.length == 3 && it.all(Char::isUpperCase)))
        }
        return readableLabel
            ?: languageName(language, locale)
            ?: trimmedLabel?.let { languageName(it, locale) }
            ?: "${kind.noun} $ordinal"
    }

    /** "en" gives "English"; null for blank, "und" and anything that is not a code the platform can name. */
    fun languageName(code: String?, locale: Locale = Locale.getDefault()): String? {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("und", true) || trimmed.equals("zxx", true)) return null
        // Only a bare code (en, eng, pt-BR, zh_Hant) is looked up; free-form text stays a label.
        if (!CODE.matches(trimmed)) return null
        val tag = Locale.forLanguageTag(trimmed.replace('_', '-'))
        if (tag.language.isNullOrEmpty()) return null
        val name = tag.getDisplayName(locale).trim()
        // Codes the platform does not know come back as themselves.
        if (name.isEmpty() || name.equals(trimmed, true) || name.equals(tag.language, true)) return null
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /**
     * Makes the names in one list distinct: a repeated "English" becomes "English", "English 2",
     * "English 3", in order; a name that appears once is left alone.
     */
    fun distinct(names: List<String>): List<String> {
        val total = names.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return names.map { name ->
            if ((total[name] ?: 0) <= 1) {
                name
            } else {
                val n = (seen[name] ?: 0) + 1
                seen[name] = n
                if (n == 1) name else "$name $n"
            }
        }
    }

    private val CODE = Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?$")
}
