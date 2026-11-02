package com.rysal.happytv.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-68 — the classifier that decides whether the error card may say "offline".
 *
 * The QA repro is one log line: `errorCode=4001
 * errorCodeName=ERROR_CODE_DECODER_INIT_FAILED … [3840, 2160 …]` on a channel
 * the panel is serving perfectly. Everything here exists so that line can
 * never again end in "This channel is offline. Try another."
 *
 * The negative tests matter as much as the positive ones: a classifier that
 * called everything "not supported" would be a different lie, so the default
 * for every non-decoder code is pinned here too.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerErrorClassifierTest {

    private fun classify(code: Int?, cause: Throwable? = null) =
        PlayerErrorClassifier.classify(code, cause)

    @Test
    fun `decoder init failure is a capability wall not an offline channel`() {
        // The exact repro code, F-68.
        assertEquals(
            PlayerErrorClassifier.PlayerFailure.UnsupportedFormat,
            classify(4001),
        )
    }

    @Test
    fun `every decoder-side code maps to the capability wall`() {
        val codes = listOf(
            4001, // ERROR_CODE_DECODER_INIT_FAILED — the QA repro
            4002, // ERROR_CODE_DECODER_QUERY_FAILED
            4004, // ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
            4005, // ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            7000, // ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED
        )
        codes.forEach { code ->
            assertEquals(
                "error code $code must be reported as a device-capability wall",
                PlayerErrorClassifier.PlayerFailure.UnsupportedFormat,
                classify(code),
            )
        }
    }

    @Test
    fun `network, parse and DRM codes stay on the honest offline path`() {
        val codes = listOf(
            1000, // ERROR_CODE_UNSPECIFIED
            1002, // ERROR_CODE_BEHIND_LIVE_WINDOW
            2001, // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            2004, // ERROR_CODE_IO_BAD_HTTP_STATUS
            2005, // ERROR_CODE_IO_FILE_NOT_FOUND
            3002, // ERROR_CODE_PARSING_MANIFEST_MALFORMED
            3003, // ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            6001, // ERROR_CODE_DRM_SCHEME_UNSUPPORTED
        )
        codes.forEach { code ->
            assertEquals(
                "error code $code is a stream failure, not a codec wall",
                PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
                classify(code),
            )
        }
    }

    @Test
    fun `an audio-only decoder failure is not a whole-video-format wall`() {
        // 5001-5004 are the audio renderer's init failures. Claiming "this TV
        // can't play this channel's video" about an audio problem would be its
        // own kind of lie, so they keep the stream-failure copy.
        listOf(5001, 5004).forEach { code ->
            assertEquals(
                "audio decoder failure $code must not be reported as an unsupported video format",
                PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
                classify(code),
            )
        }
    }

    @Test
    fun `a mid-stream decoding failure is not a capability wall`() {
        assertEquals(
            PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
            classify(4003),
        )
    }

    @Test
    fun `a null or unknown code falls back to the stream failure`() {
        assertEquals(
            PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
            classify(null),
        )
        assertEquals(
            PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
            classify(999_999),
        )
    }

    @Test
    fun `a DecoderInitializationException in the cause chain wins over the outer code`() {
        // On this hardware the vendor decoder reports the failure with a
        // neighbouring code and nests the real reason beneath it, so the cause
        // chain is checked as well as the code itself.
        val nested = IllegalStateException(
            "MediaCodecVideoRenderer error",
            decoderInitFailure(),
        )
        assertEquals(
            PlayerErrorClassifier.PlayerFailure.UnsupportedFormat,
            classify(1000, nested),
        )
    }

    @Test
    fun `the real Media3 decoder exception is recognised through the cause chain`() {
        // [PlayerErrorClassifier] matches the exception by class *name* so it
        // can be constructed on a plain JVM without dragging an @UnstableApi
        // ExoPlayer type into the production file's signature. That decision
        // is only safe while the name still matches Media3's own class — this
        // test constructs the real exception and fails the moment a Media3
        // upgrade renames it.
        val real = decoderInitFailure()
        assertEquals(
            "the classifier must still recognise Media3's own decoder exception",
            PlayerErrorClassifier.PlayerFailure.UnsupportedFormat,
            classify(1000, real),
        )
    }

    @Test
    fun `classifier codes match Media3's own PlaybackException constants`() {
        // The classifier keys on literal ints (so it stays plain-JVM and
        // free of @UnstableApi types). Pin each literal to Media3's named
        // constant so a Media3 upgrade that renumbers a code fails here
        // instead of silently flipping the card copy.
        val capability = mapOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED to 4001,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED to 4002,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES to 4004,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED to 4005,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED to 7000,
        )
        capability.forEach { (named, literal) ->
            assertEquals(literal, named)
            assertEquals(PlayerErrorClassifier.PlayerFailure.UnsupportedFormat, classify(named))
        }
        // Real outages, by Media3's names: these must keep the offline path
        // (retry budget, "Retrying...", then the offline card).
        listOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            // 4003: a decoder that was working failed mid-stream, which is a
            // glitch and not a capability wall, so it keeps retry-then-offline.
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        ).forEach { code ->
            assertEquals(
                "Media3 code $code must not be reported as an unsupported format",
                PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
                classify(code),
            )
        }
    }

    @Test
    fun `a network exception in the cause chain is still a stream failure`() {
        // Outer code and cause both say "network": nothing here may be read
        // as a capability wall.
        val cause = java.io.IOException("connection reset", java.net.SocketTimeoutException("timeout"))
        assertEquals(
            PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
            classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, cause),
        )
        assertEquals(
            PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
            classify(null, cause),
        )
    }

    @Test
    fun `a self-referencing cause chain terminates`() {
        // Defensive: a pathological cause chain must not spin forever on a
        // path that runs inside a player error callback.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(
            PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
            classify(1000, a),
        )
    }

    @Test
    fun `a refused redirect anywhere in the cause chain is an insecure redirect`() {
        // Media3 wraps the interceptor's exception in HttpDataSourceException (code 2001).
        val wrapped = java.io.IOException("Unable to connect", InsecureRedirectException())
        assertEquals(PlayerErrorClassifier.PlayerFailure.InsecureRedirect, classify(2001, wrapped))
        assertEquals(PlayerErrorClassifier.PlayerFailure.InsecureRedirect, classify(null, InsecureRedirectException()))
    }

    @Test
    fun `an ordinary network failure is not an insecure redirect`() {
        assertEquals(
            PlayerErrorClassifier.PlayerFailure.StreamUnavailable,
            classify(2001, java.io.IOException("connection reset")),
        )
    }

    /**
     * The real `MediaCodecRenderer.DecoderInitializationException`, built the
     * way Media3 builds it for a 3840×2160 HEVC format — the exact shape in
     * QA's logcat repro.
     *
     * The trailing int is Media3's private `NO_SUITABLE_DECODER_ERROR`
     * constant (used to build the message); its value is irrelevant to
     * classification, which only looks at the type.
     */
    private fun decoderInitFailure(): Throwable {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setWidth(3840)
            .setHeight(2160)
            .build()
        return MediaCodecRenderer.DecoderInitializationException(
            format,
            IllegalStateException("Decoder init failed: c2.amlogic.hevc.decoder"),
            /* secureDecoderRequired = */ false,
            /* errorCode = */ 4001,
        )
    }
}