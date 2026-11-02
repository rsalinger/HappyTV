package com.rysal.happytv.ui.player

/**
 * F-68 — what the player error card is allowed to say.
 *
 * ## Why this exists
 *
 * Every `Player.Listener.onPlayerError` used to land in one bucket: three
 * errors inside 30 s → [PlayerUiState.Offline] → "This channel is offline.
 * Try another." That sentence is a *diagnosis*, and for a whole class of
 * errors it is simply false. QA's repro (a Chromecast HD, 2026-10-21) pressed OK on a
 * 4K channel (`UK: Sports Main Event UHD`) — a perfectly healthy 4K/HEVC feed the panel was serving fine — and
 * got told the channel was offline:
 *
 * ```
 * W PlayerF48: onPlayerError: errorCode=4001 errorCodeName=ERROR_CODE_DECODER_INIT_FAILED
 *   message=MediaCodecVideoRenderer error, index=0,
 *   format=Format(0, null, null, video/hevc, hvc1.2.4.L153.B0, -1, null, [3840, 2160,
 *   -1.0, ColorInfo(BT2020, Limited range, ST2014/PQ
 * W PlayerF48: Caused by: … DecoderInitializationException: Decoder init failed:
 *   c2.amlogic.hevc.decoder, Format(… [3840, 2160
 * ```
 *
 * The stream arrived, the request succeeded, and the box simply cannot decode
 * it. "Try another" sends the user hunting for a channel that was never
 * broken. The card has to name the real cause: a video format this TV can't
 * decode.
 *
 * ## What counts as a capability problem
 *
 * Media3's decoder-side error codes (media3 1.3.1,
 * `PlaybackException.ERROR_CODE_*`):
 *
 * | Code | Name                              | Means                                       |
 * |------|-----------------------------------|---------------------------------------------|
 * | 4001 | `ERROR_CODE_DECODER_INIT_FAILED`  | no decoder could be created for the format  |
 * | 4002 | `ERROR_CODE_DECODER_QUERY_FAILED` | the decoder list could not be queried      |
 * | 4004 | `…_FORMAT_EXCEEDS_CAPABILITIES`   | resolution/profile past what the box can do |
 * | 4005 | `…_FORMAT_UNSUPPORTED`            | codec/container the box cannot handle       |
 * | 7000 | `…_VIDEO_FRAME_PROCESSOR_INIT_FAILED` | the frame-processing stage could not start |
 *
 * All five say the same thing to a user at 9 PM: *this box can't play this*.
 * That is a capability wall, not a dead channel — and it is worth calling
 * out separately from an audio-only init failure (5001-5004), which is a
 * different wall and keeps the stream's honest "something went wrong" copy
 * rather than claiming the whole format is unsupported.
 *
 * The classification is deliberately conservative in the other direction: an
 * unknown code, a null error, or an IO/network/parse/DRM code is NOT a
 * capability problem. Guessing "not supported" on a 500 would be a worse lie
 * than "offline", so the default stays [PlaybackProblem.StreamUnavailable].
 *
 * ## Note on `4003` (DECODING_FAILED)
 *
 * Deliberately **not** a capability code. It means a decoder that was
 * created and had been working failed mid-stream, which is what a corrupt
 * frame or a flaky feed looks like. It keeps the normal retry-then-offline
 * path. A decoder wall that arrives under a neighbouring code is still
 * caught through the `DecoderInitializationException` cause-chain marker.
 */
internal object PlayerErrorClassifier {

    /**
     * Codec/renderer capability failures: the device, not the stream.
     *
     * These map to [PlayerFailure.UnsupportedFormat].
     */
    private val CAPABILITY_ERROR_CODES: Set<Int> = setOf(
        4001, // ERROR_CODE_DECODER_INIT_FAILED
        4002, // ERROR_CODE_DECODER_QUERY_FAILED
        4004, // ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        4005, // ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        7000, // ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED
    )

    /**
     * The failure class behind the error card.
     *
     * Only [UnsupportedFormat] and [StreamUnavailable] are reachable from
     * [PlayerUiState.Offline]; [UnsupportedFormat] exists to stop the one
     * error that the generic copy lies about.
     */
    enum class PlayerFailure {
        /** The channel/stream is not serving. The honest "try again" path. */
        StreamUnavailable,

        /**
         * The device cannot decode this stream's video format — a 4K/HEVC
         * feed on an HD dongle, a codec the box has no decoder for.
         *
         * The stream is being served fine; this TV just has no decoder for
         * its video format. The card says so and points at another channel.
         */
        UnsupportedFormat,

        /**
         * #21: [RedirectGuard] refused an HTTPS to HTTP redirect whose target
         * carried the sign-in details. A policy stop, not a stream fault:
         * retrying would be refused again, so there is no retry budget.
         */
        InsecureRedirect,
    }

    /**
     * Classify one `Player.Listener.onPlayerError` payload.
     *
     * @param errorCode the Media3 `PlaybackException.errorCode`, or null when
     *   the platform reported a failure without one (in which case the cause
     *   chain is the only signal available).
     * @param cause the exception's cause chain, when the platform supplied
     *   one; a `DecoderInitializationException` in here is a decoder wall
     *   regardless of the outer code.
     */
    fun classify(errorCode: Int?, cause: Throwable?): PlayerFailure =
        if (cause?.isInsecureRedirect() == true) {
            PlayerFailure.InsecureRedirect
        } else if (errorCode in CAPABILITY_ERROR_CODES || cause?.isDecoderInitialization() == true) {
            PlayerFailure.UnsupportedFormat
        } else {
            PlayerFailure.StreamUnavailable
        }

    /** #21: the guard's typed refusal anywhere in the cause chain (Media3 wraps it). */
    private fun Throwable.isInsecureRedirect(): Boolean {
        var current: Throwable? = this
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is InsecureRedirectException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Walk the cause chain for a `DecoderInitializationException`.
     *
     * Matched by class **name** on purpose: the class lives in
     * `androidx.media3.exoplayer.mediacodec`, an `@UnstableApi` type that
     * would otherwise drag the whole ExoPlayer artifact into this file's
     * signature — and this classifier has to stay constructible from a plain
     * JVM unit test with no Media3 runtime (the ViewModel test suite does
     * exactly that). Media3's own renderer nests this exception, never a
     * subclass, so the name is a stable marker.
     */
    private fun Throwable.isDecoderInitialization(): Boolean {
        var current: Throwable? = this
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current::class.java.name == DECODER_INIT_EXCEPTION) return true
            current = current.cause
        }
        return false
    }

    private const val DECODER_INIT_EXCEPTION =
        "androidx.media3.exoplayer.mediacodec.MediaCodecRenderer\$DecoderInitializationException"
}