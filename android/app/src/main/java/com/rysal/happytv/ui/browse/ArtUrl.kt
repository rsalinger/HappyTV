package com.rysal.happytv.ui.browse

import android.util.Log
import com.rysal.happytv.security.ChannelLogoGuard
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one check every provider art address passes before it reaches Coil (#238): grid posters,
 * the details poster and backdrop, episode stills and Search results all draw through
 * [HtPosterImage], which calls [checked]. The rules are [ChannelLogoUrl.safeArt].
 *
 * A poster grid composes thousands of cells, so the answer is remembered per address (a small LRU)
 * and a cell pays one map lookup, not a URL parse. The memo is dropped when the signed-in
 * username or password changes, since the check depends on them. Refusals are counted, never
 * logged with an address.
 */
internal object ArtUrl {
    private const val MEMO_SIZE = 4096
    private const val LOG_EVERY = 50

    /** Memo value for a refused address (a safe address is never empty). */
    private const val REFUSED = ""

    private val memo = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MEMO_SIZE
    }
    private var memoSecrets: List<String> = emptyList()
    private val refusals = AtomicInteger()

    /** [raw] as an address that may be requested, or null (draw the placeholder). */
    fun checked(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val secrets = ChannelLogoGuard.secrets
        synchronized(memo) {
            if (memoSecrets !== secrets) {
                memo.clear()
                memoSecrets = secrets
            }
            memo[raw]?.let { return it.takeIf { v -> v.isNotEmpty() } }
        }
        val safe = ChannelLogoUrl.safeArt(raw, secrets)
        if (safe == null) {
            val n = refusals.incrementAndGet()
            if (n == 1 || n % LOG_EVERY == 0) Log.i("HtPosterImage", "art refused count=$n")
        }
        synchronized(memo) { if (memoSecrets === secrets) memo[raw] = safe ?: REFUSED }
        return safe
    }

    /** Test hook: forgets everything remembered. */
    internal fun reset() = synchronized(memo) {
        memo.clear()
        refusals.set(0)
    }
}
