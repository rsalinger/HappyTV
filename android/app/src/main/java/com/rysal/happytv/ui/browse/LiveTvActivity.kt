package com.rysal.happytv.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.LiveTvView
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.player.HostMode
import com.rysal.happytv.ui.player.LongBackDecision
import com.rysal.happytv.ui.player.PREVIEW_HOLD_MILLIS
import com.rysal.happytv.ui.player.routeBack
import com.rysal.happytv.ui.player.LongBackSequence
import com.rysal.happytv.ui.player.PlaybackHost
import com.rysal.happytv.ui.player.PlayerHandleBridge
import com.rysal.happytv.ui.player.PlayerLaunch
import com.rysal.happytv.ui.player.PlayerLaunch.Companion.put
import com.rysal.happytv.ui.player.PlayerViewModel
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The combined Live TV screen: a category rail beside either the TV guide grid
 * or the channel list (spec #7 section 3), and the one player host for live
 * playback (#16).
 *
 * Opened by the dashboard's Live TV tile (last-used view) and by its TV guide
 * tile ([EXTRA_START_VIEW] = guide: always the guide). It hosts [BrowseNavHost]
 * at the `live_tv` route, which is where the screen's Compose navigation starts.
 *
 * Live playback happens here, not in `PlayerActivity`: [PlaybackHost] starts
 * Idle, a channel's OK plays it full screen, and Back shrinks the same surface
 * into the preview slot (see [LiveTvPlaybackLayer]). Search routes a channel in
 * through [onNewIntent] / the launch intent ([playIntent]). Home, screen off or
 * an opaque screen on top stop playback with no auto-resume (design note on
 * #11); movies and replay keep `PlayerActivity`.
 */
class LiveTvActivity : ComponentActivity() {

    private lateinit var playerViewModel: PlayerViewModel
    private lateinit var host: PlaybackHost
    private val longBack = LongBackSequence()

    /** Set by the playback layer: stop playback and put focus back on that channel. */
    private var longBackStop: (() -> Unit)? = null

    /** Set by the playback layer: a 2 s hold in the preview returns to full screen (#138). */
    private var holdReturn: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A tile click already set this mark; an `am start` launch gets one here.
        HtvPerf.startIfNotRecent(HtvPerf.LIVETV_OPEN)
        val startView = if (intent.getStringExtra(EXTRA_START_VIEW) == VIEW_GUIDE) LiveTvView.GUIDE else null

        playerViewModel = ViewModelProvider(
            this,
            PlayerViewModel.Factory(null, PlayerHandleBridge()),
        )[PlayerViewModel::class.java]
        host = PlaybackHost(
            context = this,
            scope = lifecycleScope,
            viewModel = playerViewModel,
            handle = playerViewModel.handle as PlayerHandleBridge,
            isFinishing = { isFinishing },
        )
        // A recreation that still happens (process death restore, a config change
        // configChanges does not cover) falls back to the Home path: stop, no crash.
        if (savedInstanceState != null) playerViewModel.stop()
        host.attach()

        // "Back while watching" is read live; Show channels until the first value lands.
        lifecycleScope.launch {
            Graph.settings.settings.map { it.backWhileWatching }.collect { host.backWhileWatching = it }
        }

        val scope = lifecycleScope
        setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    DisposableEffect(Unit) {
                        // F-54: the destroy path (Back out of Live TV, finish, system reclaim).
                        onDispose { host.release() }
                    }
                    LiveTvPlaybackLayer(
                        host = host,
                        playerViewModel = playerViewModel,
                        startView = startView,
                        onExit = { finish() },
                        onRefreshLive = { scope.launch { Graph.refreshCoordinator.refreshLiveOnly() } },
                        registerLongBackStop = { longBackStop = it },
                        registerHoldReturn = { holdReturn = it },
                    )
                }
            }
        }

        // A channel handed in by Search (a fresh launch rather than onNewIntent).
        if (savedInstanceState == null) playRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        playRequested(intent)
    }

    /** Plays the live channel an intent carries (Search), if any. */
    private fun playRequested(intent: Intent) {
        val launch = PlayerLaunch.from(intent)?.takeIf { it.streamType == StreamType.LIVE && !it.isReplay } ?: return
        // Consumed: a later onStart/recreate must not replay it.
        intent.removeExtra(PlayerLaunch.EXTRA_STREAM_ID)
        host.play(launch)
    }

    /**
     * Long-press Back stops playback in full screen, and a 2 s hold in the preview returns to it, even before the player has focus (the gap right
     * after OK enters Full). Runs ahead of Compose; see [LongBackSequence].
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && ::host.isInitialized) {
            // #138: the preview's 2 s hold runs on a timer from the key-down.
            when (event.action) {
                KeyEvent.ACTION_DOWN ->
                    if (event.repeatCount == 0 && host.mode == HostMode.Preview) {
                        holdHandler.removeCallbacks(holdTimeout)
                        holdHandler.postDelayed(holdTimeout, PREVIEW_HOLD_MILLIS)
                    }
                KeyEvent.ACTION_UP -> holdHandler.removeCallbacks(holdTimeout)
            }
            if (longBack.routeBack(event, host.mode) { longBackStop?.invoke() ?: host.stopPlayback() }) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val holdHandler = Handler(Looper.getMainLooper())
    private val holdTimeout = Runnable {
        if (::host.isInitialized && longBack.onHoldTimeout(host.mode) == LongBackDecision.FireReturn) {
            holdReturn?.invoke() ?: host.enterFull()
        }
    }

    override fun onStop() {
        super.onStop()
        holdHandler.removeCallbacks(holdTimeout)
        host.onActivityStopped(finishing = isFinishing)
    }

    companion object {
        /** Intent extra: open in this view instead of the remembered one. */
        const val EXTRA_START_VIEW = "com.rysal.happytv.extra.LIVE_TV_START_VIEW"

        /** [EXTRA_START_VIEW] value for the dashboard's TV guide tile. */
        const val VIEW_GUIDE = "guide"

        /** The dashboard's TV guide tile: Live TV, always in Guide view (spec #7 section 3.6). */
        fun guideIntent(context: Context): Intent =
            Intent(context, LiveTvActivity::class.java).putExtra(EXTRA_START_VIEW, VIEW_GUIDE)

        /** The dashboard's Live TV tile: the remembered view (no extra). */
        fun liveTvIntent(context: Context): Intent = Intent(context, LiveTvActivity::class.java)

        /**
         * Search's channel result: Live TV, playing [streamId] full screen. Carries the
         * stream id only (no credentials); a Live TV already open is reused and told via
         * [onNewIntent].
         */
        fun playIntent(context: Context, streamId: String): Intent =
            Intent(context, LiveTvActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .put(PlayerLaunch.live(streamId = streamId))
    }
}
