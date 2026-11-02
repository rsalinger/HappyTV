package com.rysal.happytv.ui.login

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rysal.happytv.data.Graph
import com.rysal.happytv.MainActivity
import com.rysal.happytv.ui.theme.HappyTvCanvas
import com.rysal.happytv.ui.theme.HappyTvTheme
import kotlinx.coroutines.launch

/**
 * The launcher activity — the first thing the user sees after the splash.
 * design spec §"Screens: splash, launcher banner and sign in".
 *
 * **Launch routing (issue #30).** This is the manifest's LAUNCHER entry, so it
 * first decides where the launch lands, while the system splash stays up
 * ([LaunchRouter]): with a complete stored credential set it starts
 * [MainActivity] and finishes without ever composing a sign-in frame; without
 * one it shows sign-in. Anything that must show sign-in while credentials
 * exist (Switch account, the dashboard's signed-out Sign in button) launches
 * it through [signInIntent], which sets [EXTRA_SHOW_SIGN_IN].
 *
 * When it shows sign-in it hosts [LoginScreen] inside the design canvas and
 * owns two pieces of behaviour that are not layout:
 *
 *  * the 200 ms cross-fade to [MainActivity] once the panel accepts the login
 *    (handoff "Success: a 200 ms fade to the dashboard"), and
 *  * Back, which closes the URL formats dialog or the unencrypted-server sheet
 *    when one is open.
 *
 * Typing uses the system keyboard (Gboard) — see [TvTextField]. Nothing here
 * draws a keyboard.
 */
class LoginActivity : ComponentActivity() {

    private val viewModel: LoginViewModel by viewModels { Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the Android 12+ splash *before* super.onCreate — that is the
        // only point at which the platform still owns the launch window. The
        // flash is brand-aligned (mark on Ground) and held for
        // [SPLASH_FLASH_MS] so it reads as an intentional beat rather than a
        // frame; pre-12 the same theme shows its Ground windowBackground.
        val splash = installSplashScreen()
        val shownAt = android.os.SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition {
            val elapsed = android.os.SystemClock.uptimeMillis() - shownAt
            when (route) {
                // Still deciding: hold the splash, capped as a backstop (the
                // router's own timeout already falls through to sign-in).
                null -> elapsed < LaunchRouter.SPLASH_CAP_MS
                // Brand beat before the sign-in card, as before #30.
                LaunchRoute.SignIn -> elapsed < SPLASH_FLASH_MS
                // Leaving for the dashboard: nothing to hold.
                LaunchRoute.Dashboard -> false
            }
        }

        super.onCreate(savedInstanceState)

        val forceSignIn = intent?.getBooleanExtra(EXTRA_SHOW_SIGN_IN, false) == true
        lifecycleScope.launch {
            val decided = LaunchRouter(
                credentialsPresent = {
                    // One Keystore read, on the router's IO thread. Hand the
                    // result to the coordinator so the dashboard's first frame
                    // is already signed in (no second read to wait for).
                    val stored = Graph.securedCredentials.current()
                    if (stored != null) {
                        runCatching {
                            Graph.refreshCoordinator.primeSignedIn(stored.savedAtUtcMillis)
                        }
                    }
                    stored != null
                },
            ).decide(forceSignIn)
            when (decided) {
                LaunchRoute.Dashboard -> {
                    // No setContent: the sign-in screen must never draw.
                    // (The post-launch guide kick already ran in
                    // HappyTvApp.onCreate; see kickPostSignIn, F-47.)
                    startActivity(
                        Intent(this@LoginActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }
                LaunchRoute.SignIn -> showSignIn()
            }
            // Set last: content (if any) is attached before the splash may lift.
            route = decided
        }
    }

    /** Set once the launch decision is made; read by the splash condition. */
    @Volatile
    private var route: LaunchRoute? = null

    private fun showSignIn() {
        setContent {
            HappyTvTheme {
                HappyTvCanvas {
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    BackHandler(enabled = state.overlay != LoginOverlay.None) {
                        when (state.overlay) {
                            is LoginOverlay.UrlFormats -> viewModel.onCloseUrlFormats()
                            is LoginOverlay.CleartextSheet -> viewModel.onSheetCancel()
                            LoginOverlay.None -> Unit
                        }
                    }

                    LoginScreen(
                        state = state,
                        onServerUrlChange = viewModel::onServerUrlChange,
                        onUsernameChange = viewModel::onUsernameChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onRememberMeToggle = viewModel::onRememberMeToggle,
                        onSubmit = viewModel::onSubmit,
                        onRetry = viewModel::onRetry,
                        onChangeServer = viewModel::onChangeServer,
                        onM3uPressed = viewModel::onM3uPressed,
                        onOpenUrlFormats = viewModel::onOpenUrlFormats,
                        onCloseUrlFormats = viewModel::onCloseUrlFormats,
                        onPickUrlFormat = viewModel::onPickUrlFormat,
                        onPickSavedServer = viewModel::onPickSavedServer,
                        onSheetUseHttps = viewModel::onSheetUseHttps,
                        onSheetContinueAnyway = viewModel::onSheetContinueAnyway,
                        onSheetCancel = viewModel::onSheetCancel,
                        onFocusHandled = viewModel::onFocusHandled,
                    )

                    // 200 ms cross-fade to the dashboard, once.
                    LaunchedEffect(state.isAuthenticated) {
                        if (state.isAuthenticated) {
                            // F-47: kick off a one-shot guide refresh in
                            // parallel with the cross-fade so the Live TV
                            // rail has its now-line populated by the time
                            // it lands on the dashboard (and the dashboard
                            // can finish its own initial 3-tile refresh).
                            // The periodic schedule also fires, but at
                            // epgRefreshHours intervals (default 12) — for
                            // a brand-new sign-in that is "hours from
                            // now", which is the bug.
                            com.rysal.happytv.data.guide.RefreshGuideScheduler.refreshNow(
                                this@LoginActivity.applicationContext,
                            )
                            startActivity(
                                Intent(this@LoginActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                            finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Explicit "show sign-in" request. Without it a launch with stored
         * credentials goes straight to the dashboard (issue #30), so every
         * caller that wants the sign-in screen while credentials may exist
         * must use [signInIntent].
         */
        const val EXTRA_SHOW_SIGN_IN = "com.rysal.happytv.extra.SHOW_SIGN_IN"

        /** An intent that always lands on the sign-in screen. */
        fun signInIntent(context: android.content.Context): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_SHOW_SIGN_IN, true)

        /**
         * The launch flash's minimum on-screen time (design spec §"Screens: splash"
         * is "gone before anyone reads it"; the polish task pins it to 200 ms,
         * the same beat the success cross-fade uses below).
         */
        private const val SPLASH_FLASH_MS = 200L

        /** Graph-backed factory — the same seams the unit tests fake. */
        val Factory = viewModelFactory {
            initializer {
                LoginViewModel(
                    auth = CoordinatorLoginAuthGateway(Graph.refreshCoordinator),
                    savedServersSource = RoomSavedServersSource(
                        database = Graph.database,
                        credentials = Graph.securedCredentials,
                    ),
                    cleartextConsent = Graph.cleartextConsent,
                )
            }
        }
    }
}
