@file:Suppress("DEPRECATION") // see the class KDoc: upstream marked these APIs deprecated in 1.1.0

package com.rysal.happytv.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one credential set HappyTV holds: an Xtream host/port and the login for it.
 *
 * @param savedAtUtcMillis when [SecuredCredentialsStore.save] last succeeded (epoch millis, UTC).
 */
data class Credentials(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String,
    val password: String,
    val savedAtUtcMillis: Long,
)

/**
 * Encrypted-at-rest home for the Xtream credentials — AndroidX Security's
 * [EncryptedSharedPreferences] under a [MasterKey] (AES256_GCM, Keystore-backed),
 * file `happytv_secure_prefs`.
 *
 * **This is the only place credentials are stored.** Some players keep a second,
 * plaintext copy in ordinary SharedPreferences; we deliberately do not —
 * no plain preferences file, no DataStore, no Room column ever receives this [password]
 * in the clear (Room's `login_profiles.password` holds an opaque value only).
 *
 * Upstream note: `androidx.security:security-crypto` 1.1.0 (the newest release)
 * marks [EncryptedSharedPreferences]/[MasterKey] `@Deprecated`, so the whole
 * file suppresses the warning on purpose — there is no drop-in androidx
 * successor, and the spec for v1 calls for this exact mechanism. Re-evaluate at
 * v2 (Tink AEAD over Keystore directly, or whatever androidx ships next).
 *
 * Threading: construction is cheap (the Keystore/file work happens on first
 * access) and **every public operation hops to [Dispatchers.IO] itself**, so
 * callers never have to think about which dispatcher they are on and the main
 * thread never does Keystore or disk I/O. Each operation is a single
 * `SharedPreferences` mutation, so a concurrent read either sees the old or the
 * new set — never a partial one.
 *
 * Manual DI: [Graph] exposes one instance as `Graph.securedCredentials`
 * (built via [create]); tests inject their own prefs through the internal
 * constructor.
 */
class SecuredCredentialsStore internal constructor(
    private val prefsFactory: () -> SharedPreferences,
    private val clockUtcMillis: () -> Long = System::currentTimeMillis,
) : EpgOverrideStore {

    /**
     * Built on first use, under the store's thread-safe lazy lock — i.e. always
     * from a `Dispatchers.IO` block below, never from whatever thread happened
     * to construct this store.
     */
    private val prefs: SharedPreferences by lazy { prefsFactory() }

    /**
     * Persists the credential set, replacing any previous one atomically.
     *
     * `commit()` rather than `apply()`: a failed write must not be lost silently
     * to process death — it is logged here (v1 does not surface it in the UI;
     * the login flow re-saves on every successful login anyway).
     */
    suspend fun save(
        host: String,
        port: Int,
        useTls: Boolean,
        username: String,
        password: String,
    ): Unit = withContext(Dispatchers.IO) {
        val written = prefs.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putBoolean(KEY_USE_TLS, useTls)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .putLong(KEY_SAVED_AT_UTC_MILLIS, clockUtcMillis())
            .commit()
        if (!written) {
            Log.e(TAG, "Failed to persist credentials to $PREFS_FILE_NAME (disk full?)")
        }
        ChannelLogoGuard.update(username, password)
    }

    /**
     * The stored credential set, or `null` when there is none (first run, or
     * after [wipe]).
     *
     * A partially-written set is treated as absent: better to ask for the
     * password again than to retry a login with half a credential.
     */
    suspend fun current(): Credentials? = withContext(Dispatchers.IO) {
        val p = prefs
        val host = p.getString(KEY_HOST, null)
        if (host == null) {
            return@withContext null
        }
        val missing = listOf(KEY_PORT, KEY_USE_TLS, KEY_USERNAME, KEY_PASSWORD)
            .filterNot { p.contains(it) }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Credential set incomplete (missing $missing) — treating as absent")
            return@withContext null
        }
        ChannelLogoGuard.update(p.getString(KEY_USERNAME, null), p.getString(KEY_PASSWORD, null))
        Credentials(
            host = host,
            port = p.getInt(KEY_PORT, 0),
            useTls = p.getBoolean(KEY_USE_TLS, false),
            username = p.getString(KEY_USERNAME, null).orEmpty(),
            password = p.getString(KEY_PASSWORD, null).orEmpty(),
            // Absent only for a set written before this key existed (none, v1).
            savedAtUtcMillis = p.getLong(KEY_SAVED_AT_UTC_MILLIS, 0L),
        )
    }

    /**
     * #18: a user-typed XMLTV link may carry credentials in its query, so it
     * lives here (encrypted) rather than in the plain settings DataStore.
     * [wipe] clears it with the account.
     */
    override suspend fun get(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_EPG_OVERRIDE, null)
    }

    override suspend fun set(value: String?): Unit = withContext(Dispatchers.IO) {
        val edit = prefs.edit()
        if (value == null) edit.remove(KEY_EPG_OVERRIDE) else edit.putString(KEY_EPG_OVERRIDE, value)
        edit.commit()
        Unit
    }

    /**
     * True exactly when [current] would return non-null — i.e. a *complete*
     * credential set is stored. An incomplete set (see [current]) is not
     * "present": the honest answer is that there is no usable saved login.
     */
    suspend fun isPresent(): Boolean = current() != null

    /** Removes every stored credential. Safe to call when nothing is stored. */
    suspend fun wipe(): Unit = withContext(Dispatchers.IO) {
        // clear() rather than removing the six keys: nothing else may ever live
        // in this file, and "wipe" must mean "wipe".
        prefs.edit().clear().commit()
        ChannelLogoGuard.update(null, null)
        Unit
    }

    companion object {
        private const val TAG = "SecuredCredentials"

        /** EncryptedSharedPreferences file name — keep in sync with tests. */
        const val PREFS_FILE_NAME = "happytv_secure_prefs"

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USE_TLS = "useTls"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SAVED_AT_UTC_MILLIS = "savedAtUtcMillis"
        private const val KEY_EPG_OVERRIDE = "epgOverride"

        /**
         * Production entry point: an encrypted store on the application context.
         *
         * Cheap and non-suspending on purpose — [Graph] holds it in a `by lazy`
         * val; the Keystore key generation and file I/O happen inside the first
         * suspend call, on [Dispatchers.IO].
         */
        fun create(context: Context): SecuredCredentialsStore {
            val appContext = context.applicationContext
            return SecuredCredentialsStore(prefsFactory = { encryptedPrefs(appContext) })
        }

        /**
         * AES256_GCM master key in the Android Keystore; values encrypted with
         * AES256_GCM, keys with AES256_SIV (deterministic — needed so a key can
         * be found, while still not being a readable column name on disk).
         *
         * A Keystore/keyset failure here surfaces as a crash on the first login
         * call: credentials are not written anywhere less secure as a fallback.
         */
        private fun encryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
