package com.rysal.happytv.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rysal.happytv.BuildConfig
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.entity.WatchProgress
import com.rysal.happytv.data.entity.WatchProgressPolicy
import com.rysal.happytv.perf.HtvPerf
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

/**
 * Player state machine — design spec §"Screens: player" (per-mode table, other
 * states, D-pad sticky note) with every branch unit-testable behind
 * [PlayerRepository] + [PlayerHandle] fakes.
 *
 * One-shot flow per launch: [load] resolves the stream through the repository
 * seam (catalog row, stored login, cached `user_info`, EPG now/next), builds
 * the playback URL with [PlayerUrlBuilder] and publishes [PlayerUiState.Ready]
 * carrying everything the UI draws. Replay launches are gated on
 * `LiveStream.tvArchive` (protocol §4.1) — a channel without an archive gets
 * [PlayerUiState.ReplayUnsupported] and the activity returns the user to the
 * channel list.
 *
 * After Ready, the activity reports player facts back in
 * ([onIsPlayingChanged], [onBuffering], [onPosition], [onTracks],
 * [onStreamError]) and the ViewModel republishes the whole content snapshot;
 * UI code only ever reads [state].
 */
class PlayerViewModel(
    launch: PlayerLaunch?,
    private val repository: PlayerRepository,
    internal val handle: PlayerHandle,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** What is being played; replaced by [start] when the host zaps. */
    private var current: PlayerLaunch = launch ?: IDLE_LAUNCH

    private var loadJob: Job? = null

    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Transient snackbar line ("Retrying…"); the UI auto-dismisses it. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _effects = Channel<PlayerEffect>(Channel.BUFFERED)
    /** One-off commands toward the activity (retry, track selection). */
    val effects: Flow<PlayerEffect> = _effects.receiveAsFlow()

    private var connection: PlayerConnection? = null

    /**
     * Whose watch progress the current stream is (#78): a movie, or one episode of a show.
     * Null for live and replay, which never save progress. Position fields are filled per save.
     */
    private var progressTarget: WatchProgress? = null

    /** The show's next episode as a position-0 row, saved when this episode finishes ("Up next"). */
    private var upNextTarget: WatchProgress? = null

    /** The episode after the one playing (#84): what the end-of-episode card offers. Null on the last one. */
    private var nextTarget: NextEpisode? = null

    private val _endOfPlayback = MutableStateFlow<EndOfPlayback>(EndOfPlayback.None)
    /** The end-of-episode card (#84): countdown, stopped, or "return to details". */
    val endOfPlayback: StateFlow<EndOfPlayback> = _endOfPlayback.asStateFlow()

    private var countdownJob: Job? = null

    /**
     * True from [start] until the new item has rendered or reached READY (#84). Until then the
     * engine can still report the previous item's end position and STATE_ENDED, so saves and
     * the end signal are ignored: they would mark the new episode watched.
     */
    private var awaitingNewMedia = false

    /** The first frame (or READY) of the item [start] asked for: saves and the end signal count again. */
    fun onMediaRendered() {
        awaitingNewMedia = false
    }

    /**
     * What this session has actually done (#104). A save needs proof of a real viewing: the
     * stream has played, and either [WatchProgressPolicy.MIN_SAVE_MILLIS] of wall-clock time was
     * watched or the viewer committed a seek. Without it the engine's position is only the launch's
     * resume point (or 0), and writing it back would bump a row's timestamps for a glance.
     */
    private var watchedMillis = 0L
    private var playingSinceMillis: Long? = null
    private var seekedThisSession = false

    private fun resetSessionEvidence() {
        watchedMillis = 0L
        playingSinceMillis = null
        seekedThisSession = false
    }

    private fun watchedSoFar(): Long = watchedMillis + (playingSinceMillis?.let { (clock() - it).coerceAtLeast(0L) } ?: 0L)

    private fun sessionHasPlayed(): Boolean = seekedThisSession || watchedSoFar() >= WatchProgressPolicy.MIN_SAVE_MILLIS

    /** Saves go one at a time, in the order they were asked for. */
    private val progressLock = Mutex()

    /** Live/replay progress bounds — the airing program, or a rolling window. */
    private var windowStartUtcMillis: Long = 0L
    private var windowEndUtcMillis: Long = 0L

    /** Timestamps of stream errors; three inside 30 s = "Stream offline". */
    private val streamErrorTimes = mutableListOf<Long>()

    /**
     * F-68: a decoder error that arrived while the launch was still resolving
     * (state [PlayerUiState.Loading], no content to hang a card on yet).
     * [publishLoaded] honours it so the first content published is the
     * unsupported-format card, not a Ready that would re-prepare and fail again.
     */
    private var unsupportedFormatPending = false

    /** #21: same idea as [unsupportedFormatPending] for a refused redirect. */
    private var insecureRedirectPending = false

    init {
        // A null launch is the Live TV host starting Idle (#16): nothing plays
        // until the host calls [start].
        if (launch != null) load()
    }

    // ------------------------------------------------------------------ load

    /**
     * Re-points this ViewModel at a new launch (channel zapping): resets the
     * per-stream error bookkeeping and progress window, cancels any load still
     * in flight, and resolves the new launch. The constructor launch stays the
     * first one, so activities that start with one launch are unchanged.
     */
    fun start(newLaunch: PlayerLaunch) {
        current = newLaunch
        progressTarget = null
        upNextTarget = null
        resetEndOfPlayback()
        awaitingNewMedia = true
        resetSessionEvidence()
        streamErrorTimes.clear()
        windowStartUtcMillis = 0L
        windowEndUtcMillis = 0L
        load()
    }

    /**
     * Back to Idle (#16): cancels any load in flight and drops the current
     * stream, so nothing is Ready and a host that stops its engine cannot be
     * rebuilt from a stale target.
     */
    fun stop() {
        loadJob?.cancel()
        loadJob = null
        current = IDLE_LAUNCH
        progressTarget = null
        upNextTarget = null
        resetEndOfPlayback()
        awaitingNewMedia = false
        resetSessionEvidence()
        streamErrorTimes.clear()
        windowStartUtcMillis = 0L
        windowEndUtcMillis = 0L
        unsupportedFormatPending = false
        insecureRedirectPending = false
        _message.value = null
        HtvPerf.cancel(HtvPerf.PLAYER_NEXT_EPISODE)
        _state.value = PlayerUiState.Loading
    }

    /** The activity is finishing (Back, finish) with a next-episode load possibly in flight: drop its timer (#99). */
    public override fun onCleared() {
        HtvPerf.cancel(HtvPerf.PLAYER_NEXT_EPISODE)
        super.onCleared()
    }

    private fun resetEndOfPlayback() {
        countdownJob?.cancel()
        countdownJob = null
        nextTarget = null
        _endOfPlayback.value = EndOfPlayback.None
    }

    // ------------------------------------------------------ end of episode (#84)

    /**
     * The player reached the end of the stream. For an episode: the episode is finished (saved
     * through the same path as any finish, which also queues the next one as "Up next"), then a
     * 10 s countdown to the next episode starts, or on the last episode the screen returns to
     * details. Movies, live and replay do nothing here.
     */
    fun onPlaybackEnded(durationMillis: Long) {
        if (awaitingNewMedia) return
        if (_endOfPlayback.value != EndOfPlayback.None) return
        if (progressTarget == null || currentContent()?.isEpisode != true) return
        persistProgress(durationMillis, durationMillis, reachedEnd = true)
        val next = nextTarget
        if (next == null) {
            _endOfPlayback.value = EndOfPlayback.ReturnToDetails
            return
        }
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (seconds in NEXT_EPISODE_COUNTDOWN_SECONDS downTo 1) {
                _endOfPlayback.value = EndOfPlayback.Countdown(next.label, seconds)
                delay(1_000L)
            }
            playNextEpisode()
        }
    }

    /** Back or Cancel during the countdown: stay on the ended frame, offer Play / Back to details. */
    fun cancelCountdown() {
        val next = nextTarget ?: return
        if (_endOfPlayback.value !is EndOfPlayback.Countdown) return
        countdownJob?.cancel()
        countdownJob = null
        _endOfPlayback.value = EndOfPlayback.Stopped(next.label)
    }

    /** The Next episode button, or the countdown reaching zero. Resumes the episode's saved position, else 0. */
    fun playNextEpisode() {
        val next = nextTarget ?: return
        if (_endOfPlayback.value == EndOfPlayback.None || _endOfPlayback.value == EndOfPlayback.ReturnToDetails) return
        val launch = current
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            HtvPerf.start(HtvPerf.PLAYER_NEXT_EPISODE)
            val saved = runCatching { repository.progress(StreamType.SERIES, next.id) }.getOrNull()
            val resumeAt = saved?.takeIf { it.finishedAt == null }?.positionMillis ?: 0L
            start(
                launch.copy(
                    streamType = StreamType.SERIES,
                    episodeId = next.id,
                    startPositionMillis = resumeAt,
                    startOver = false,
                ),
            )
        }
    }

    /**
     * Start over deletes the title's saved row (finished mark included). A plain Play on a finished
     * title leaves the row alone (#99): it is the "watched" marker, so a short look keeps it, and
     * the first save past the minute replaces it with an unfinished row.
     */
    private suspend fun clearRowForFreshStart(type: StreamType, id: String) {
        if (current.startOver) runCatching { repository.clearProgress(type, id) }
    }

    /** Resolve the launch and publish [PlayerUiState.Ready] (or a failure state). */
    fun load() {
        Log.i(TAG, "load() called — streamType=${current.streamType} streamId=${current.streamId} " +
            "isReplay=${current.isReplay} hasExplicitConnection=${current.hasExplicitConnection}")
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = PlayerUiState.Loading
            _message.value = null
            unsupportedFormatPending = false
            val conn = resolveConnection() ?: run {
                Log.w(TAG, "resolveConnection() returned null → NeedsAuth")
                _state.value = PlayerUiState.NeedsAuth
                return@launch
            }
            Log.i(TAG, "resolveConnection() → host=${conn.host} port=${conn.port} useTls=${conn.useTls}")
            connection = conn
            when (current.streamType) {
                StreamType.LIVE -> loadLive(conn)
                StreamType.VOD -> loadVod(conn)
                StreamType.SERIES -> loadSeries(conn)
            }
        }
    }

    private suspend fun resolveConnection(): PlayerConnection? {
        if (current.hasExplicitConnection) {
            return PlayerConnection(
                host = current.host!!,
                port = current.port!!,
                useTls = current.useTls!!,
                username = current.username!!,
                password = current.password!!,
            )
        }
        return repository.connection()
    }

    private suspend fun loadLive(conn: PlayerConnection) {
        Log.i(TAG, "loadLive: looking up stream id=${current.streamId}")
        val stream = repository.liveStream(current.streamId) ?: run {
            Log.w(TAG, "loadLive: liveStream(${current.streamId}) returned null → StreamNotFound")
            _state.value = PlayerUiState.StreamNotFound
            return
        }
        Log.i(TAG, "loadLive: found stream id=${stream.id} name=${stream.name} num=${stream.num} " +
            "tvArchive=${stream.tvArchive} epgChannelId=${stream.epgChannelId}")
        val now = clock()
        val nowNext = stream.epgChannelId?.let {
            Log.i(TAG, "loadLive: querying nowNext for epgChannelId=$it")
            repository.nowNext(it, now)
        }
        Log.i(TAG, "loadLive: nowNext=$nowNext")

        val wantsReplay = current.isReplay && current.startUtcMillis > 0L && current.durationMinutes > 0
        if (wantsReplay && !stream.tvArchive) {
            // design spec §"Player" §6: channels without tv_archive can't replay.
            Log.w(TAG, "loadLive: wantsReplay but tvArchive=false → ReplayUnsupported")
            _state.value = PlayerUiState.ReplayUnsupported(REPLAY_UNSUPPORTED_MESSAGE)
            return
        }

        val url: String
        val mode: PlaybackMode
        if (wantsReplay) {
            url = PlayerUrlBuilder.replay(
                origin = conn.origin,
                username = conn.username,
                password = conn.password,
                streamId = stream.id,
                startUtcMillis = current.startUtcMillis,
                durationMinutes = current.durationMinutes,
            )
            mode = PlaybackMode.Replay
            // Replay bounds come from the program the user asked for.
            windowStartUtcMillis = current.startUtcMillis
            windowEndUtcMillis = current.startUtcMillis + current.durationMinutes * 60_000L
        } else {
            val ext = current.extension ?: PlayerUrlBuilder.liveExtension(
                repository.allowedOutputFormats(),
            )
            Log.i(TAG, "loadLive: extension=$ext (from launch=${current.extension}, " +
                "allowedOutputFormats=${repository.allowedOutputFormats()})")
            url = PlayerUrlBuilder.live(
                origin = conn.origin,
                username = conn.username,
                password = conn.password,
                streamId = stream.id,
                extension = ext,
            )
            mode = PlaybackMode.Live
            // Without guide data, the "program" is a rolling window around now.
            windowStartUtcMillis = nowNext?.now?.startUtcMillis ?: (now - ROLLING_WINDOW_HALF)
            windowEndUtcMillis = nowNext?.now?.endUtcMillis ?: (now + ROLLING_WINDOW_HALF)
        }
        Log.i(TAG, "loadLive: built URL = ${PlayerUrlBuilder.redactCredentials(url)}  mode=$mode")

        publishLoaded(
            PlayerContent(
                url = url,
                mode = mode,
                title = nowNext?.now?.title ?: stream.name,
                topLine = PlayerFormatting.channelLine(stream.num, stream.name),
                channelName = stream.name,
                monogram = PlayerMonograms.initials(stream.name),
                monogramPalette = PlayerMonograms.paletteFor(stream.name),
                logoUrl = stream.streamIcon,
                nowNext = nowNext,
                tvArchive = stream.tvArchive,
                isFavorite = repository.isFavorite(stream.id, StreamType.LIVE),
                progress = initialProgress(mode, now),
                badge = initialBadge(mode, now),
                isBuffering = true,
            ),
        )
    }

    private suspend fun loadVod(conn: PlayerConnection) {
        val vod = repository.vodStream(current.streamId) ?: run {
            Log.w(TAG, "loadVod: vodStream(${current.streamId}) returned null → StreamNotFound")
            logPlayUrl("vod row ${current.streamId} missing from catalog", null)
            _state.value = PlayerUiState.StreamNotFound
            return
        }
        // Protocol §3.2: a non-blank `direct_source` IS the play URL — build
        // the standard `/movie/{user}/{pass}/{id}.{ext}` only for rows without
        // one (F-51: the app ignored this override entirely).
        val direct = vod.directSource?.takeIf { it.isNotBlank() }
        val extension = PlayerUrlBuilder.movieExtension(vod.containerExtension, current.extension)
        val url = direct ?: PlayerUrlBuilder.movie(
            origin = conn.origin,
            username = conn.username,
            password = conn.password,
            streamId = vod.id,
            containerExtension = extension,
        )
        logPlayUrl(
            if (direct != null) "direct_source" else "built /movie/.$extension",
            url,
        )
        Log.i(TAG, "loadVod: built URL = ${PlayerUrlBuilder.redactCredentials(url)}")
        val now = clock()
        progressTarget = WatchProgress(
            streamType = StreamType.VOD, streamId = vod.id, positionMillis = 0L, durationMillis = 0L, updatedUtcMillis = 0L,
        )
        clearRowForFreshStart(StreamType.VOD, vod.id)
        publishLoaded(
            PlayerContent(
                url = url,
                startPositionMillis = current.startPositionMillis,
                mode = PlaybackMode.Movie,
                title = vod.name,
                topLine = PlayerFormatting.movieLine(current.year, current.mpaa),
                channelName = null,
                monogram = null,
                monogramPalette = null,
                nowNext = null,
                isFavorite = repository.isFavorite(vod.id, StreamType.VOD),
                progress = PlayerProgress(
                    startLabel = PlayerFormatting.runtimeLabel(0L),
                    endLabel = null,
                    fillFraction = 0f,
                    bufferedFraction = 0f,
                    liveEdgeFraction = null,
                    dot = ScrubberDot.Cream,
                ),
                // "Ends at …" needs the duration; it appears on the first tick.
                badge = null,
                isBuffering = true,
            ),
        )
    }

    private suspend fun loadSeries(conn: PlayerConnection) {
        val episode = repository.episode(current.streamId, current.episodeId) ?: run {
            Log.w(TAG, "loadSeries: episode(${current.streamId}) returned null → StreamNotFound")
            _state.value = PlayerUiState.StreamNotFound
            return
        }
        Log.i(TAG, "loadSeries: found episode id=${episode.id} containerExtension=${episode.containerExtension}")
        val url = PlayerUrlBuilder.episode(
            origin = conn.origin,
            username = conn.username,
            password = conn.password,
            episodeId = episode.id,
            containerExtension = episode.containerExtension ?: current.extension ?: "mp4",
        )
        Log.i(TAG, "loadSeries: built URL = ${PlayerUrlBuilder.redactCredentials(url)}")
        val now = clock()
        progressTarget = WatchProgress(
            streamType = StreamType.SERIES,
            streamId = episode.id,
            seriesId = current.streamId,
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            positionMillis = 0L,
            durationMillis = 0L,
            updatedUtcMillis = 0L,
        )
        upNextTarget = episode.nextId?.let { nextId ->
            WatchProgress(
                streamType = StreamType.SERIES,
                streamId = nextId,
                seriesId = current.streamId,
                seasonNumber = episode.nextSeasonNumber,
                episodeNumber = episode.nextEpisodeNumber,
                positionMillis = 0L,
                durationMillis = 0L,
                updatedUtcMillis = 0L,
            )
        }
        nextTarget = episode.nextId?.let { nextId ->
            NextEpisode(nextId, nextEpisodeLabel(episode.nextSeasonNumber, episode.nextEpisodeNumber, episode.nextTitle))
        }
        clearRowForFreshStart(StreamType.SERIES, episode.id)
        // Resume only lands on the episode that was in progress: a different one starts at 0.
        val startAt = if (current.episodeId == null || current.episodeId == episode.id) current.startPositionMillis else 0L
        publishLoaded(
            PlayerContent(
                url = url,
                startPositionMillis = startAt,
                mode = PlaybackMode.Movie,
                isEpisode = true,
                title = episode.title ?: episode.seriesName.orEmpty(),
                topLine = episode.seriesName?.takeIf { episode.title != null },
                channelName = null,
                monogram = null,
                monogramPalette = null,
                nowNext = null,
                isFavorite = repository.isFavorite(current.streamId, StreamType.SERIES),
                progress = PlayerProgress(
                    startLabel = PlayerFormatting.runtimeLabel(0L),
                    endLabel = null,
                    fillFraction = 0f,
                    bufferedFraction = 0f,
                    liveEdgeFraction = null,
                    dot = ScrubberDot.Cream,
                ),
                badge = null,
                isBuffering = true,
            ),
        )
    }

    // ------------------------------------------------------- playback facts

    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && !awaitingNewMedia) {
            if (playingSinceMillis == null) playingSinceMillis = clock()
        } else {
            playingSinceMillis?.let { watchedMillis += (clock() - it).coerceAtLeast(0L) }
            playingSinceMillis = null
        }
        updateContent { it.copy(isPlaying = isPlaying) }
    }

    fun onBuffering(isBuffering: Boolean) = updateContent { it.copy(isBuffering = isBuffering) }

    /** The activity's 1 s tick while playing; recomputes the mode's progress. */
    fun onPosition(positionMillis: Long, durationMillis: Long, bufferedMillis: Long) {
        val now = clock()
        updateContent { content ->
            content.copy(
                progress = progressFor(content.mode, positionMillis, durationMillis, bufferedMillis, now),
                badge = badgeFor(content.mode, positionMillis, durationMillis, now),
            )
        }
    }

    /**
     * Saves how far into a movie or episode playback is (#78). The host calls it on pause, on
     * stop / Back, every [WatchProgressPolicy.SAVE_INTERVAL_MILLIS] while playing, and in
     * `onStop`. Under a minute in saves nothing; with five minutes or less left, or past 95%,
     * the title is finished: its row keeps the position and gets `finishedAt`, and a show's next
     * episode is queued as "Up next". Live and replay never save.
     *
     * The write outlives this ViewModel (a Back press clears it right after calling), hence
     * [NonCancellable].
     */
    fun persistProgress(positionMillis: Long, durationMillis: Long, reachedEnd: Boolean = false) {
        if (awaitingNewMedia) return
        val target = progressTarget ?: return
        if (currentContent()?.mode != PlaybackMode.Movie) return
        // #104: a session that has not really played (or been sought) writes nothing.
        if (!reachedEnd && !sessionHasPlayed()) return
        val duration = durationMillis.coerceAtLeast(0L)
        val decision = WatchProgressPolicy.decide(positionMillis, duration)
        if (decision == WatchProgressPolicy.Decision.IGNORE) return
        val now = clock()
        viewModelScope.launch(NonCancellable) {
            progressLock.withLock {
                runCatching {
                    if (decision == WatchProgressPolicy.Decision.FINISHED) {
                        // Finished: keep the row with its position and mark it, so it drops out of
                        // Continue watching and Resume but still reads as watched.
                        repository.saveProgress(
                            target.copy(
                                positionMillis = positionMillis, durationMillis = duration,
                                updatedUtcMillis = now, finishedAt = now,
                            ),
                        )
                        upNextTarget?.let { repository.saveUpNext(it.copy(updatedUtcMillis = now)) }
                    } else {
                        repository.saveProgress(
                            target.copy(positionMillis = positionMillis, durationMillis = duration, updatedUtcMillis = now),
                        )
                    }
                }.onFailure { Log.w(TAG, "persistProgress failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** Track info arrived from `Player.Listener.onTracksChanged`; null hides the button. */
    fun onTracks(tracks: PlayerTracks?) = updateContent { it.copy(tracks = tracks) }

    /**
     * `Player.Listener.onPlayerError` — snackbar "Retrying…" and a re-prepare,
     * unless it is the third failure inside 30 s, which goes [PlayerUiState.Offline]
     * (design spec task §9; no auto-failover to backup streams).
     *
     * **F-68:** a decoder/codec failure is not a stream failure. Retrying it
     * three times produces the same `DecoderInitializationException` every
     * time and then lies to the user — "This channel is offline" on a channel
     * the panel is serving perfectly, because this TV cannot decode its
     * format. Codec errors therefore short-circuit straight to
     * [PlayerUiState.UnsupportedFormat] on the *first* error, spend no retry
     * budget, and are never described as offline. See [PlayerErrorClassifier]
     * for what counts and — as importantly — what does not.
     */
    fun onStreamError(error: Throwable? = null, errorCode: Int? = null) {
        val now = clock()
        Log.w(TAG, "onStreamError: error=$error errorCode=$errorCode " +
            "errors=${streamErrorTimes.size}")
        val failure = PlayerErrorClassifier.classify(errorCode, error)
        if (failure == PlayerErrorClassifier.PlayerFailure.InsecureRedirect) {
            Log.w(TAG, "onStreamError: insecure redirect refused; going to InsecureRedirect without retrying")
            showInsecureRedirect()
            return
        }
        if (failure == PlayerErrorClassifier.PlayerFailure.UnsupportedFormat) {
            Log.w(TAG, "onStreamError: decoder/codec failure — the device cannot play this " +
                "format; going to UnsupportedFormat without burning the retry budget")
            showUnsupportedFormat()
            return
        }
        streamErrorTimes.removeAll { it < now - ERROR_WINDOW_MILLIS }
        streamErrorTimes += now
        if (streamErrorTimes.size >= MAX_ERRORS_IN_WINDOW) {
            updateContent { it.copy(isBuffering = false) }
            val ready = _state.value
            if (ready is PlayerUiState.Ready) _state.value = PlayerUiState.Offline(ready.content)
            return
        }
        _message.value = RETRYING_MESSAGE
        viewModelScope.launch { _effects.send(PlayerEffect.RetryPrepare) }
    }

    /**
     * F-68 — the codec-capability state. Copy is never "offline" and the
     * buttons never offer "Try again": a decoder that refused the format
     * once will refuse it again, so the card offers only Back to channels.
     * Retry still works from here (a reboot or a provider-side swap can
     * change the outcome), so the budget is cleared on the way out.
     */
    private fun showUnsupportedFormat() {
        streamErrorTimes.clear()
        _message.value = null
        val content = currentContentOrNull()
        if (content != null) {
            _state.value = PlayerUiState.UnsupportedFormat(content.copy(isBuffering = false))
            return
        }
        // No content yet. Only Loading can still become a card; NeedsAuth,
        // StreamNotFound and ReplayUnsupported are final and must not be
        // overwritten by a stray decoder error.
        if (_state.value == PlayerUiState.Loading) unsupportedFormatPending = true
    }

    /**
     * #21 - the redirect guard refused playback. Like F-68's card there is no
     * retry (it would be refused again); the card offers Open settings and Back.
     */
    private fun showInsecureRedirect() {
        streamErrorTimes.clear()
        _message.value = null
        val content = currentContentOrNull()
        if (content != null) {
            _state.value = PlayerUiState.InsecureRedirect(content.copy(isBuffering = false))
            return
        }
        if (_state.value == PlayerUiState.Loading) insecureRedirectPending = true
    }

    /**
     * Publishes freshly resolved content: [PlayerUiState.Ready], unless a
     * decoder error already arrived while we were loading (F-68), in which
     * case the unsupported-format card goes up directly.
     */
    private fun publishLoaded(content: PlayerContent) {
        if (insecureRedirectPending) {
            insecureRedirectPending = false
            _state.value = PlayerUiState.InsecureRedirect(content.copy(isBuffering = false))
        } else if (unsupportedFormatPending) {
            unsupportedFormatPending = false
            _state.value = PlayerUiState.UnsupportedFormat(content.copy(isBuffering = false))
        } else {
            _state.value = PlayerUiState.Ready(content)
        }
    }

    // ---------------------------------------------------------------- intents

    /** OK — pause or play (any OK keypress; the controls don't have to be up). */
    fun togglePlayPause() = handle.togglePlayPause()

    /** Play key (#136): resumes, never pauses. */
    fun play() = handle.setPlayWhenReady(true)

    /** Pause key (#136): pauses, never resumes. */
    fun pause() = handle.setPlayWhenReady(false)

    /** Left/Right while the controls are hidden — ±10 s on movies only. */
    fun skipWhileHidden(deltaMillis: Long) {
        if (currentContent()?.mode == PlaybackMode.Movie) {
            if (!awaitingNewMedia) seekedThisSession = true
            handle.seekBy(deltaMillis)
        }
    }

    /** Scrubber seek (#102): one coalesced jump to [positionMillis], movies and episodes only. */
    fun seekToPosition(positionMillis: Long) {
        if (currentContent()?.mode == PlaybackMode.Movie) {
            if (!awaitingNewMedia) seekedThisSession = true
            handle.seekTo(positionMillis)
        }
    }

    /** Up/Down and the info button — toggle Now/Next without pausing playback. */
    fun toggleProgramInfo() {
        updateContent { content ->
            if (content.nowNext == null) content else content.copy(showProgramInfo = !content.showProgramInfo)
        }
    }

    fun toggleFavorite() {
        val content = currentContent() ?: return
        val type = when (current.streamType) {
            StreamType.LIVE -> StreamType.LIVE
            StreamType.VOD -> StreamType.VOD
            StreamType.SERIES -> StreamType.SERIES
        }
        viewModelScope.launch {
            repository.toggleFavorite(current.streamId, type)
            val favorite = repository.isFavorite(current.streamId, type)
            updateContent { it.copy(isFavorite = favorite) }
        }
    }

    /** Replay's **Go live** — swap the timeshift URL for the live one. */
    fun goLive() {
        val conn = connection ?: return
        val content = currentContent() ?: return
        if (content.mode != PlaybackMode.Replay) return
        val url = PlayerUrlBuilder.live(
            origin = conn.origin,
            username = conn.username,
            password = conn.password,
            streamId = current.streamId,
            extension = current.extension ?: PlayerUrlBuilder.liveExtension(null),
        )
        val now = clock()
        // Back to the program the guide shows now (the scrubber it had before Start over), or the
        // rolling window when the guide has none.
        windowStartUtcMillis = content.nowNext?.now?.startUtcMillis ?: (now - ROLLING_WINDOW_HALF)
        windowEndUtcMillis = content.nowNext?.now?.endUtcMillis ?: (now + ROLLING_WINDOW_HALF)
        _state.value = PlayerUiState.Ready(
            content.copy(
                url = url,
                mode = PlaybackMode.Live,
                progress = initialProgress(PlaybackMode.Live, now),
                badge = initialBadge(PlaybackMode.Live, now),
            ),
        )
    }

    /**
     * Live's **Start over** (#131): replay the current program from its start through the same
     * timeshift route the guide's "Watch from the start" uses. Go live returns to the live URL.
     * A no-op unless [PlayerStartOver.program] says the channel and guide allow it.
     */
    fun startOver() {
        val conn = connection ?: return
        val content = currentContent() ?: return
        val program = PlayerStartOver.program(content) ?: return
        val url = PlayerUrlBuilder.replay(
            origin = conn.origin,
            username = conn.username,
            password = conn.password,
            streamId = current.streamId,
            startUtcMillis = program.startUtcMillis,
            durationMinutes = PlayerStartOver.durationMinutes(program),
        )
        val now = clock()
        windowStartUtcMillis = program.startUtcMillis
        windowEndUtcMillis = program.endUtcMillis
        _state.value = PlayerUiState.Ready(
            content.copy(
                url = url,
                mode = PlaybackMode.Replay,
                showProgramInfo = false,
                progress = initialProgress(PlaybackMode.Replay, now),
                badge = initialBadge(PlaybackMode.Replay, now),
            ),
        )
    }

    /** Audio/subtitles panel selection — updates state and tells the activity. */
    fun selectAudioTrack(index: Int) {
        val tracks = currentContent()?.tracks ?: return
        val option = tracks.audio.getOrNull(index) ?: return
        val trackIndex = option.trackIndex ?: return
        updateContent { it.copy(tracks = tracks.copy(selectedAudio = index)) }
        viewModelScope.launch {
            _effects.send(PlayerEffect.SelectAudioTrack(option.groupIndex, trackIndex))
        }
    }

    fun selectSubtitleTrack(index: Int) {
        val tracks = currentContent()?.tracks ?: return
        val option = tracks.subtitles.getOrNull(index) ?: return
        updateContent { it.copy(tracks = tracks.copy(selectedSubtitle = index)) }
        viewModelScope.launch {
            _effects.send(PlayerEffect.SelectSubtitleTrack(option.groupIndex, option.trackIndex))
        }
    }

    /** Offline card's **Try again** — fresh error budget, re-prepare. */
    fun retry() {
        streamErrorTimes.clear()
        _message.value = null
        _state.value = currentContentOrNull()?.let { PlayerUiState.Ready(it.copy(isBuffering = true)) }
            ?: PlayerUiState.Loading
        handle.prepare()
    }

    fun clearMessage() {
        _message.value = null
    }

    // -------------------------------------------------------------- internals

    /**
     * The VOD resolution trail (F-51's "log it"): which URL was chosen and why,
     * credentials redacted (netsec F-10), debug builds only (F-09). Pairs with
     * [PlayerNetworkLog], which proves the request actually left the device.
     */
    private fun logPlayUrl(source: String, url: String?) {
        if (!BuildConfig.DEBUG) return
        val safe = url?.let { PlayerUrlBuilder.redactCredentials(it) } ?: "(none)"
        Log.i(TAG, "vod resolve: $source → $safe")
    }

    private fun currentContent(): PlayerContent? = (_state.value as? PlayerUiState.Ready)?.content

    private fun currentContentOrNull(): PlayerContent? =
        (_state.value as? PlayerUiState.Ready)?.content
            ?: (_state.value as? PlayerUiState.Offline)?.content
            ?: (_state.value as? PlayerUiState.UnsupportedFormat)?.content
            ?: (_state.value as? PlayerUiState.InsecureRedirect)?.content

    private fun updateContent(transform: (PlayerContent) -> PlayerContent) {
        val ready = _state.value as? PlayerUiState.Ready ?: return
        _state.value = PlayerUiState.Ready(transform(ready.content))
    }

    private fun initialProgress(mode: PlaybackMode, now: Long): PlayerProgress = when (mode) {
        PlaybackMode.Live -> PlayerProgress(
            startLabel = PlayerFormatting.clockLabel(windowStartUtcMillis),
            endLabel = PlayerFormatting.clockLabel(windowEndUtcMillis),
            fillFraction = liveFraction(now),
            bufferedFraction = liveFraction(now),
            liveEdgeFraction = null,
            dot = ScrubberDot.PulsingRed,
        )
        PlaybackMode.Replay -> PlayerProgress(
            startLabel = PlayerFormatting.clockLabel(windowStartUtcMillis),
            endLabel = PlayerFormatting.clockLabel(windowEndUtcMillis),
            fillFraction = 0f,
            bufferedFraction = 0f,
            // On launch the user is at the program's start; the live edge is "now".
            liveEdgeFraction = ((now - windowStartUtcMillis).toFloat() /
                (maxOf(windowEndUtcMillis, now) - windowStartUtcMillis).coerceAtLeast(1L)),
            dot = ScrubberDot.Cream,
        )
        PlaybackMode.Movie -> PlayerProgress(
            startLabel = PlayerFormatting.runtimeLabel(0L),
            endLabel = null,
            fillFraction = 0f,
            bufferedFraction = 0f,
            liveEdgeFraction = null,
            dot = ScrubberDot.Cream,
        )
    }

    private fun initialBadge(mode: PlaybackMode, now: Long): PlayerBadge? = when (mode) {
        PlaybackMode.Live -> PlayerBadge(BadgeKind.Live, LIVE_BADGE_TEXT)
        PlaybackMode.Replay -> PlayerBadge(
            BadgeKind.Replay,
            PlayerFormatting.replayBadgeLabel(windowStartUtcMillis, now),
        )
        PlaybackMode.Movie -> null
    }

    private fun progressFor(
        mode: PlaybackMode,
        positionMillis: Long,
        durationMillis: Long,
        bufferedMillis: Long,
        now: Long,
    ): PlayerProgress = when (mode) {
        PlaybackMode.Live -> PlayerProgress(
            startLabel = PlayerFormatting.clockLabel(windowStartUtcMillis),
            endLabel = PlayerFormatting.clockLabel(windowEndUtcMillis),
            fillFraction = liveFraction(now),
            bufferedFraction = liveFraction(now),
            liveEdgeFraction = null,
            dot = ScrubberDot.PulsingRed,
        )
        PlaybackMode.Movie -> {
            val total = durationMillis.coerceAtLeast(0L)
            val position = positionMillis.coerceIn(0L, total.coerceAtLeast(positionMillis))
            PlayerProgress(
                startLabel = PlayerFormatting.runtimeLabel(position),
                endLabel = total.takeIf { it > 0L }?.let { PlayerFormatting.runtimeLabel(it) },
                fillFraction = fraction(position, total),
                // design spec: the buffered part runs ~6% ahead of the playhead.
                bufferedFraction = (fraction(bufferedMillis, total))
                    .coerceAtLeast(fraction(position, total) + BUFFERED_LOOKAHEAD),
                liveEdgeFraction = null,
                dot = ScrubberDot.Cream,
                positionMillis = position,
                durationMillis = total,
            )
        }
        PlaybackMode.Replay -> {
            val startAbs = current.startUtcMillis
            val spanEnd = maxOf(windowEndUtcMillis, now)
            val span = (spanEnd - startAbs).coerceAtLeast(1L)
            val positionAbs = startAbs + positionMillis.coerceAtLeast(0L)
            val bufferedAbs = startAbs + bufferedMillis.coerceAtLeast(0L)
            PlayerProgress(
                startLabel = PlayerFormatting.clockLabel(windowStartUtcMillis),
                endLabel = PlayerFormatting.clockLabel(windowEndUtcMillis),
                fillFraction = fraction((positionAbs - startAbs).coerceAtLeast(0L), span),
                bufferedFraction = fraction((bufferedAbs - startAbs).coerceAtLeast(0L), span)
                    .coerceAtLeast(fraction((positionAbs - startAbs).coerceAtLeast(0L), span)),
                liveEdgeFraction = fraction((now - startAbs).coerceAtLeast(0L), span),
                dot = ScrubberDot.Cream,
            )
        }
    }

    private fun badgeFor(
        mode: PlaybackMode,
        positionMillis: Long,
        durationMillis: Long,
        now: Long,
    ): PlayerBadge? = when (mode) {
        PlaybackMode.Live -> PlayerBadge(BadgeKind.Live, LIVE_BADGE_TEXT)
        PlaybackMode.Replay -> PlayerBadge(
            BadgeKind.Replay,
            PlayerFormatting.replayBadgeLabel(windowStartUtcMillis, now),
        )
        PlaybackMode.Movie -> durationMillis.takeIf { it > 0L }?.let { total ->
            PlayerBadge(BadgeKind.MovieEnds, PlayerFormatting.endsAtLabel(now + (total - positionMillis)))
        }
    }

    private fun liveFraction(now: Long): Float {
        val span = (windowEndUtcMillis - windowStartUtcMillis).coerceAtLeast(1L)
        return ((now - windowStartUtcMillis).toFloat() / span).coerceIn(0f, 1f)
    }

    private fun fraction(part: Long, total: Long): Float =
        if (total <= 0L) 0f else (part.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    /** Production factory — [Graph] wiring, exactly like the other screens. */
    class Factory(
        private val launch: PlayerLaunch?,
        private val handle: PlayerHandle,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PlayerViewModel::class.java) {
                "Factory only knows PlayerViewModel; got $modelClass"
            }
            return PlayerViewModel(
                launch = launch,
                repository = Graph.playerRepository(),
                handle = handle,
            ) as T
        }
    }

    companion object {
        private const val TAG = "PlayerF48"

        /** Placeholder for "no launch yet" so [current] stays non-null. */
        private val IDLE_LAUNCH = PlayerLaunch(streamType = StreamType.LIVE, streamId = "")

        const val REPLAY_UNSUPPORTED_MESSAGE = "Replay isn't supported for this channel."
        const val RETRYING_MESSAGE = "Retrying…"
        const val LIVE_BADGE_TEXT = "Live"

        /**
         * F-68 copy for a decoder-capability wall.
         *
         * design spec §"Rules to keep": "one red line with an icon, at the bottom of
         * the card or control that failed, **with the fix beside it**". The old
         * offline line ("This channel is offline. Try another.") broke that rule
         * twice over on a 4K channel: it named the wrong cause. These two lines
         * name the real cause (a video format this TV can't decode) in terms
         * that hold for any format (4K/HEVC, AV1, ...), and point at the one
         * thing the user can do: pick another channel.
         *
         * Vocabulary per design spec: sentence case, active voice, no exclamation
         * points, no tutorial copy. "This TV" rather than "this device" — it is
         * what the user is looking at.
         */
        const val UNSUPPORTED_FORMAT_HEADING = "This TV can't play this channel's video."
        /** #21: "movie", "episode" or "channel" for the refused-redirect card. */
        fun insecureRedirectHeading(content: PlayerContent): String {
            val noun = when {
                content.isEpisode -> "episode"
                content.mode == PlaybackMode.Movie -> "movie"
                else -> "channel"
            }
            return "This $noun would send your sign-in details without encryption, so HappyTV stopped it."
        }

        const val UNSUPPORTED_FORMAT_BODY =
            "The channel uses a video format this TV doesn't support. Try another channel."

        /** Rolling program window when the EPG has nothing for this channel. */
        const val ROLLING_WINDOW_HALF = 15L * 60L * 1000L

        const val MAX_ERRORS_IN_WINDOW = 3
        const val ERROR_WINDOW_MILLIS = 30_000L

        /** Movie buffered part: 6% of the runtime ahead of the playhead. */
        const val BUFFERED_LOOKAHEAD = 0.06f
    }
}

// ------------------------------------------------------------------- UI model

/** The whole screen as one immutable snapshot; UI code only reads it. */
sealed interface PlayerUiState {
    /** Resolving the stream (catalog row, login, URL). */
    data object Loading : PlayerUiState

    /** No stored credentials — the activity redirects to sign-in. */
    data object NeedsAuth : PlayerUiState

    /** The id isn't in the current catalog (refresh is overdue). */
    data object StreamNotFound : PlayerUiState

    /** Replay on a channel with `tv_archive == 0`; activity returns to the list. */
    data class ReplayUnsupported(val message: String) : PlayerUiState

    /** Playing (or buffering/erroring) — carries everything the UI draws. */
    data class Ready(val content: PlayerContent) : PlayerUiState

    /** Three stream errors inside 30 s — the error card replaces playback. */
    data class Offline(val content: PlayerContent) : PlayerUiState

    /**
     * F-68 — the stream is fine, **this device** cannot decode its video
     * format (a 4K/HEVC feed on an HD dongle).
     *
     * A distinct state, not a flag on [Offline], because the two demand
     * different copy and different buttons: a decoder that refused the format
     * will refuse it again, so this card offers "Back to channels" alone and
     * never says "Try again" or "offline".
     */
    data class UnsupportedFormat(val content: PlayerContent) : PlayerUiState

    /**
     * #21 - the stream's host redirected from HTTPS to plain HTTP with the
     * sign-in details in the target, and the redirect guard refused. Actions:
     * Open settings (the override lives there) and Back. No retry.
     */
    data class InsecureRedirect(val content: PlayerContent) : PlayerUiState
}

enum class PlaybackMode { Live, Movie, Replay }

/** One `Ready`/`Offline` snapshot — every mode-specific field in one place. */
data class PlayerContent(
    /** Playback URL; changes only on **Go live** (replay → live). */
    val url: String,
    val mode: PlaybackMode,
    /** Program title (live/replay), movie name or episode title. */
    val title: String,
    /** "101 · Northbay News" or "2023 · PG-13"; null hides the line. */
    val topLine: String?,
    /** Channel name for the offline card's top block; null for VOD. */
    val channelName: String?,
    /** Monogram initials — channels only; null hides the monogram. */
    val monogram: String?,
    val monogramPalette: MonogramPalette?,
    /** EPG now/next; null for VOD/series (and channels without guide data). */
    val nowNext: PlayerNowNext?,
    val showProgramInfo: Boolean = false,
    /** The channel keeps catch-up (`tv_archive`): Start over can replay the current program (#131). */
    val tvArchive: Boolean = false,
    val isFavorite: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    /** Audio/subtitle options; null = no track information, button hidden. */
    val tracks: PlayerTracks? = null,
    val progress: PlayerProgress,
    val badge: PlayerBadge?,
    /** A series episode (plays as [PlaybackMode.Movie]); only changes copy. */
    val isEpisode: Boolean = false,
    /** Where playback starts (Resume, #78); 0 = the beginning. */
    val startPositionMillis: Long = 0L,
    /** The channel's raw `stream_icon`; ChannelLogoUrl vets it before any request (#139). */
    val logoUrl: String? = null,
)

/** Scrubber snapshot — per-mode labels, fill, buffered, live edge and dot. */
data class PlayerProgress(
    /** Program start (live/replay) or elapsed runtime (movie). */
    val startLabel: String?,
    /** Program end or total runtime; null until the movie's duration is known. */
    val endLabel: String?,
    val fillFraction: Float,
    /** Where the buffered part reaches; always >= [fillFraction]. */
    val bufferedFraction: Float,
    /** Replay's 3 × 28 px cream live-edge marker; null outside replay. */
    val liveEdgeFraction: Float?,
    val dot: ScrubberDot,
    /** Movie runtime facts (used by the leave-confirmation); 0 elsewhere. */
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
)

enum class ScrubberDot { PulsingRed, Cream }

/** The right-hand badge next to the scrubber. */
data class PlayerBadge(val kind: BadgeKind, val text: String)

enum class BadgeKind { Live, MovieEnds, Replay }

/**
 * Track choices for the audio-and-subtitles panel. An empty [tracks] pair
 * means the player reported no alternate audio at all — the button is hidden
 * entirely (design spec §"Player" other states), not disabled.
 */
data class PlayerTracks(
    val audio: List<PlayerTrackOption>,
    /** Always includes the "Off" entry at index 0. */
    val subtitles: List<PlayerTrackOption>,
    val selectedAudio: Int = 0,
    val selectedSubtitle: Int = 0,
) {
    val hasChoices: Boolean get() = audio.isNotEmpty() || subtitles.size > 1
}

/**
 * One selectable option. [trackIndex] is the index inside its
 * `Tracks.Group`; null (subtitles only) means "Off" — clear the override.
 */
data class PlayerTrackOption(
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int?,
)

/** One-off commands from the ViewModel to the activity's player. */
sealed interface PlayerEffect {
    /** Re-prepare after a stream error. */
    data object RetryPrepare : PlayerEffect

    data class SelectAudioTrack(val groupIndex: Int, val trackIndex: Int) : PlayerEffect

    data class SelectSubtitleTrack(val groupIndex: Int, val trackIndex: Int?) : PlayerEffect
}
