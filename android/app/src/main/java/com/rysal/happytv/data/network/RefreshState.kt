package com.rysal.happytv.data.network

import com.rysal.happytv.data.entity.StreamType

/**
 * State of one refresh — observed by the dashboard's three progress tiles.
 *
 * Deliberately a tiny sealed hierarchy rather than booleans + error string:
 * a tile must distinguish "never ran" from "running, 2 of 3 steps done" from
 * "finished with 12 431 channels in 3.4 s", because the usual IPTV-player UX we
 * follow shows exactly those three things (Idle → progress bar →
 * "Last updated Xs ago", with Error + retry replacing the last one).
 */
sealed interface RefreshState {

    /** Nothing has been fetched in this process yet (first launch, or after sign-out). */
    data object Idle : RefreshState

    /**
     * A refresh is running; [step] of [ofSteps] sub-steps are finished. The
     * coordinator's three sub-steps per tile are: categories fetched (1),
     * catalog rows fetched (2), written to Room (3).
     */
    data class Loading(val step: Int, val ofSteps: Int) : RefreshState

    /**
     * The tile's catalog was refreshed successfully.
     *
     * @param channels rows written for this catalog — live channels, movies or
     * shows depending on which tile it is (the protocol calls all of them
     * "streams"; the tiles do not).
     * @param ms wall-clock duration of the whole tile refresh.
     */
    data class Done(val channels: Int, val ms: Long) : RefreshState

    /**
     * The refresh failed. Whatever the panel *did* return is still persisted
     * (a failed categories call never deletes the old categories), so a
     * mid-refresh failure leaves the previous catalog usable behind this
     * state — the UI's retry is the only action needed.
     */
    data class Error(val message: String) : RefreshState
}

/**
 * The three tiles' states in one value, so the dashboard collects a single
 * `StateFlow` instead of three. [get] and [with] index by [StreamType].
 */
data class RefreshProgress(
    val live: RefreshState = RefreshState.Idle,
    val vod: RefreshState = RefreshState.Idle,
    val series: RefreshState = RefreshState.Idle,
) {
    operator fun get(type: StreamType): RefreshState = when (type) {
        StreamType.LIVE -> live
        StreamType.VOD -> vod
        StreamType.SERIES -> series
    }

    fun with(type: StreamType, state: RefreshState): RefreshProgress = when (type) {
        StreamType.LIVE -> copy(live = state)
        StreamType.VOD -> copy(vod = state)
        StreamType.SERIES -> copy(series = state)
    }

    val hasError: Boolean
        get() = live is RefreshState.Error || vod is RefreshState.Error || series is RefreshState.Error

    /** Total rows across every tile that finished successfully. */
    val doneChannels: Int
        get() = listOf(live, vod, series).filterIsInstance<RefreshState.Done>().sumOf { it.channels }
}

/** Human label for a catalog — same words the dashboard's tiles use. */
val StreamType.label: String
    get() = when (this) {
        StreamType.LIVE -> "Live TV"
        StreamType.VOD -> "Movies"
        StreamType.SERIES -> "Series"
    }
