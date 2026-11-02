package com.rysal.happytv.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.VodStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Player state-machine coverage — every branch of the design spec per-mode table
 * and the task's acceptance list, against recording fakes (the dashboard's
 * testing pattern: the VM only ever sees [PlayerRepository] + [PlayerHandle]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // 2024-04-19T16:00:00Z — the protocol example moment.
    private val now = 1_713_542_400_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ load

    @Test
    fun `live channel plays an m3u8 url when the account allows it`() = runTest {
        val connection = PlayerConnection("panel.example", 8080, false, "u", "p")
        val repo = fakeRepository(
            connection = connection,
            live = mapOf("7" to liveStream("7", num = 101, name = "Northbay News", archive = true)),
            allowedFormats = listOf("ts", "m3u8"),
        )
        val vm = viewModel(PlayerLaunch.live("7"), repo)

        val content = vm.readyContent()
        assertEquals(PlaybackMode.Live, content.mode)
        assertEquals("http://panel.example:8080/live/u/p/7.m3u8", content.url)
        assertEquals("101 · Northbay News", content.topLine)
        assertEquals("Northbay News", content.title)
        assertEquals("NN", content.monogram)
        assertEquals(BadgeKind.Live, content.badge?.kind)
        assertEquals(ScrubberDot.PulsingRed, content.progress.dot)
        assertNull(content.progress.liveEdgeFraction)
    }

    @Test
    fun `live channel falls back to ts when m3u8 is not allowed`() = runTest {
        val repo = fakeRepository(
            live = mapOf("7" to liveStream("7", name = "Northbay News")),
            allowedFormats = listOf("ts"),
        )
        val vm = viewModel(PlayerLaunch.live("7"), repo)

        assertTrue(vm.readyContent().url.endsWith("/live/u/p/7.ts"))
    }

    @Test
    fun `vod plays the movie route with the row's container extension`() = runTest {
        val repo = fakeRepository(
            vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "The Long Coast", containerExtension = "mkv")),
        )
        val vm = viewModel(PlayerLaunch.movie("9"), repo)

        val content = vm.readyContent()
        assertEquals(PlaybackMode.Movie, content.mode)
        assertEquals("http://panel.example:8080/movie/u/p/9.mkv", content.url)
        assertEquals("The Long Coast", content.title)
        assertEquals(ScrubberDot.Cream, content.progress.dot)
        // "Ends at …" needs the duration; it arrives with the first tick.
        assertNull(content.badge)
    }

    @Test
    fun `vod honors a non-blank direct_source over the built url`() = runTest {
        // F-51 / protocol §3.2: when the row carries direct_source, that URL
        // is the play URL — no /movie/ build at all.
        val repo = fakeRepository(
            vod = mapOf(
                "9" to VodStream(
                    id = "9",
                    categoryId = "1",
                    name = "The Long Coast",
                    containerExtension = "mkv",
                    directSource = "http://cdn.example/long-coast.mkv",
                ),
            ),
        )
        val vm = viewModel(PlayerLaunch.movie("9"), repo)

        assertEquals("http://cdn.example/long-coast.mkv", vm.readyContent().url)
    }

    @Test
    fun `vod ignores a blank direct_source and builds the movie url`() = runTest {
        val repo = fakeRepository(
            vod = mapOf(
                "9" to VodStream(
                    id = "9",
                    categoryId = "1",
                    name = "The Long Coast",
                    containerExtension = "mkv",
                    directSource = "  ",
                ),
            ),
        )
        val vm = viewModel(PlayerLaunch.movie("9"), repo)

        assertEquals("http://panel.example:8080/movie/u/p/9.mkv", vm.readyContent().url)
    }

    @Test
    fun `vod with a junk container extension still resolves an mp4 url`() = runTest {
        // The row's container_extension can be degenerate ("."); the load must
        // not crash — it falls back to mp4 (F-51 hardening).
        val repo = fakeRepository(
            vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "The Long Coast", containerExtension = ".")),
        )
        val vm = viewModel(PlayerLaunch.movie("9"), repo)

        assertEquals("http://panel.example:8080/movie/u/p/9.mp4", vm.readyContent().url)
    }

    @Test
    fun `series plays the first episode via the series route`() = runTest {
        val repo = fakeRepository(
            firstEpisode = PlayerEpisode("11", "mp4", "Pilot", "The Show"),
        )
        val vm = viewModel(PlayerLaunch.series("5"), repo)

        val content = vm.readyContent()
        assertEquals("http://panel.example:8080/series/u/p/11.mp4", content.url)
        assertEquals("Pilot", content.title)
        assertEquals("The Show", content.topLine)
    }

    @Test
    fun `missing stored login gates to NeedsAuth`() = runTest {
        val vm = viewModel(PlayerLaunch.live("7"), fakeRepository(connection = null))
        assertEquals(PlayerUiState.NeedsAuth, vm.state.value)
    }

    @Test
    fun `unknown stream id gates to StreamNotFound`() = runTest {
        val vm = viewModel(PlayerLaunch.live("404"), fakeRepository())
        assertEquals(PlayerUiState.StreamNotFound, vm.state.value)
    }

    // ---------------------------------------------------------------- replay

    @Test
    fun `replay on an archive channel builds the timeshift url and cream state`() = runTest {
        val repo = fakeRepository(
            live = mapOf("7" to liveStream("7", name = "Capital Desk", archive = true)),
        )
        val vm = viewModel(replayLaunch(), repo)

        val content = vm.readyContent()
        assertEquals(PlaybackMode.Replay, content.mode)
        assertTrue(
            "expected timeshift URL, got ${content.url}",
            content.url.startsWith("http://panel.example:8080/timeshift/u/p/30/2024-04-19:15-30/7"),
        )
        assertEquals(ScrubberDot.Cream, content.progress.dot)
        assertNotNull(content.progress.liveEdgeFraction)
        assertEquals(BadgeKind.Replay, content.badge?.kind)
        // Started 30 min ago → "Replay · 30 min behind" on launch.
        assertEquals("Replay · 30 min behind", content.badge?.text)
    }

    @Test
    fun `replay on a channel without tv_archive is rejected with the handoff copy`() = runTest {
        val repo = fakeRepository(
            live = mapOf("7" to liveStream("7", name = "Capital Desk", archive = false)),
        )
        val vm = viewModel(replayLaunch(), repo)

        val state = vm.state.value
        assertTrue(state is PlayerUiState.ReplayUnsupported)
        assertEquals(
            PlayerViewModel.REPLAY_UNSUPPORTED_MESSAGE,
            (state as PlayerUiState.ReplayUnsupported).message,
        )
    }

    @Test
    fun `go live swaps the timeshift url for the live one`() = runTest {
        val repo = fakeRepository(
            live = mapOf("7" to liveStream("7", name = "Capital Desk", archive = true)),
            allowedFormats = listOf("m3u8"),
        )
        val vm = viewModel(replayLaunch(), repo)
        assertTrue(vm.state.value is PlayerUiState.Ready)

        vm.goLive()

        val content = vm.readyContent()
        assertEquals(PlaybackMode.Live, content.mode)
        assertEquals("http://panel.example:8080/live/u/p/7.m3u8", content.url)
        assertEquals(ScrubberDot.PulsingRed, content.progress.dot)
        assertEquals(BadgeKind.Live, content.badge?.kind)
        assertNull(content.progress.liveEdgeFraction)
    }

    // ------------------------------------------------------------- start over

    private fun liveWithGuide(archive: Boolean) = fakeRepository(
        live = mapOf("7" to liveStream("7", name = "Capital Desk", archive = archive, epgChannelId = "ch7")),
        allowedFormats = listOf("m3u8"),
        nowNext = PlayerNowNext(
            now = PlayerProgram("Headline report", now - 12 * 60_000L, now + 48 * 60_000L, "Details."),
            next = PlayerProgram("Market wrap", now + 48 * 60_000L, now + 78 * 60_000L),
        ),
    )

    @Test
    fun `start over replays the current program through the timeshift route and go live returns`() = runTest {
        val vm = viewModel(PlayerLaunch.live("7"), liveWithGuide(archive = true))
        assertTrue(vm.readyContent().tvArchive)
        assertTrue(PlayerStartOver.isEnabled(vm.readyContent()))

        vm.startOver()

        val replay = vm.readyContent()
        assertEquals(PlaybackMode.Replay, replay.mode)
        assertTrue(
            "expected a 60 minute timeshift from the program start, got ${replay.url}",
            replay.url.startsWith("http://panel.example:8080/timeshift/u/p/60/"),
        )
        assertEquals(ScrubberDot.Cream, replay.progress.dot)
        assertEquals(BadgeKind.Replay, replay.badge?.kind)
        assertEquals("Replay · 12 min behind", replay.badge?.text)
        assertEquals(PlayerFormatting.clockLabel(now - 12 * 60_000L), replay.progress.startLabel)
        // The guide data stays for the info card.
        assertNotNull(replay.nowNext)

        vm.goLive()

        val live = vm.readyContent()
        assertEquals(PlaybackMode.Live, live.mode)
        assertEquals("http://panel.example:8080/live/u/p/7.m3u8", live.url)
        // The scrubber is the program's again, and Start over is available once more.
        assertEquals(PlayerFormatting.clockLabel(now - 12 * 60_000L), live.progress.startLabel)
        assertEquals(PlayerFormatting.clockLabel(now + 48 * 60_000L), live.progress.endLabel)
        assertTrue(PlayerStartOver.isEnabled(live))
    }

    @Test
    fun `start over does nothing on a channel without tv_archive`() = runTest {
        val vm = viewModel(PlayerLaunch.live("7"), liveWithGuide(archive = false))
        val before = vm.readyContent()

        vm.startOver()

        assertEquals(before, vm.readyContent())
    }

    @Test
    fun `start over does nothing without guide data or in replay`() = runTest {
        val noGuide = viewModel(
            PlayerLaunch.live("7"),
            fakeRepository(live = mapOf("7" to liveStream("7", name = "N", archive = true, epgChannelId = "ch7"))),
        )
        val before = noGuide.readyContent()
        noGuide.startOver()
        assertEquals(before, noGuide.readyContent())

        val replay = viewModel(replayLaunch(), fakeRepository(live = mapOf("7" to liveStream("7", name = "N", archive = true))))
        val replayBefore = replay.readyContent()
        replay.startOver()
        assertEquals(replayBefore, replay.readyContent())
    }

    @Test
    fun `the program description reaches the info card`() = runTest {
        val vm = viewModel(PlayerLaunch.live("7"), liveWithGuide(archive = false))
        vm.toggleProgramInfo()
        val content = vm.readyContent()
        assertTrue(content.showProgramInfo)
        assertEquals("Details.", PlayerInfoCard.from(content, now)!!.description)
    }

    // -------------------------------------------------------------- playback

    @Test
    fun `movie progress reports runtime labels and the ends-at badge`() = runTest {
        val repo = fakeRepository(
            vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mp4")),
        )
        val vm = viewModel(PlayerLaunch.movie("9"), repo)

        vm.onPosition(
            positionMillis = 3_734_000L, // 1:02:14
            durationMillis = 7_440_000L, // 2:04:00
            bufferedMillis = 3_800_000L,
        )

        val content = vm.readyContent()
        assertEquals("1:02:14", content.progress.startLabel)
        assertEquals("2:04:00", content.progress.endLabel)
        assertEquals(3_734_000L, content.progress.positionMillis)
        assertEquals(7_440_000L, content.progress.durationMillis)
        assertTrue(content.progress.fillFraction in 0.49f..0.51f)
        // Buffered reaches at least the 6% lookahead past the playhead.
        assertTrue(content.progress.bufferedFraction >= content.progress.fillFraction)
        assertEquals(BadgeKind.MovieEnds, content.badge?.kind)
        assertTrue(content.badge!!.text.startsWith("Ends at "))
    }

    @Test
    fun `live progress fills by wall clock through the epg program`() = runTest {
        val programStart = now - 30 * 60_000L
        val repo = fakeRepository(
            live = mapOf("7" to liveStream("7", name = "Northbay News", archive = false, epgChannelId = "ch7")),
            nowNext = PlayerNowNext(
                now = PlayerProgram("Headline report", programStart, programStart + 60 * 60_000L),
                next = PlayerProgram("Market wrap", programStart + 60 * 60_000L, programStart + 90 * 60_000L),
            ),
        )
        val vm = viewModel(PlayerLaunch.live("7"), repo)

        val content = vm.readyContent()
        assertEquals("Headline report", content.title)
        assertTrue("fill=${content.progress.fillFraction}", content.progress.fillFraction in 0.49f..0.51f)
        assertEquals(PlayerFormatting.clockLabel(programStart), content.progress.startLabel)
        assertNotNull(content.nowNext)
        assertTrue(content.nowNext!!.next!!.title == "Market wrap")
    }

    @Test
    fun `stream errors retry twice then go offline`() = runTest {
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val handle = FakePlayerHandle()
        val vm = viewModel(PlayerLaunch.live("7"), repo, handle)
        val effects = recordEffects(vm)

        vm.onStreamError()
        assertEquals(PlayerViewModel.RETRYING_MESSAGE, vm.message.value)
        assertEquals(listOf<PlayerEffect>(PlayerEffect.RetryPrepare), effects.toList())

        vm.onStreamError()
        assertTrue(vm.state.value is PlayerUiState.Ready)

        vm.onStreamError() // third inside 30 s
        assertTrue(vm.state.value is PlayerUiState.Offline)
        assertEquals("N", (vm.state.value as PlayerUiState.Offline).content.channelName)
    }

    @Test
    fun `an error outside the 30 s window does not accumulate`() = runTest {
        var clockNow = now
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val vm = PlayerViewModel(
            launch = PlayerLaunch.live("7"),
            repository = repo,
            handle = FakePlayerHandle(),
            clock = { clockNow },
        )

        vm.onStreamError()
        vm.onStreamError()
        clockNow += 31_000L // window slides
        vm.onStreamError()

        assertTrue("third error outside window must not go offline", vm.state.value is PlayerUiState.Ready)
    }

    @Test
    fun `retry clears the error budget and re-prepares`() = runTest {
        val handle = FakePlayerHandle()
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val vm = viewModel(PlayerLaunch.live("7"), repo, handle)

        vm.onStreamError()
        vm.onStreamError()
        vm.onStreamError()
        assertTrue(vm.state.value is PlayerUiState.Offline)

        vm.retry()

        assertTrue(vm.state.value is PlayerUiState.Ready)
        assertEquals(1, handle.prepareCalls)
    }

    @Test
    fun `genuine network errors with real codes keep the retry then offline behaviour`() = runTest {
        // F-68 regression guard: passing the Media3 code must not change what
        // a real outage does. Two retries ("Retrying…" + re-prepare), then
        // the offline card on the third inside 30 s.
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val vm = viewModel(PlayerLaunch.live("7"), repo)
        val effects = recordEffects(vm)
        val networkFailure = java.io.IOException("connection reset")

        vm.onStreamError(networkFailure, 2001) // IO_NETWORK_CONNECTION_FAILED
        assertEquals(PlayerViewModel.RETRYING_MESSAGE, vm.message.value)
        assertEquals(listOf<PlayerEffect>(PlayerEffect.RetryPrepare), effects.toList())
        vm.onStreamError(networkFailure, 2001)
        assertTrue(vm.state.value is PlayerUiState.Ready)
        vm.onStreamError(networkFailure, 2001)

        assertTrue(vm.state.value is PlayerUiState.Offline)
    }

    // ------------------------------------------------------ #21 redirect guard

    private fun refusedRedirect(): Throwable =
        java.io.IOException("Unable to connect", InsecureRedirectException())

    @Test
    fun `a refused redirect goes to the insecure redirect card with no retry`() = runTest {
        val repo = fakeRepository(
            vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mkv")),
        )
        val handle = FakePlayerHandle()
        val vm = viewModel(PlayerLaunch.movie("9"), repo, handle)
        val effects = recordEffects(vm)

        vm.onStreamError(refusedRedirect(), 2001)

        val state = vm.state.value
        assertTrue("was ${state::class.simpleName}", state is PlayerUiState.InsecureRedirect)
        assertFalse((state as PlayerUiState.InsecureRedirect).content.isBuffering)
        assertNull(vm.message.value)
        assertTrue("no re-prepare", effects.isEmpty())
        assertEquals(0, handle.prepareCalls)
        assertEquals(
            "This movie would send your sign-in details without encryption, so HappyTV stopped it.",
            PlayerViewModel.insecureRedirectHeading(state.content),
        )
    }

    @Test
    fun `the refusal copy names a channel or an episode`() = runTest {
        val live = viewModel(PlayerLaunch.live("7"), fakeRepository(live = mapOf("7" to liveStream("7", name = "N"))))
        assertEquals(
            "This channel would send your sign-in details without encryption, so HappyTV stopped it.",
            PlayerViewModel.insecureRedirectHeading(live.readyContent()),
        )
        val episode = live.readyContent().copy(isEpisode = true)
        assertEquals(
            "This episode would send your sign-in details without encryption, so HappyTV stopped it.",
            PlayerViewModel.insecureRedirectHeading(episode),
        )
    }

    @Test
    fun `a refused redirect never reaches the offline card however many arrive`() = runTest {
        val vm = viewModel(PlayerLaunch.live("7"), fakeRepository(live = mapOf("7" to liveStream("7", name = "N"))))

        repeat(5) { vm.onStreamError(refusedRedirect(), 2001) }

        assertTrue(vm.state.value is PlayerUiState.InsecureRedirect)
    }

    // ------------------------------------------------------------ F-68 codec

    @Test
    fun `a decoder failure goes to unsupported format on the first error`() = runTest {
        // F-68: three identical 4001s used to burn the retry budget and then
        // print "This channel is offline" on a channel the panel was serving.
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val handle = FakePlayerHandle()
        val vm = viewModel(PlayerLaunch.live("7"), repo, handle)
        val effects = recordEffects(vm)

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)

        val state = vm.state.value
        assertTrue(
            "a decoder failure must not reach the offline card, was ${state::class.simpleName}",
            state is PlayerUiState.UnsupportedFormat,
        )
        assertEquals("N", (state as PlayerUiState.UnsupportedFormat).content.channelName)
        assertFalse(
            "the codec card must not spin the buffering spinner",
            state.content.isBuffering,
        )
        // No "Retrying…", no re-prepare: retrying a decoder wall cannot work,
        // and the snackbar is the "we are still trying" promise.
        assertNull(vm.message.value)
        assertTrue(
            "a decoder failure must not ask the player to re-prepare",
            effects.isEmpty(),
        )
        assertEquals("the player must not be touched at all", 0, handle.prepareCalls)
    }

    @Test
    fun `a decoder failure never reaches the offline card however many arrive`() = runTest {
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val vm = viewModel(PlayerLaunch.live("7"), repo)

        repeat(5) { vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED) }

        assertTrue(vm.state.value is PlayerUiState.UnsupportedFormat)
    }

    @Test
    fun `retry from the codec card returns to ready so a later HD channel is reachable`() = runTest {
        // The escape hatch the card does NOT advertise: a provider-side swap
        // or a reboot can change the outcome, and "Try again" must not be a
        // one-way door.
        val handle = FakePlayerHandle()
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val vm = viewModel(PlayerLaunch.live("7"), repo, handle)

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)
        vm.retry()

        assertTrue(vm.state.value is PlayerUiState.Ready)
        assertEquals(1, handle.prepareCalls)
    }

    @Test
    fun `a codec failure does not consume the budget for the next stream error`() = runTest {
        // The budget is per playback session; a decoder wall that cleared
        // itself must not leave the next two real errors one short of the
        // offline card.
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val vm = viewModel(PlayerLaunch.live("7"), repo)

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)
        vm.retry()
        vm.onStreamError(null, ERROR_IO_BAD_HTTP_STATUS)
        vm.onStreamError(null, ERROR_IO_BAD_HTTP_STATUS)

        assertTrue(
            "two genuine stream errors after a codec wall must still be retrying",
            vm.state.value is PlayerUiState.Ready,
        )
        vm.onStreamError(null, ERROR_IO_BAD_HTTP_STATUS)
        assertTrue(vm.state.value is PlayerUiState.Offline)
    }

    @Test
    fun `a codec failure while still loading lands on the card once content resolves`() = runTest {
        // F-68's real path: the first prepare fails at decoder init. Hold the
        // launch in Loading (connection lookup suspended), deliver 4001, then
        // let it resolve. The first thing published must be the card.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = fakeRepository(
            live = mapOf("7" to liveStream("7", name = "N")),
            gate = gate,
        )
        val handle = FakePlayerHandle()
        val vm = viewModel(PlayerLaunch.live("7"), repo, handle)
        val effects = recordEffects(vm)
        assertEquals(PlayerUiState.Loading, vm.state.value)

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)
        assertEquals(PlayerUiState.Loading, vm.state.value)
        gate.complete(Unit)

        val state = vm.state.value
        assertTrue(
            "expected the unsupported-format card, was ${state::class.simpleName}",
            state is PlayerUiState.UnsupportedFormat,
        )
        assertEquals("N", (state as PlayerUiState.UnsupportedFormat).content.channelName)
        assertFalse(state.content.isBuffering)
        assertNull(vm.message.value)
        assertTrue("no retry effect", effects.isEmpty())
        assertEquals(0, handle.prepareCalls)
    }

    @Test
    fun `a codec failure after a final non-content state leaves that state alone`() = runTest {
        val repo = fakeRepository(live = emptyMap())
        val vm = viewModel(PlayerLaunch.live("missing"), repo)
        assertEquals(PlayerUiState.StreamNotFound, vm.state.value)

        vm.onStreamError(decoderInitFailure(), ERROR_DECODER_INIT_FAILED)

        assertEquals(PlayerUiState.StreamNotFound, vm.state.value)
    }

    @Test
    fun `a mid-stream decoding failure takes the normal retry then offline path`() = runTest {
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val handle = FakePlayerHandle()
        val vm = viewModel(PlayerLaunch.live("7"), repo, handle)
        val effects = recordEffects(vm)

        vm.onStreamError(null, 4003) // ERROR_CODE_DECODING_FAILED
        assertEquals(PlayerViewModel.RETRYING_MESSAGE, vm.message.value)
        assertEquals(listOf<PlayerEffect>(PlayerEffect.RetryPrepare), effects.toList())
        vm.onStreamError(null, 4003)
        assertTrue(vm.state.value is PlayerUiState.Ready)
        vm.onStreamError(null, 4003)

        assertTrue(vm.state.value is PlayerUiState.Offline)
    }

    @Test
    fun `the codec card copy names the cause and the fix and never says offline`() = runTest {
        // design spec error rule: one red line with the fix beside it. These two
        // assertions are the copy contract — "offline" or "try another" in
        // either line re-opens F-68, and "Try again" is a button the card
        // deliberately does not draw.
        val heading = PlayerViewModel.UNSUPPORTED_FORMAT_HEADING
        val body = PlayerViewModel.UNSUPPORTED_FORMAT_BODY
        listOf(heading, body).forEach { line ->
            assertFalse("copy must not blame the channel: \"$line\"", line.contains("offline", true))
            assertFalse("copy must not send the user hunting: \"$line\"", line.contains("try again", true))
            assertFalse("design spec forbids exclamation points: \"$line\"", line.contains('!'))
        }
        assertEquals("This TV can't play this channel's video.", heading)
        assertEquals(
            "The channel uses a video format this TV doesn't support. Try another channel.",
            body,
        )
        assertFalse("copy stays format-generic: \"$body\"", body.contains("4K", ignoreCase = true))
    }

    // --------------------------------------------------------------- controls

    @Test
    fun `program info toggles on channels and is a no-op on movies`() = runTest {
        val repo = fakeRepository(
            live = mapOf("7" to liveStream("7", name = "N", epgChannelId = "ch")),
            nowNext = PlayerNowNext(PlayerProgram("Now", now, now + 1000), null),
        )
        val vm = viewModel(PlayerLaunch.live("7"), repo)
        assertFalse(vm.readyContent().showProgramInfo)

        vm.toggleProgramInfo()
        assertTrue(vm.readyContent().showProgramInfo)
        vm.toggleProgramInfo()
        assertFalse(vm.readyContent().showProgramInfo)

        val movieVm = viewModel(
            PlayerLaunch.movie("9"),
            fakeRepository(vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mp4"))),
        )
        movieVm.toggleProgramInfo()
        assertFalse(movieVm.readyContent().showProgramInfo)
    }

    @Test
    fun `favorite toggles through the repository`() = runTest {
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "N")))
        val vm = viewModel(PlayerLaunch.live("7"), repo)
        assertFalse(vm.readyContent().isFavorite)

        vm.toggleFavorite()
        assertTrue(vm.readyContent().isFavorite)
        assertTrue(repo.isFavorite("7", StreamType.LIVE))

        vm.toggleFavorite()
        assertFalse(vm.readyContent().isFavorite)
    }

    @Test
    fun `skip while hidden seeks only on movies`() = runTest {
        val movieHandle = FakePlayerHandle()
        val movieVm = viewModel(
            PlayerLaunch.movie("9"),
            fakeRepository(vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mp4"))),
            movieHandle,
        )
        movieVm.skipWhileHidden(10_000L)
        movieVm.skipWhileHidden(-10_000L)
        assertEquals(listOf(10_000L, -10_000L), movieHandle.seeks)

        val liveHandle = FakePlayerHandle()
        val liveVm = viewModel(PlayerLaunch.live("7"), fakeRepository(live = mapOf("7" to liveStream("7", name = "N"))), liveHandle)
        liveVm.skipWhileHidden(10_000L)
        assertTrue(liveHandle.seeks.isEmpty())
    }

    @Test
    fun `play and pause go through the player handle`() = runTest {
        val handle = FakePlayerHandle()
        val vm = viewModel(PlayerLaunch.live("7"), fakeRepository(live = mapOf("7" to liveStream("7", name = "N"))), handle)

        vm.togglePlayPause()
        assertEquals(1, handle.toggles)
    }

    // ----------------------------------------------------------------- tracks

    @Test
    fun `no track information hides the tracks button entirely`() = runTest {
        val repo = fakeRepository(vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mp4")))
        val vm = viewModel(PlayerLaunch.movie("9"), repo)

        vm.onTracks(null)
        assertNull(vm.readyContent().tracks)

        vm.onTracks(
            PlayerTracks(
                audio = listOf(PlayerTrackOption("English", 0, 0)),
                subtitles = listOf(PlayerTrackOption("Off", 1, null), PlayerTrackOption("English", 1, 0)),
            ),
        )
        assertNotNull(vm.readyContent().tracks)
        assertTrue(vm.readyContent().tracks!!.hasChoices)
    }

    @Test
    fun `selecting a track updates state and emits the selection effect`() = runTest {
        val repo = fakeRepository(vod = mapOf("9" to VodStream(id = "9", categoryId = "1", name = "M", containerExtension = "mp4")))
        val vm = viewModel(PlayerLaunch.movie("9"), repo)
        val effects = recordEffects(vm)
        vm.onTracks(
            PlayerTracks(
                audio = listOf(PlayerTrackOption("English", 0, 0), PlayerTrackOption("Spanish", 0, 1)),
                subtitles = listOf(PlayerTrackOption("Off", 1, null)),
            ),
        )

        vm.selectAudioTrack(1)

        assertEquals(1, vm.readyContent().tracks!!.selectedAudio)
        assertTrue(effects.contains(PlayerEffect.SelectAudioTrack(0, 1)))
    }

    // --------------------------------------------------------------- plumbing

    /**
     * The 3840×2160 HEVC decoder failure from QA's F-68 repro, built with
     * Media3's own exception type so the test exercises the same cause chain
     * the device produces (`onPlayerError` → `DecoderInitializationException`).
     */
    private fun decoderInitFailure(): Throwable =
        MediaCodecRenderer.DecoderInitializationException(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(3840)
                .setHeight(2160)
                .build(),
            IllegalStateException("Decoder init failed: c2.amlogic.hevc.decoder"),
            /* secureDecoderRequired = */ false,
            /* errorCode = */ ERROR_DECODER_INIT_FAILED,
        )

    private fun replayLaunch() = PlayerLaunch.replay(
        streamId = "7",
        startUtcMillis = now - 30 * 60_000L,
        durationMinutes = 30,
    )

    private fun viewModel(
        launch: PlayerLaunch?,
        repo: PlayerRepository,
        handle: PlayerHandle = FakePlayerHandle(),
    ) = PlayerViewModel(launch, repo, handle, clock = { now })

    private fun kotlinx.coroutines.test.TestScope.recordEffects(vm: PlayerViewModel): List<PlayerEffect> {
        val collected = mutableListOf<PlayerEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.effects.toList(collected)
        }
        return collected
    }

    // ------------------------------------------------------------------ zap

    @Test
    fun `start re-points the same view model at another channel`() = runTest {
        val repo = fakeRepository(
            live = mapOf(
                "7" to liveStream("7", num = 101, name = "Northbay News"),
                "8" to liveStream("8", num = 102, name = "Harbor Sports"),
            ),
        )
        val vm = viewModel(PlayerLaunch.live("7"), repo)
        assertEquals("Northbay News", vm.readyContent().title)

        vm.start(PlayerLaunch.live("8"))

        val content = vm.readyContent()
        assertEquals("Harbor Sports", content.title)
        assertTrue(content.url.endsWith("/live/u/p/8.m3u8") || content.url.endsWith("/live/u/p/8.ts"))
    }

    @Test
    fun `a view model without a launch stays loading until started`() = runTest {
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "Northbay News")))
        val vm = viewModel(null, repo)
        assertEquals(PlayerUiState.Loading, vm.state.value)

        vm.start(PlayerLaunch.live("7"))

        assertEquals("Northbay News", vm.readyContent().title)
    }

    @Test
    fun `stop drops the stream and returns to loading`() = runTest {
        val repo = fakeRepository(live = mapOf("7" to liveStream("7", name = "Northbay News")))
        val vm = viewModel(PlayerLaunch.live("7"), repo)
        assertTrue(vm.state.value is PlayerUiState.Ready)

        vm.stop()

        assertEquals(PlayerUiState.Loading, vm.state.value)
    }

    @Test
    fun `start resets the stream error budget of the previous channel`() = runTest {
        val repo = fakeRepository(
            live = mapOf(
                "7" to liveStream("7", name = "Northbay News"),
                "8" to liveStream("8", name = "Harbor Sports"),
            ),
        )
        val vm = viewModel(PlayerLaunch.live("7"), repo)
        // Two I/O errors on the first channel: one more would be "offline".
        repeat(2) { vm.onStreamError(null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) }

        vm.start(PlayerLaunch.live("8"))
        repeat(2) { vm.onStreamError(null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) }

        assertTrue("the new channel starts with a fresh budget", vm.state.value is PlayerUiState.Ready)
    }

    @Test
    fun `start supersedes a load still in flight`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = fakeRepository(
            live = mapOf(
                "7" to liveStream("7", name = "Northbay News"),
                "8" to liveStream("8", name = "Harbor Sports"),
            ),
            gate = gate,
        )
        val vm = viewModel(PlayerLaunch.live("7"), repo)
        assertTrue(vm.state.value is PlayerUiState.Loading)

        vm.start(PlayerLaunch.live("8"))
        gate.complete(Unit)

        assertEquals("Harbor Sports", vm.readyContent().title)
    }

    private fun PlayerViewModel.readyContent(): PlayerContent =
        (state.value as PlayerUiState.Ready).content

    private fun liveStream(
        id: String,
        num: Int? = null,
        name: String,
        archive: Boolean = false,
        epgChannelId: String? = null,
    ) = LiveStream(
        id = id,
        categoryId = "1",
        num = num,
        name = name,
        tvArchive = archive,
        epgChannelId = epgChannelId,
    )

    private fun fakeRepository(
        connection: PlayerConnection? = PlayerConnection("panel.example", 8080, false, "u", "p"),
        live: Map<String, LiveStream> = emptyMap(),
        vod: Map<String, VodStream> = emptyMap(),
        firstEpisode: PlayerEpisode? = null,
        allowedFormats: List<String>? = null,
        nowNext: PlayerNowNext? = null,
        gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) = object : PlayerRepository {
        val favorites = mutableSetOf<String>()

        override suspend fun connection(): PlayerConnection? {
            gate?.await()
            return connection
        }

        override suspend fun liveStream(id: String): LiveStream? = live[id]

        override suspend fun vodStream(id: String): VodStream? = vod[id]

        override suspend fun firstEpisode(seriesId: String): PlayerEpisode? = firstEpisode

        override suspend fun allowedOutputFormats(): List<String>? = allowedFormats

        override suspend fun nowNext(epgChannelId: String, nowUtcMillis: Long): PlayerNowNext? = nowNext

        override suspend fun isFavorite(streamId: String, streamType: StreamType): Boolean =
            streamId in favorites

        override suspend fun toggleFavorite(streamId: String, streamType: StreamType) {
            if (!favorites.remove(streamId)) favorites.add(streamId)
        }
    }
}

/**
 * The Media3 error codes F-68's tests speak in. Spelled out rather than
 * referenced from `PlaybackException` so the tests keep reading like the
 * logcat line QA filed them against.
 */
private const val ERROR_DECODER_INIT_FAILED = 4001
private const val ERROR_IO_BAD_HTTP_STATUS = 2004

/** Records the D-pad-driven commands the ViewModel sends to the player. */
private class FakePlayerHandle : PlayerHandle {
    var toggles = 0
    var prepareCalls = 0
    val seeks = mutableListOf<Long>()

    override fun togglePlayPause() {
        toggles++
    }

    override fun seekBy(deltaMillis: Long) {
        seeks += deltaMillis
    }

    override fun prepare() {
        prepareCalls++
    }
}
