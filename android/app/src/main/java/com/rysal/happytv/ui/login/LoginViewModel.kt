package com.rysal.happytv.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rysal.happytv.data.network.LoginResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * State holder for the sign-in screen.
 *
 * One [StateFlow] of [LoginUiState]; the composables render it and call the
 * `on…` methods. Everything that touches disk, Room or the network runs on
 * [Dispatchers.IO] (via [withContext]) or inside the gateway, so the main
 * thread only ever reads state.
 *
 * The gateway pattern ([LoginAuthGateway] / [SavedServersSource] /
 * `CleartextConsentStore`) keeps this class unit-testable with plain fakes —
 * see `LoginViewModelTest` in `app/src/test`.
 */
class LoginViewModel internal constructor(
    private val auth: LoginAuthGateway,
    private val savedServersSource: SavedServersSource,
    private val cleartextConsent: com.rysal.happytv.security.CleartextConsentStore,
    private val clockUtcMillis: () -> Long = System::currentTimeMillis,
    private val loginTimeoutMillis: Long = DEFAULT_LOGIN_TIMEOUT_MILLIS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { loadSavedServers() }
    }

    // ------------------------------------------------------------------ inputs

    fun onServerUrlChange(value: String) = _state.update {
        it.copy(serverUrl = value, serverUrlError = null, infoLine = null)
    }

    fun onUsernameChange(value: String) = _state.update {
        it.copy(username = value, usernameError = null, infoLine = null)
    }

    fun onPasswordChange(value: String) = _state.update {
        it.copy(password = value, passwordError = null, infoLine = null)
    }

    fun onRememberMeToggle() = _state.update { it.copy(rememberMe = !it.rememberMe) }

    /** "Common URL formats" pressed: show the dialog, focus its first option. */
    fun onOpenUrlFormats() = _state.update {
        it.copy(overlay = LoginOverlay.UrlFormats, focusTarget = LoginFocusTarget.FormatOption)
    }

    fun onCloseUrlFormats() = _state.update {
        it.copy(overlay = LoginOverlay.None, focusTarget = LoginFocusTarget.UrlFormatsLink)
    }

    /** Picking a format fills Server URL, closes the dialog, returns focus to it. */
    fun onPickUrlFormat(url: String) = _state.update {
        it.copy(serverUrl = url, serverUrlError = null, overlay = LoginOverlay.None, focusTarget = LoginFocusTarget.ServerUrl)
    }

    /** "M3U playlist" pressed: explain that it arrives in v1.1 (status line). */
    fun onM3uPressed() = _state.update {
        it.copy(infoLine = M3U_INFO_LINE, focusTarget = LoginFocusTarget.M3u)
    }

    /**
     * "Saved on this TV" row picked: fill URL + username, clear the password,
     * move focus to Password (design spec "Sign in", saved-servers state).
     */
    fun onPickSavedServer(id: String) = _state.update { s ->
        val server = s.savedServers.firstOrNull { it.id == id } ?: return@update s
        s.copy(
            serverUrl = server.hostLabel,
            username = server.username,
            password = "",
            serverUrlError = null,
            usernameError = null,
            passwordError = null,
            selectedServerId = server.id,
            infoLine = null,
            focusTarget = LoginFocusTarget.Password,
        )
    }

    /** The screen has moved focus where [LoginUiState.focusTarget] asked. */
    fun onFocusHandled() = _state.update { it.copy(focusTarget = null) }

    /** "Change server" (unreachable state): clear the error, focus Server URL. */
    fun onChangeServer() = _state.update {
        it.copy(phase = LoginPhase.Idle, infoLine = null, focusTarget = LoginFocusTarget.ServerUrl)
    }

    /** "Retry" (unreachable state): attempt the same login again. */
    fun onRetry() {
        val parsed = parsePanelUrl(_state.value.serverUrl) ?: run {
            _state.update { it.copy(phase = LoginPhase.Idle, focusTarget = LoginFocusTarget.ServerUrl) }
            return
        }
        attempt(parsed)
    }

    // ------------------------------------------------------------------ submit

    /** "Sign in": validate, warn about cleartext once, then log in. */
    fun onSubmit() {
        val s = _state.value
        if (s.isSigningIn) return

        val missing = buildList {
            if (s.serverUrl.isBlank()) add(LoginFocusTarget.ServerUrl to "Enter your server URL.")
            if (s.username.isBlank()) add(LoginFocusTarget.Username to "Enter your username.")
            if (s.password.isEmpty()) add(LoginFocusTarget.Password to "Enter your password.")
        }
        if (missing.isNotEmpty()) {
            _state.update {
                it.copy(
                    serverUrlError = missing.firstOrNull { e -> e.first == LoginFocusTarget.ServerUrl }?.second,
                    usernameError = missing.firstOrNull { e -> e.first == LoginFocusTarget.Username }?.second,
                    passwordError = missing.firstOrNull { e -> e.first == LoginFocusTarget.Password }?.second,
                    phase = if (it.phase is LoginPhase.Error) LoginPhase.Idle else it.phase,
                    infoLine = null,
                    focusTarget = missing.first().first,
                )
            }
            return
        }

        val parsed = parsePanelUrl(s.serverUrl) ?: run {
            _state.update {
                it.copy(
                    serverUrlError = INVALID_URL_MESSAGE,
                    phase = LoginPhase.Idle,
                    infoLine = null,
                    focusTarget = LoginFocusTarget.ServerUrl,
                )
            }
            return
        }
        attempt(parsed)
    }

    /**
     * The cleartext sheet's three answers.
     *
     * [CleartextDecision.UseHttps] rewrites the URL and signs in over TLS;
     * [CleartextDecision.ContinueOverHttp] records consent and signs in over
     * plain http; cancel returns focus to the Sign in button. The answer is
     * remembered per server, so neither choice is asked twice.
     */
    fun onSheetUseHttps() {
        val s = _state.value
        if (s.overlay !is LoginOverlay.CleartextSheet) return
        val rewritten = s.serverUrl.trim().replaceFirst("http://", "https://")
        val parsed = parsePanelUrl(rewritten) ?: return
        _state.update { it.copy(serverUrl = rewritten, overlay = LoginOverlay.None) }
        viewModelScope.launch { cleartextConsent.remember(parsed.host, parsed.port, com.rysal.happytv.security.CleartextDecision.UseHttps) }
        attempt(parsed)
    }

    fun onSheetContinueAnyway() {
        val s = _state.value
        if (s.overlay !is LoginOverlay.CleartextSheet) return
        val parsed = parsePanelUrl(s.serverUrl) ?: run {
            _state.update { it.copy(overlay = LoginOverlay.None) }
            return
        }
        _state.update { it.copy(overlay = LoginOverlay.None) }
        viewModelScope.launch {
            cleartextConsent.remember(parsed.host, parsed.port, com.rysal.happytv.security.CleartextDecision.ContinueOverHttp)
        }
        attempt(parsed)
    }

    fun onSheetCancel() = _state.update {
        it.copy(overlay = LoginOverlay.None, focusTarget = LoginFocusTarget.Submit)
    }

    // --------------------------------------------------------------- internals

    /**
     * Gate, then log in. The gate is the one rule the handoff calls out as
     * non-negotiable: a plain-`http://` URL for a server the user has not
     * answered for **before** raises the unencrypted-server sheet, never a
     * silent request.
     */
    private fun attempt(parsed: ParsedPanel) {
        viewModelScope.launch {
            val consent = cleartextConsent.decision(parsed.host, parsed.port)
            if (!parsed.useTls && consent == null) {
                _state.update {
                    it.copy(
                        overlay = LoginOverlay.CleartextSheet(hostLabel = parsed.hostLabel),
                        phase = if (it.phase is LoginPhase.Error) LoginPhase.Idle else it.phase,
                        // F-66: the sheet is modal and "Use HTTPS" is its first
                        // stop (design spec §"Unencrypted-server sheet": "Use HTTPS"
                        // (primary, focused; …)"). Without this ask the sheet
                        // renders three buttons that nothing owns focus for, so
                        // the remote cannot answer it and a plain-HTTP provider
                        // cannot be signed into at all.
                        focusTarget = LoginFocusTarget.SheetUseHttps,
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    phase = LoginPhase.Loading,
                    overlay = LoginOverlay.None,
                    serverUrlError = null,
                    usernameError = null,
                    passwordError = null,
                    infoLine = null,
                    focusTarget = null,
                )
            }

            val s = _state.value
            val result = try {
                withTimeoutOrNull(loginTimeoutMillis) {
                    withContext(io) {
                        auth.login(
                            host = parsed.host,
                            port = parsed.port,
                            useTls = parsed.useTls || consent == com.rysal.happytv.security.CleartextDecision.UseHttps,
                            username = s.username,
                            password = s.password,
                            displayName = parsed.displayName,
                        )
                    }
                }
            } catch (t: TimeoutCancellationException) {
                null
            }

            when {
                // No answer inside the watchdog: the handoff's "also used after
                // 15 seconds with no answer" path.
                result == null -> _state.update {
                    it.copy(
                        phase = LoginPhase.Error(LoginErrorKind.Unreachable, CANT_REACH_MESSAGE),
                        focusTarget = LoginFocusTarget.Retry,
                    )
                }

                result is LoginResult.Success -> {
                    // Credentials are already persisted by the coordinator
                    // (encrypted store + Room profile + user_info cache). Kick
                    // off the 3-tile refresh without waiting for it — the
                    // dashboard opens in its first-load state.
                    viewModelScope.launch { runCatching { auth.refreshAll() } }
                    _state.update { it.copy(phase = LoginPhase.Authenticated(navigateToMain = true)) }
                }

                else -> {
                    val message = (result as LoginResult.Failure).message
                    if (message.isUnreachableMessage()) {
                        _state.update {
                            it.copy(
                                phase = LoginPhase.Error(LoginErrorKind.Unreachable, CANT_REACH_MESSAGE),
                                focusTarget = LoginFocusTarget.Retry,
                            )
                        }
                    } else {
                        // The panel's own message ("Subscription expired", …)
                        // is shown word for word (design spec "Sign in", wrong password).
                        _state.update {
                            it.copy(
                                phase = LoginPhase.Error(LoginErrorKind.WrongCredentials, message),
                                focusTarget = LoginFocusTarget.Password,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadSavedServers() {
        val servers = withContext(io) { savedServersSource.savedServers() }
        _state.update { current ->
            val mostRecent = servers.firstOrNull()
            val seeded = current.copy(
                savedServers = servers,
                savedServersLoaded = true,
                selectedServerId = mostRecent?.id ?: current.selectedServerId,
            )
            if (mostRecent == null || seeded.serverUrl.isNotBlank()) {
                // First run, or the user already typed something: leave their
                // work alone. First run puts focus on Server URL.
                seeded.copy(focusTarget = seeded.focusTarget ?: LoginFocusTarget.ServerUrl)
            } else {
                // Saved servers: Home picked, URL + username filled, password
                // empty, focus on Password.
                seeded.copy(
                    serverUrl = mostRecent.hostLabel,
                    username = mostRecent.username,
                    password = "",
                    focusTarget = LoginFocusTarget.Password,
                )
            }
        }
    }

    /**
     * True when [RefreshCoordinator.login] reported "we never got an answer"
     * rather than a refusal. Its [LoginResult.Failure] carries only text, so
     * the discriminator is that message's opening — kept next to the constant
     * it mirrors in `RefreshCoordinator`.
     */
    private fun String.isUnreachableMessage(): Boolean = startsWith(COULDNT_REACH_PREFIX)

    companion object {
        /** Handoff: "also used after 15 seconds with no answer". */
        const val DEFAULT_LOGIN_TIMEOUT_MILLIS = 15_000L

        const val CANT_REACH_MESSAGE = "Can’t reach the server. Check the URL and your network."
        const val M3U_INFO_LINE = "Playlists from an M3U link arrive in v1.1."
        const val INVALID_URL_MESSAGE = "Enter a valid server URL, like provider.example:8080."

        /** Mirrors `RefreshCoordinator`'s "Couldn't reach …" failure prefix. */
        private const val COULDNT_REACH_PREFIX = "Couldn't reach "
    }
}

/**
 * A user-typed panel address, resolved the way [com.rysal.happytv.data.network.panelOrigin]
 * will see it.
 *
 * A missing scheme is treated as https and never falls back to http; the
 * URL formats dialog says so ("You can leave off https://. HappyTV uses an
 * encrypted connection unless you type http://."). This screen only decides
 * which scheme to *ask* for and whether the cleartext sheet is needed.
 */
internal data class ParsedPanel(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    /** `host:port`, for the "Signing in to …" status line and the sheet. */
    val hostLabel: String,
    /** Panel display name — host without a port. */
    val displayName: String,
)

/**
 * OkHttp's URL parser (the OkHttp 4 Kotlin replacement for the handoff's
 * `okio.HttpUrl.parse`). Returns null when [raw] is not a usable panel
 * address, which is what drives the field's inline error.
 */
internal fun parsePanelUrl(raw: String): ParsedPanel? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val withScheme = when {
        text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> text
        else -> "https://$text"
    }
    val url = withScheme.toHttpUrlOrNull() ?: return null
    val host = url.host.trim()
    if (host.isEmpty()) return null
    val useTls = url.scheme.equals("https", ignoreCase = true)
    val port = url.port
    return ParsedPanel(
        host = host,
        port = port,
        useTls = useTls,
        hostLabel = "$host:$port",
        displayName = host,
    )
}
