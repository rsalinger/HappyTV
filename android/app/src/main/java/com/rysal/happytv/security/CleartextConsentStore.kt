@file:Suppress("DEPRECATION") // SharedPreferences is fine here: this file holds no secret

package com.rysal.happytv.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the user answered on the "This provider doesn't use encryption" sheet. */
enum class CleartextDecision {
    /** They chose **Use HTTPS** — never send their password over http for this host and port. */
    UseHttps,

    /** They chose **Continue anyway** — http is allowed for this host and port. */
    ContinueOverHttp,
}

/**
 * The sheet's memory: "HappyTV remembers your answer for this server"
 * (design spec "Sign in > Unencrypted-server sheet").
 *
 * Keyed by host and port (#187): a different port is a different service, so
 * `http://host:8090` asks again after an answer for `host:8089`. Entries
 * written before #187 were keyed on the bare host; they no longer match, so
 * the user is asked once more.
 *
 * Deliberately **not** an encrypted store: this is a yes/no the user gave us
 * out loud, not a credential. `SecuredCredentialsStore` remains the only
 * encrypted store, and the only home of passwords.
 */
interface CleartextConsentStore {

    /** The stored answer, or null when the user has never been asked. */
    suspend fun decision(host: String, port: Int): CleartextDecision?

    /** True once the user has answered for [host]:[port] — in either direction. */
    suspend fun isConsented(host: String, port: Int): Boolean = decision(host, port) != null

    suspend fun remember(host: String, port: Int, decision: CleartextDecision)

    /** Settings screen "clear decisions" / tests. */
    suspend fun clear()
}

/**
 * SharedPreferences-backed [CleartextConsentStore], file `happytv_login_prefs`.
 *
 * Every operation hops to [Dispatchers.IO] itself (same convention as
 * [SecuredCredentialsStore]), so callers never think about dispatchers.
 */
class PrefsCleartextConsentStore internal constructor(
    private val prefs: () -> SharedPreferences,
) : CleartextConsentStore {

    override suspend fun decision(host: String, port: Int): CleartextDecision? =
        withContext(Dispatchers.IO) {
            when (prefs().getString(keyFor(host, port), null)) {
                DECISION_HTTPS -> CleartextDecision.UseHttps
                DECISION_HTTP -> CleartextDecision.ContinueOverHttp
                else -> null
            }
        }

    override suspend fun remember(host: String, port: Int, decision: CleartextDecision) {
        withContext(Dispatchers.IO) {
            val value = when (decision) {
                CleartextDecision.UseHttps -> DECISION_HTTPS
                CleartextDecision.ContinueOverHttp -> DECISION_HTTP
            }
            prefs().edit().putString(keyFor(host, port), value).apply()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            val editor = prefs().edit()
            prefs().all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    private fun keyFor(host: String, port: Int): String = KEY_PREFIX + host.trim().lowercase() + ":" + port

    companion object {
        const val PREFS_FILE_NAME = "happytv_login_prefs"
        private const val KEY_PREFIX = "cleartext:"
        private const val DECISION_HTTPS = "https"
        private const val DECISION_HTTP = "http"

        fun create(context: Context): PrefsCleartextConsentStore {
            val appContext = context.applicationContext
            return PrefsCleartextConsentStore {
                appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}
