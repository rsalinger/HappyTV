package com.rysal.happytv.ui.player

import com.rysal.happytv.data.BackWhileWatching

/**
 * Pure player-lifecycle policy, kept free of Android types so it is unit
 * testable and shared by every host of a [PlaybackHost].
 */
internal object PlayerPolicy {

    /**
     * Whether a player rebuilt after a non-finishing stop (Home, screen off)
     * seeks back to where it was (F-56).
     *
     * Movies and replays are fixed-length streams, so the position means what
     * it says. Live moves at 1.0x: restoring a stale position would leave the
     * viewer permanently behind the live edge, so live starts over at the edge.
     */
    fun resumeOnStart(mode: PlaybackMode): Boolean = when (mode) {
        PlaybackMode.Movie, PlaybackMode.Replay -> true
        PlaybackMode.Live -> false
    }

    /**
     * Back resolution for the player (spec #7 section 5, #16).
     *
     * | playback | short Back | long-press Back |
     * |----------|------------|-----------------|
     * | Live | Show channels: [BackAction.ToPreview]; Stop playback: [BackAction.StopPlayback] | [BackAction.StopPlayback] |
     * | Replay | [BackAction.Leave] (no preview for replay) | [BackAction.Leave] |
     * | Movie | [BackAction.Leave] (the leave confirmation belongs to the screen) | [BackAction.Leave] |
     */
    fun backAction(
        mode: PlaybackMode,
        backWhileWatching: BackWhileWatching,
        longPress: Boolean,
    ): BackAction = when (mode) {
        PlaybackMode.Live -> when {
            longPress -> BackAction.StopPlayback
            backWhileWatching == BackWhileWatching.SHOW_CHANNELS -> BackAction.ToPreview
            else -> BackAction.StopPlayback
        }
        PlaybackMode.Replay, PlaybackMode.Movie -> BackAction.Leave
    }

    /** OK on [tappedStreamId] in the combined screen while the host is in [hostMode]. */
    fun channelOk(
        hostMode: HostMode,
        playingStreamId: String?,
        tappedStreamId: String,
    ): ChannelOkAction =
        if (hostMode == HostMode.Preview && playingStreamId == tappedStreamId) {
            ChannelOkAction.EnterFull
        } else {
            ChannelOkAction.PlayFull
        }

    /**
     * Whether the Live TV host stops its player when the activity stops (Home,
     * screen off, Settings opened over it): always for live, never resumed
     * (design note on #11: no auto-resume, the provider connection is freed).
     * A finishing activity is handled by the destroy path instead.
     */
    fun stopHostOnActivityStop(hostMode: HostMode, finishing: Boolean): Boolean =
        hostMode != HostMode.Idle && !finishing
}

/**
 * What the Live TV host is showing (#16). The one [PlayerSurface] is never
 * re-parented: these are only its bounds and who owns the D-pad.
 *
 * - [Idle]: nothing plays; the surface is hidden and the preview slot shows art.
 * - [Full]: the surface fills the screen with the player chrome on top; the
 *   combined screen underneath is composed but takes no focus.
 * - [Preview]: the surface sits in the 448 x 252 slot; the combined screen has
 *   the D-pad.
 */
internal enum class HostMode { Idle, Full, Preview }

/** What a Back press in the player resolves to. */
internal enum class BackAction {
    /** Shrink full-screen live playback into the preview window; it keeps playing. */
    ToPreview,

    /** Stop playback and show the combined screen without a preview. */
    StopPlayback,

    /** Leave the player screen ([PlayerActivity]: movies and replay). */
    Leave,
}

/** What OK on a channel in the combined screen does. */
internal enum class ChannelOkAction {
    /** Start this channel full screen (nothing playing, or a different channel: a zap). */
    PlayFull,

    /** This channel is the preview: enlarge it, no reload. */
    EnterFull,
}

/**
 * Back key sequencing for #16: the short press acts on key-up (so a hold can be
 * told apart), a hold fires once and swallows the release. The caller consumes
 * the key-down so the system never also sees a Back.
 */
internal class BackKeyGate(
    private val onShortPress: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private var down = false
    private var longFired = false

    /** Back key-down. A repeat or the long-press flag fires the hold, once. */
    fun onDown(repeatCount: Int, longPressFlag: Boolean = false) {
        if (repeatCount == 0 && !longPressFlag) {
            down = true
            longFired = false
            return
        }
        if (down && !longFired) {
            longFired = true
            onLongPress()
        }
    }

    /** Back key-up: the short press, unless the hold already fired or no down was seen here. */
    fun onUp() {
        val shortPress = down && !longFired
        down = false
        longFired = false
        if (shortPress) onShortPress()
    }

    /** Forget a half-seen press (the mode changed mid-press). */
    fun reset() {
        down = false
        longFired = false
    }
}

/** What the activity does with one Back key event ([LongBackSequence]). */
internal enum class LongBackDecision {
    /** Not ours: let Compose and the system handle it. */
    Pass,

    /** A hold in full screen: stop playback now. Consume the event. */
    FireStop,

    /** A 2 s hold in the Live TV preview (#138): back to full screen of the playing channel. */
    FireReturn,

    /** The rest of a hold that already fired (repeats, key-up): swallow. */
    Consume,
}

/** How long Back is held in the Live TV preview to return to full screen (#138). */
internal const val PREVIEW_HOLD_MILLIS = 2_000L

/**
 * Activity-level long-press Back (#16 re-QA, #138). Compose-level handling needs
 * focus inside the player, and for about a second after OK enters Full there is
 * none, so the activity watches the key first.
 *
 * - Full: a hold (repeat or FLAG_LONG_PRESS) stops playback, once.
 * - Preview: platform repeats do nothing. The activity starts a [PREVIEW_HOLD_MILLIS]
 *   timer on the key-down (repeats are not guaranteed to arrive) and asks
 *   [onHoldTimeout] when it runs out: still down in Preview, it returns to full
 *   screen, once. A release before that is a short Back.
 * - Idle: nothing new.
 *
 * Once fired, the whole remaining sequence, key-up included, is consumed, so it
 * can never also trigger the short Back on release.
 */
internal class LongBackSequence {
    private var fired = false
    private var pressed = false

    fun onDown(mode: HostMode, repeatCount: Int, longPressFlag: Boolean): LongBackDecision {
        if (repeatCount == 0 && !longPressFlag) {
            fired = false
            pressed = true
            return LongBackDecision.Pass
        }
        if (fired) return LongBackDecision.Consume
        return when (mode) {
            HostMode.Idle, HostMode.Preview -> LongBackDecision.Pass
            HostMode.Full -> { fired = true; LongBackDecision.FireStop }
        }
    }

    /** The [PREVIEW_HOLD_MILLIS] timer started on the key-down ran out. */
    fun onHoldTimeout(mode: HostMode): LongBackDecision {
        if (!pressed || fired || mode != HostMode.Preview) return LongBackDecision.Pass
        fired = true
        return LongBackDecision.FireReturn
    }

    fun onUp(): LongBackDecision {
        val wasFired = fired
        fired = false
        pressed = false
        return if (wasFired) LongBackDecision.Consume else LongBackDecision.Pass
    }
}

/**
 * Activity-level Back routing shared by the hosts (#66). Returns true when the
 * event is consumed: a hold (repeat or FLAG_LONG_PRESS) calls [stop] once and the
 * rest of the sequence, key-up included, is swallowed so it cannot also act as a
 * short Back. A plain down/up passes through untouched.
 */
internal fun LongBackSequence.routeBack(
    event: android.view.KeyEvent,
    mode: HostMode,
    stop: () -> Unit,
): Boolean {
    if (event.keyCode != android.view.KeyEvent.KEYCODE_BACK) return false
    val decision = when (event.action) {
        android.view.KeyEvent.ACTION_DOWN -> onDown(mode, event.repeatCount, event.isLongPress)
        android.view.KeyEvent.ACTION_UP -> onUp()
        else -> LongBackDecision.Pass
    }
    return when (decision) {
        LongBackDecision.FireStop -> { stop(); true }
        LongBackDecision.Consume -> true
        LongBackDecision.Pass, LongBackDecision.FireReturn -> false
    }
}
