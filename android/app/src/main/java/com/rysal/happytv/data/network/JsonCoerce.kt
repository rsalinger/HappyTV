package com.rysal.happytv.data.network

/**
 * Defensive numeric coercion for Xtream wire values.
 *
 * The protocol docs say one thing and panels do another: `stream_id` is an
 * integer in the spec and `"123"` in most responses (and sometimes an integer
 * again on XUI-One forks); `rating` is a number in some responses and `"7.5"`
 * in public docs; `tv_archive` is `0|1`, `"0"|"1"`, `true|false`, or absent.
 * Every mapper in `XtreamMappers.kt` funnels through this object instead of
 * calling `String.toIntOrNull()` directly, so a panel that flips a field's
 * JSON type mid-list degrades to "field absent" rather than a crash.
 *
 * Nothing here throws: `null` means "could not read a number from this", and
 * callers decide whether that is fatal (ids) or cosmetic (ratings).
 */
object JsonCoerce {

    /**
     * `Any?` because callers pass whatever the DTO field held — a `String`,
     * an `Int`/`Long`/`Double` boxed by the mapper, or `null`. Both the
     * `"42"` and `42` wire shapes parse; booleans and objects do not
     * (`"true"` is deliberately not a number — panels use it for flags we
     * store as text).
     */
    fun toIntOrNull(s: Any?): Int? = when (s) {
        null -> null
        is Int -> s
        is Long -> s.toIntSafelyOrNull()
        is Double -> s.toIntSafelyOrNull()
        is Boolean -> null
        // A JSON `7.0` reaches a String field as "7.0" — accept it, truncate.
        is String -> parseLoose(s)?.let { loose ->
            loose.toIntOrNull() ?: loose.toDoubleOrNull()?.toIntSafelyOrNull()
        }
        else -> null
    }

    /** Same contract as [toIntOrNull] for 64-bit fields (`added`, `exp_date`…). */
    fun toLongOrNull(s: Any?): Long? = when (s) {
        null -> null
        is Long -> s
        is Int -> s.toLong()
        is Double -> s.toLongSafelyOrNull()
        is Boolean -> null
        is String -> parseLoose(s)?.let { loose ->
            loose.toLongOrNull() ?: loose.toDoubleOrNull()?.toLongSafelyOrNull()
        }
        else -> null
    }

    /** Ratings: `8.7`, `"8.7"`, `8` and `"8"` all become `8.7`/`8.0`. */
    fun toDoubleOrNull(s: Any?): Double? = when (s) {
        null -> null
        is Double -> s
        is Float -> s.toDouble()
        is Int -> s.toDouble()
        is Long -> s.toDouble()
        is Boolean -> null
        is String -> parseLoose(s)?.toDoubleOrNull()
        else -> null
    }

    /**
     * `tv_archive`-style flags: `1`/`"1"`/`true` → true, `0`/`"0"`/`false` →
     * false, anything else → [default] (panels leave the field out or send
     * `""` when catchup is unsupported).
     */
    fun toBooleanOrNull(s: Any?, default: Boolean = false): Boolean = when (s) {
        is Boolean -> s
        is Int -> s != 0
        is Long -> s != 0L
        is String -> when (s.trim().lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no", "" -> false
            else -> default
        }
        else -> default
    }

    /**
     * Shared loose-number parse: trims, tolerates the surrounding quotes some
     * panels include (`"\"7.5\""` reaches us already unquoted by the JSON
     * layer, but `" 7.5 "` and `"7,5"` do occur), and returns null instead of
     * throwing on `"N/A"`/`""`.
     */
    private fun parseLoose(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val normalized = if (t.contains(',') && !t.contains('.')) t.replace(',', '.') else t
        // Reject anything that is not optionally-signed digits/decimal point so
        // `toLongOrNull` never silently truncates "12abc".
        val body = normalized.removePrefix("-")
        if (body.isEmpty() || body.any { !it.isDigit() && it != '.' }) return null
        return normalized
    }

    private fun Long.toIntSafelyOrNull(): Int? =
        if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null

    private fun Double.toIntSafelyOrNull(): Int? =
        if (this in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) toInt() else null

    private fun Double.toLongSafelyOrNull(): Long? =
        if (this in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) toLong() else null
}
