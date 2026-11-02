package com.rysal.happytv.ui.login

import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.network.LoginResult
import com.rysal.happytv.data.network.RefreshCoordinator
import com.rysal.happytv.security.SecuredCredentialsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen's narrow view of "log in": one call, plus "start the catalog
 * refresh". A fake in `LoginViewModelTest` implements this interface, so the
 * state machine is tested without Room, OkHttp or a panel.
 */
interface LoginAuthGateway {
    suspend fun login(
        host: String,
        port: Int,
        useTls: Boolean,
        username: String,
        password: String,
        displayName: String,
    ): LoginResult

    /** Kick off the 3-tile refresh after a successful login (fire and forget). */
    suspend fun refreshAll()
}

/** Where "Saved on this TV" gets its rows. */
interface SavedServersSource {
    suspend fun savedServers(): List<SavedServer>
}

/**
 * [LoginAuthGateway] over the real coordinator. `RefreshCoordinator.login`
 * already persists credentials (encrypted store + Room profile + `user_info`
 * cache) on success, so the screen does not repeat that work.
 */
internal class CoordinatorLoginAuthGateway(
    private val coordinator: RefreshCoordinator,
) : LoginAuthGateway {
    override suspend fun login(
        host: String,
        port: Int,
        useTls: Boolean,
        username: String,
        password: String,
        displayName: String,
    ): LoginResult = coordinator.login(
        host = host,
        port = port,
        useTls = useTls,
        username = username,
        password = password,
        displayName = displayName,
    )

    override suspend fun refreshAll() = coordinator.refreshAll()
}

/**
 * "Saved on this TV" from the Room `login_profiles` row plus the encrypted
 * store's timestamp.
 *
 * v1 is single-account ([LoginProfile][com.rysal.happytv.data.entity.LoginProfile]
 * holds one row), so this returns at most one server — the list-shaped
 * [LoginUiState.savedServers] is what the handoff draws, and it scales the
 * day the data layer stores more than one profile. When the user has never
 * signed in there are no rows, so the card centres and the field is empty
 * (the "first run" state).
 */
internal class RoomSavedServersSource(
    private val database: HappyTvDatabase,
    private val credentials: SecuredCredentialsStore,
    private val clockUtcMillis: () -> Long = System::currentTimeMillis,
    private val locale: () -> Locale = Locale::getDefault,
) : SavedServersSource {

    override suspend fun savedServers(): List<SavedServer> {
        val profile = database.loginProfileDao().get() ?: return emptyList()
        val stored = credentials.current()
        val hostLabel = "${profile.host}:${profile.port}"
        return listOf(
            SavedServer(
                id = "profile-${profile.id}",
                name = profile.name.ifBlank { profile.host },
                hostLabel = hostLabel,
                username = profile.username,
                lastSignedInLabel = stored?.savedAtUtcMillis
                    ?.takeIf { it > 0 }
                    ?.let { "Last signed in " + formatDay(it, locale()) },
                // The single stored row *is* the account in use: the accent
                // check marks it until v1 grows a multi-server list.
                inUse = stored != null,
            ),
        )
    }

    /** "Oct 14" — day precision is all the handoff shows. */
    private fun formatDay(epochMillis: Long, locale: Locale): String =
        SimpleDateFormat("MMM d", locale).format(Date(epochMillis))
}
