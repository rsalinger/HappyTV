package com.rysal.happytv.ui.settings

import com.rysal.happytv.data.Graph
import kotlinx.coroutines.flow.first

/**
 * Reads the Account pane's facts (design spec §"Settings > Sections and rows"):
 * the stored login profile for the server's display name, the encrypted
 * credential set for host/port/username and for *when* this account was signed
 * in with — "Signed in · 12 hours ago".
 *
 * Returns null when there is no stored credential set: that is the
 * "not signed in" state, which draws the Sign in card, not an error.
 */
object SettingsAccountSource {
    suspend fun load(): SettingsAccount? {
        val credentials = Graph.securedCredentials.current() ?: return null
        val profile = Graph.database.loginProfileDao().observe().first()
        return SettingsAccount(
            serverName = profile?.name?.trim().takeUnless { it.isNullOrEmpty() } ?: credentials.host,
            host = credentials.host,
            port = credentials.port,
            username = credentials.username,
            signedInAtUtcMillis = credentials.savedAtUtcMillis,
        )
    }
}
