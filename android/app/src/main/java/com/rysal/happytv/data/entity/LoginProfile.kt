package com.rysal.happytv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single logged-in panel account.
 *
 * Single-row table: [id] is always [SINGLE_ROW_ID] and reads use `LIMIT 1`.
 *
 * [password] is **ciphertext** produced by the security layer
 * (EncryptedSharedPreferences-backed store) — the plaintext password never
 * touches this table, this file, or any log.
 */
@Entity(tableName = "login_profiles")
data class LoginProfile(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    /** Display label for the account, e.g. the provider name. */
    val name: String,
    /** Panel host, e.g. `panel.example.com` (no scheme, no path). */
    val host: String,
    val port: Int,
    val username: String,
    /** Ciphertext — see class doc. Decrypt via the security layer before use. */
    val password: String,
    /** false = plain HTTP (Xtream panels commonly ship without TLS). Default false. */
    val useTls: Boolean = false,
) {
    companion object {
        const val SINGLE_ROW_ID = 0
    }
}
