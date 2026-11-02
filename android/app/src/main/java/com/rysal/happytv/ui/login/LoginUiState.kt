package com.rysal.happytv.ui.login

/**
 * Sign-in screen state — design spec "Screens: splash, launcher banner and
 * sign in" plus the task's "State details".
 *
 * The screen is a pure function of this data class. [LoginViewModel] is the
 * only writer; the composables own nothing but transient focus/editing state
 * (which field the D-pad is on, whether a field is being typed into).
 */

/** One row of the "Saved on this TV" list. */
data class SavedServer(
    /** Stable key — the Room profile row today, one per saved server later. */
    val id: String,
    /** Display name, e.g. "Home". */
    val name: String,
    /** `host:port` as the handoff shows it. */
    val hostLabel: String,
    val username: String,
    /** "Last signed in Oct 14", or null when we have no timestamp. */
    val lastSignedInLabel: String?,
    /** True for the server currently signed in (accent check at the right). */
    val inUse: Boolean,
)

/** Where the D-pad should land next. Consumed once, then cleared. */
enum class LoginFocusTarget {
    SavedServer,
    ServerUrl,
    UrlFormatsLink,
    Username,
    Password,
    Eye,
    Remember,
    Submit,
    M3u,
    Retry,
    ChangeServer,
    FormatOption,
    FormatClose,
    SheetUseHttps,
    SheetContinueAnyway,
    SheetCancel,
}

/** Overlay above the card: the formats dialog or the unencrypted-server sheet. */
sealed interface LoginOverlay {
    object None : LoginOverlay
    object UrlFormats : LoginOverlay
    data class CleartextSheet(val hostLabel: String) : LoginOverlay
}

/** Why a login attempt failed — it decides the status line and where focus goes. */
enum class LoginErrorKind {
    /** Missing/invalid fields: the messages live on the fields, not the status line. */
    Fields,

    /** The panel refused the credentials (`auth: 0`). Focus returns to Password. */
    WrongCredentials,

    /** No answer in 15 s, or the panel could not be reached at all. */
    Unreachable,
}

/**
 * The sign-in state machine, as the task names it:
 * `Idle`, `Loading`, `Error(msg)`, `Authenticated`.
 */
sealed interface LoginPhase {
    object Idle : LoginPhase
    object Loading : LoginPhase
    data class Error(val kind: LoginErrorKind, val message: String) : LoginPhase

    /** Set once the panel accepted the credentials; the activity navigates. */
    data class Authenticated(val navigateToMain: Boolean = true) : LoginPhase
}

data class LoginUiState(
    // ---------------------------------------------------------------- the form
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** "Remember me on this TV", checked by default. */
    val rememberMe: Boolean = true,

    // ------------------------------------------------------- field-level errors
    val serverUrlError: String? = null,
    val usernameError: String? = null,
    val passwordError: String? = null,

    // ------------------------------------------------------------------ phase
    val phase: LoginPhase = LoginPhase.Idle,

    /**
     * Transient info line shown in the status slot while [phase] is Idle —
     * the M3U "arrives in v1.1" note. Errors always win over it.
     */
    val infoLine: String? = null,

    // ------------------------------------------------------------ saved servers
    val savedServers: List<SavedServer> = emptyList(),
    val selectedServerId: String? = null,
    /** True once saved servers were loaded (the card recentres on first run). */
    val savedServersLoaded: Boolean = false,

    // ----------------------------------------------------------------- overlays
    val overlay: LoginOverlay = LoginOverlay.None,

    // --------------------------------------------------------------- focus ask
    /** One-shot request from the ViewModel; the screen focuses it and clears. */
    val focusTarget: LoginFocusTarget? = null,
) {
    val isSigningIn: Boolean get() = phase is LoginPhase.Loading

    /** Can't-reach-server state: Retry/Change server replace Sign in, M3U hides. */
    val isUnreachable: Boolean
        get() = (phase as? LoginPhase.Error)?.kind == LoginErrorKind.Unreachable

    /** Signing in, or a failed attempt's message, or the M3U note, or help. */
    val hasSavedServers: Boolean get() = savedServers.isNotEmpty()

    /** The panel accepted us — the activity cross-fades to MainActivity. */
    val isAuthenticated: Boolean
        get() = (phase as? LoginPhase.Authenticated)?.navigateToMain == true

    /** Error text for the status slot, or null when the slot shows help/info. */
    val statusError: String?
        get() = (phase as? LoginPhase.Error)?.takeIf { it.kind != LoginErrorKind.Fields }?.message
}
