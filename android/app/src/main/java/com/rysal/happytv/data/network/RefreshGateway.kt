package com.rysal.happytv.data.network

import kotlinx.coroutines.flow.StateFlow

/**
 * The session's sign-in state — the live source of truth the dashboard header
 * observes (F-45).
 *
 * @param signedIn true while the encrypted credential store holds a complete
 *   credential set.
 * @param lastSignedInAtUtcMillis wall-clock of the last successful sign-in
 *   (epoch millis, UTC) — `0` when there is none. The header renders it as
 *   "Signed in just now" / "Last signed in 12 minutes ago".
 * @param resolved false only while a cold-start read of the credential store
 *   is still in flight: [signedIn] is then not yet known, so the UI must show
 *   neither a signed-in nor a signed-out state (issue #30). Defaults to true
 *   so every explicit value is a resolved one.
 */
data class SessionState(
    val signedIn: Boolean = false,
    val lastSignedInAtUtcMillis: Long = 0L,
    val resolved: Boolean = true,
)

/**
 * The slice of [RefreshCoordinator] the dashboard talks to — an interface so
 * the ViewModel can be driven by a recording fake in tests (the acceptance
 * asks for a "fake coordinator that records the call list") while the app
 * keeps using the real coordinator through [com.rysal.happytv.data.Graph].
 *
 * Every method mirrors the coordinator one-to-one; nothing is re-typed.
 */
interface RefreshGateway {
    /** The three tiles' states — one stream per tile, indexed by [StreamType]. */
    val progress: StateFlow<RefreshProgress>

    /** Refresh all three tiles in parallel. */
    suspend fun refreshAll()

    /** Per-tile refreshes — the dashboard's status-pill "Refresh" buttons. */
    suspend fun refreshLiveOnly()

    suspend fun refreshVodOnly()

    suspend fun refreshSeriesOnly()

    /** True when the encrypted credential store holds a credential set. */
    suspend fun isSignedIn(): Boolean

    /**
     * The live session state — observe this instead of one-shot reads (F-45).
     *
     * Emits the persisted session on collection (a cold start with remembered
     * credentials is already `signedIn`), then again on every change: [login]
     * publishes the new sign-in + stamp the moment credentials are persisted,
     * [signOut] publishes the empty state. The dashboard header renders
     * whatever this holds — "Signed in just now" / "Last signed in …" /
     * "Not signed in" — so it can never lag a sign-in until some later
     * re-prime or app restart.
     */
    val sessionState: StateFlow<SessionState>

    /** Wipe credentials + profile; catalog tables are left alone. */
    suspend fun signOut()

    /** `provider.example:8080` for the About panel — "Not signed in" without one. */
    suspend fun serverLabel(): String
}
