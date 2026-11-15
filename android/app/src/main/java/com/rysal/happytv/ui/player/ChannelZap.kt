package com.rysal.happytv.ui.player

import com.rysal.happytv.data.dao.FavoriteDao
import com.rysal.happytv.data.dao.HiddenChannelDao
import com.rysal.happytv.data.dao.LiveStreamDao
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.perf.HtvPerf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// #224: channel up/down in full-screen live TV.

/** A held key steps at most this often (about five channels a second). */
internal const val ZAP_STEP_INTERVAL_MILLIS = 200L

/** The stream starts once no step has come for this long: on release, or between taps. */
internal const val ZAP_SETTLE_MILLIS = 250L

/** The channel panel stays up this long after the last step, like the controls. */
internal const val ZAP_INFO_HIDE_MILLIS = CONTROLS_HIDE_MILLIS

/**
 * Which list channel up/down walks: the one the viewer started from (#224). Live TV sets it when a
 * channel is played from its lists, and it is kept through zaps and recent-row picks.
 */
sealed interface ChannelListSource {
    /** The Favorites list, in the viewer's own order. */
    data object Favorites : ChannelListSource

    /** All channels, by number. */
    data object AllChannels : ChannelListSource

    /** One provider category, by number. */
    data class Category(val categoryId: String) : ChannelListSource

    /** No list to follow (Search, Recently watched, nothing picked): the channel's own provider category. */
    data object OwnCategory : ChannelListSource
}

/** Where channel up/down goes from a channel: its neighbour in the list, hidden channels skipped, wrapping at the ends. */
class ChannelNeighbours(
    private val liveStreamDao: LiveStreamDao,
    private val favoriteDao: FavoriteDao,
    private val hiddenChannelDao: HiddenChannelDao,
) {
    /** The channel after ([direction] > 0, down) or before (up) [fromStreamId] in [source]; null when there is none to go to. */
    suspend fun neighbour(source: ChannelListSource, fromStreamId: String, direction: Int): LiveStream? {
        val startedAt = System.nanoTime()
        val from = liveStreamDao.getById(fromStreamId) ?: return null
        val down = direction > 0
        val found = when (source) {
            ChannelListSource.Favorites -> inFavorites(from, down)
            ChannelListSource.AllChannels -> inScope(null, from, down)
            is ChannelListSource.Category -> inScope(source.categoryId, from, down)
            ChannelListSource.OwnCategory -> inScope(from.categoryId, from, down)
        }
        HtvPerf.log(HtvPerf.PLAYER_CHANNEL_STEP, (System.nanoTime() - startedAt) / 1_000_000L, "list=${source::class.simpleName}")
        return found?.takeIf { it.id != from.id }
    }

    private suspend fun inScope(categoryId: String?, from: LiveStream, down: Boolean): LiveStream? {
        val num = from.num ?: Int.MAX_VALUE
        return if (down) {
            liveStreamDao.channelAfter(categoryId, num, from.name) ?: liveStreamDao.firstChannel(categoryId)
        } else {
            liveStreamDao.channelBefore(categoryId, num, from.name) ?: liveStreamDao.lastChannel(categoryId)
        }
    }

    private suspend fun inFavorites(from: LiveStream, down: Boolean): LiveStream? {
        val ids = favoriteDao.getOrdered(StreamType.LIVE).map { it.streamId }
        // Playing something that is not a favorite (unstarred meanwhile): its own category instead.
        if (from.id !in ids) return inScope(from.categoryId, from, down)
        val hidden = hiddenChannelDao.observeIds(StreamType.LIVE).first().toHashSet()
        return walkList(ids, from.id, if (down) 1 else -1) { id ->
            if (id in hidden) null else liveStreamDao.getById(id)
        }
    }
}

/**
 * The first usable entry after ([direction] 1) or before (-1) [current] in [ids], wrapping at the
 * ends; [resolve] returns null for an entry to skip (hidden, or no longer in the lineup). Null when
 * [current] is not in the list or nothing else is usable.
 */
internal suspend fun <T> walkList(ids: List<String>, current: String, direction: Int, resolve: suspend (String) -> T?): T? {
    val at = ids.indexOf(current)
    if (at < 0) return null
    for (i in 1 until ids.size) {
        val id = ids[Math.floorMod(at + direction * i, ids.size)]
        if (id == current) continue
        resolve(id)?.let { return it }
    }
    return null
}

/** A channel to step to, with the program on now when the guide has one. */
data class ZapTarget(val streamId: String, val number: Int?, val name: String, val nowNext: PlayerNowNext?)

/** What the zapper needs from the host: where the viewer is, what is next, and how to start a stream. */
interface ChannelStepper {
    val playingStreamId: String?

    suspend fun neighbour(fromStreamId: String, direction: Int): ZapTarget?

    fun tune(streamId: String)
}

/** The Live TV window's stepper: neighbours from Room, the program on now from the guide, tunes through the host. */
class LiveChannelStepper(
    private val neighbours: ChannelNeighbours,
    private val nowNext: suspend (epgChannelId: String, nowUtcMillis: Long) -> PlayerNowNext?,
    private val playing: () -> String?,
    private val list: () -> ChannelListSource,
    private val onTune: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChannelStepper {
    override val playingStreamId: String? get() = playing()

    override suspend fun neighbour(fromStreamId: String, direction: Int): ZapTarget? {
        val stream = neighbours.neighbour(list(), fromStreamId, direction) ?: return null
        val nowNext = stream.epgChannelId?.let { nowNext(it, clock()) }
        return ZapTarget(stream.id, stream.num, stream.name, nowNext)
    }

    override fun tune(streamId: String) = onTune(streamId)
}

/** The panel shown while stepping: the Now / Next card for the channel the next tune will open. */
data class ZapPreview(val streamId: String, val card: PlayerInfoCard)

/**
 * Turns channel up/down key presses into tunes (#224). A tap (repeatCount 0) tunes at once. Capped repeats of a
 * held key move a target one channel along the list and update [preview]; the stream starts once, on key-up or
 * [settleMillis] after the last step, for the channel the target ended on. A held key therefore never opens a stream
 * for the channels it passes. Stepping back to the channel already playing tunes nothing.
 */
class ChannelZapper(
    private val scope: CoroutineScope,
    private val stepper: ChannelStepper,
    private val clock: () -> Long,
    /** Runs just before the tune (the player keeps its controls closed for the new channel). */
    private val beforeTune: () -> Unit = {},
    private val stepIntervalMillis: Long = ZAP_STEP_INTERVAL_MILLIS,
    private val settleMillis: Long = ZAP_SETTLE_MILLIS,
) {
    private val _preview = MutableStateFlow<ZapPreview?>(null)

    /** Number, name and current program of the channel being stepped to; null when idle. */
    val preview: StateFlow<ZapPreview?> = _preview.asStateFlow()

    private val lock = Mutex()
    private var targetId: String? = null
    private var lastStepAt: Long? = null
    private var settleJob: Job? = null

    /** True while a step panel is showing, so the host keeps routing keys here through the load. */
    val isActive: Boolean get() = _preview.value != null

    /**
     * One key press: [direction] +1 = channel down, -1 = channel up. A repeat of a held key
     * ([repeatCount] > 0) that comes sooner than the step interval after the last step is dropped.
     */
    fun step(direction: Int, repeatCount: Int = 0) {
        val now = clock()
        val last = lastStepAt
        if (repeatCount > 0 && last != null && now - last < stepIntervalMillis) return
        lastStepAt = now
        scope.launch {
            // Steps resolve in press order, each from the one before.
            lock.withLock {
                val from = targetId ?: stepper.playingStreamId ?: return@withLock
                val next = stepper.neighbour(from, direction) ?: return@withLock
                targetId = next.streamId
                _preview.value = ZapPreview(next.streamId, cardFor(next, clock()))
                settleJob?.cancel()
                if (repeatCount == 0) {
                    // A tap (the first press of a hold too) tunes at once; any pending settle is replaced.
                    settleJob = null
                    commit()
                } else {
                    settleJob = scope.launch {
                        delay(settleMillis)
                        commit()
                    }
                }
            }
        }
    }

    private fun commit() {
        val id = targetId ?: return
        targetId = null
        settleJob = null
        if (id == stepper.playingStreamId) return
        beforeTune()
        stepper.tune(id)
    }

    /** The key came up: a held key tunes now rather than waiting out the settle time (which stays as the fallback). */
    fun release() {
        if (targetId == null) return
        settleJob?.cancel()
        commit()
    }

    /** Hides the panel; a pending target keeps it up until it tunes. */
    fun dismiss() {
        if (targetId == null) _preview.value = null
    }

    /** Drops a pending target and the panel (the player left the screen). */
    fun cancel() {
        settleJob?.cancel()
        settleJob = null
        targetId = null
        lastStepAt = null
        _preview.value = null
    }

    private fun cardFor(target: ZapTarget, nowUtcMillis: Long): PlayerInfoCard {
        val now = target.nowNext?.now
        return PlayerInfoCard(
            channelLine = PlayerFormatting.channelLine(target.number, target.name),
            title = now?.title?.takeIf { it.isNotBlank() } ?: PROGRAM_INFO_UNAVAILABLE,
            timeRange = now?.let { PlayerFormatting.rangeLabel(it.startUtcMillis, it.endUtcMillis) },
            timeLeft = now?.let { PlayerInfoCard.timeLeftLabel(it.endUtcMillis, nowUtcMillis) },
            description = null,
            nextTitle = null,
            nextRange = null,
        )
    }
}

/** What an Up or Down press does in the player (#224). */
internal enum class VerticalKeyAction {
    /** Live, controls hidden: the channel above. */
    ChannelUp,

    /** Live, controls hidden: the channel below. */
    ChannelDown,

    /** Swallow the key: Up on the open live controls (reserved), or Down when the row cannot open. */
    Ignore,

    /** Show the controls and toggle Now / Next (replay, and live without a channel list). */
    ToggleProgramInfo,

    /** Movie and episode controls: Up/Down move between the buttons and the scrubber. */
    MovieScrubber,

    /** An open panel's focus graph moves the selection. */
    PassToPanel,
}

/**
 * Routes Up/Down once the recent row has declined the key. Live playback with a [channelList] steps
 * channels while the controls are hidden; with them open Down belongs to the recent row (routed before
 * this) and Up does nothing. Now / Next is reached from its button only. Replay and movies keep their
 * behavior.
 */
internal fun verticalKeyAction(
    down: Boolean,
    mode: PlaybackMode?,
    controlsVisible: Boolean,
    tracksPanelOpen: Boolean,
    channelList: Boolean,
): VerticalKeyAction = when {
    mode == PlaybackMode.Movie && controlsVisible && !tracksPanelOpen -> VerticalKeyAction.MovieScrubber
    tracksPanelOpen -> VerticalKeyAction.PassToPanel
    mode == PlaybackMode.Live && channelList ->
        if (controlsVisible) {
            VerticalKeyAction.Ignore
        } else if (down) {
            VerticalKeyAction.ChannelDown
        } else {
            VerticalKeyAction.ChannelUp
        }
    else -> VerticalKeyAction.ToggleProgramInfo
}
