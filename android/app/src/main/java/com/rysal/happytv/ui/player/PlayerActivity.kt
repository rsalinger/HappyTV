@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.rysal.happytv.ui.player

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The player destination — design spec §"Screens: player" + task §1/§4/§5.
 *
 * A thin host: [PlaybackHost] owns the [androidx.media3.exoplayer.ExoPlayer],
 * the F-54/F-56 lifecycle, the Media3 listeners, the position tick and the
 * effect collector; [PlayerSurface] draws the one video surface;
 * [PlayerScreen] draws the chrome and routes the D-pad into
 * [PlayerViewModel], which owns all state. This activity forwards the
 * lifecycle callbacks and owns the exit paths (Back, auth, replay gate).
 *
 * Android 14 note (task §10): v1 deliberately runs **no** foreground
 * mediaPlayback service — the player dies with the activity. There is no
 * picture-in-picture (#28): Home and screen-off stop playback.
 */
class PlayerActivity : ComponentActivity() {

    private companion object {
        const val TAG = "PlayerF48"
    }

    private lateinit var viewModel: PlayerViewModel
    private lateinit var host: PlaybackHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launch = PlayerLaunch.from(intent) ?: run {
            Log.w(TAG, "onCreate: PlayerLaunch.from(intent) returned null → finish()")
            finish()
            return
        }
        Log.i(TAG, "onCreate: PlayerLaunch streamType=${launch.streamType} streamId=${launch.streamId} " +
            "extension=${launch.extension} hasExplicitConnection=${launch.hasExplicitConnection} " +
            "isReplay=${launch.isReplay}")

        val handle = PlayerHandleBridge()
        viewModel = ViewModelProvider(
            this,
            PlayerViewModel.Factory(launch, handle),
        )[PlayerViewModel::class.java]
        host = PlaybackHost(
            context = this,
            scope = lifecycleScope,
            viewModel = viewModel,
            handle = handle,
            isFinishing = { isFinishing },
        )

        setContent {
            HappyTvTheme(alwaysDark = true) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val ready = state as? PlayerUiState.Ready

                // F-54: the destroy path. Runs on BACK too (`isFinishing`
                // short-circuits the onStop release), so this is the teardown
                // the back-out repro actually takes.
                DisposableEffect(Unit) {
                    onDispose { host.release() }
                }

                Box(modifier = Modifier.fillMaxSize().background(PlayerColors.VideoBlack)) {
                    PlayerSurface(
                        player = host.exoPlayer,
                        dimmed = ready?.content?.isBuffering == true,
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlayerScreen(
                        viewModel = viewModel,
                        onBack = { finish() },
                        // #66: a hold on Back stops and returns to details
                        // (consistent with Live TV's long-press Stop); without
                        // it the repeats re-toggled the leave dialog.
                        onLongBack = { finish() },
                    )
                }
            }
        }

        host.attach()

        // #84: the last episode ended, back to details.
        lifecycleScope.launch {
            viewModel.endOfPlayback.collect { if (it == EndOfPlayback.ReturnToDetails) finish() }
        }

        // Exit paths the activity owns: replay without an archive, no login.
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                Log.i(TAG, "state observer: ${state::class.simpleName}")
                when (state) {
                    is PlayerUiState.ReplayUnsupported -> {
                        Log.w(TAG, "state: ReplayUnsupported → toast + finish()")
                        Toast.makeText(this@PlayerActivity, state.message, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    PlayerUiState.NeedsAuth -> {
                        Log.w(TAG, "state: NeedsAuth → finish()")
                        finish()
                    }
                    else -> Unit
                }
            }
        }
    }

    private val longBack = LongBackSequence()

    /**
     * A Back hold stops and returns to details even when nothing in the Compose
     * tree has focus (#66 QA). Compose sees the initial Back down first and a
     * short Back acts on release, so the hold must be recognised here.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::host.isInitialized && longBack.routeBack(event, HostMode.Full) { finish() }) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart")
        // `finish()` in onCreate (unusable intent) returns before the host
        // is built, and Android still walks this activity through onStart.
        if (!::host.isInitialized) return
        host.onStart()
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop: isFinishing=$isFinishing")
        if (!::host.isInitialized) return
        host.onStop()
    }
}
