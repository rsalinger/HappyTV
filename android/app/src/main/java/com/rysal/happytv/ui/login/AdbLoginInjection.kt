package com.rysal.happytv.ui.login

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.rysal.happytv.BuildConfig
import com.rysal.happytv.MainActivity
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.network.LoginResult
import com.rysal.happytv.security.CleartextConsentStore
import com.rysal.happytv.security.CleartextDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Debug-build-only `adb shell am broadcast` shortcut for sign-in.
 *
 * ## Why it exists
 * The sign-in screen is a 3-field Compose form on a TV. Workers (and the human
 * owner building the next fix) often need to "just be logged in" without
 * driving the on-screen keyboard field by field. This receiver is the single
 * `adb` invocation that runs the same `RefreshCoordinator.login` the UI does,
 * then routes to [MainActivity] exactly like the screen's success path.
 *
 * ## Why a runtime-registered receiver, not a manifest entry
 * A manifest entry would ship in release builds and pin the app's surface
 * area for a tooling-only API. Registering here, gated on [BuildConfig.DEBUG],
 * means a release APK has no extra broadcast surface at all — the action name
 * is a dangling string nobody listens for.
 *
 * ## Who may send it (issue #20)
 * The receiver is `RECEIVER_EXPORTED` (Android 14 will not deliver an
 * `adb shell am broadcast` from uid 2000 to a `NOT_EXPORTED` receiver), so
 * the sender is restricted in two layers:
 *
 *  1. **[BROADCAST_PERMISSION] (`android.permission.DUMP`) on the
 *     registration.** The system only delivers the broadcast if the sender
 *     holds it. DUMP is a `signature|privileged|development` permission that
 *     the shell package (uid 2000) is granted in its own manifest, and root
 *     holds everything; an ordinary installed app can neither declare nor be
 *     granted it. ActivityManager checks it from the sender's uid/pid, so it
 *     needs no cooperation from the sender and works on every API level
 *     (the permissioned `registerReceiver` overload exists since API 1;
 *     [ContextCompat] supplies the 6-argument form below API 26 and ignores
 *     the export flag below API 33).
 *  2. **[InjectionSenderPolicy] on API 34+.** [BroadcastReceiver.getSentFromUid]
 *     names the sender uid; anything other than shell (2000) or root (0) is
 *     dropped even if the permission layer somehow let it through. Below
 *     API 34 (or when the uid is unknown, -1) layer 1 alone applies.
 *
 * A rejected broadcast logs one line with the uid and no extras.
 *
 * ## Wire
 * ```
 * adb shell am broadcast \
 *     -a com.rysal.happytv.INJECT_CREDENTIALS \
 *     -p com.rysal.happytv \
 *     --es host <host> \
 *     --es username <u> --es password <p> \
 *     [--es displayName "QA"]
 * ```
 * Debug builds use the same package id as release, `com.rysal.happytv`.
 *
 * Result lands in logcat under [TAG] (the implicit broadcast surface is
 * empty when the receiver swallows it; logcat is the verification channel).
 */
object AdbLoginInjection {
    /** Stable action string. The `am broadcast -a …` invocation refers here. */
    const val ACTION_INJECT_CREDENTIALS = "com.rysal.happytv.INJECT_CREDENTIALS"

    /** String extra: panel host or full URL, e.g. `"provider.example"` or `http://provider.example:8080`. */
    const val EXTRA_HOST = "host"

    /** String extra: panel username. Required. */
    const val EXTRA_USERNAME = "username"

    /** String extra: panel password. Required. */
    const val EXTRA_PASSWORD = "password"

    /** String extra: optional display name for the Room `login_profiles` row. */
    const val EXTRA_DISPLAY_NAME = "displayName"

    internal const val TAG = "LoginInjection"

    /**
     * Permission a sender must hold. Granted to the shell uid, never to a
     * third-party app (see the class comment).
     */
    internal const val BROADCAST_PERMISSION = "android.permission.DUMP"

    /** True exactly when [register] should attach the receiver. */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /**
     * Register the receiver if and only if [BuildConfig.DEBUG] is true (the
     * guard so release builds never observe this action). Idempotent: a
     * second call replaces the prior registration cleanly.
     */
    fun register(appContext: Context) {
        if (!enabled) return
        ContextCompat.registerReceiver(
            appContext,
            Receiver(),
            IntentFilter(ACTION_INJECT_CREDENTIALS),
            BROADCAST_PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(
            TAG,
            "AdbLoginInjection receiver registered (debug variant ${BuildConfig.APPLICATION_ID})",
        )
    }

    private class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val uid = if (Build.VERSION.SDK_INT >= 34) sentFromUid else null
            if (!InjectionSenderPolicy.isTrusted(uid)) {
                Log.w(TAG, "Rejected INJECT_CREDENTIALS from uid $uid (not shell or root)")
                return
            }
            val parsed = parseIntent(intent)
            if (parsed == null) {
                // No field error UI; the operator reads the reason in logcat.
                Log.w(
                    TAG,
                    "Ignored INJECT_CREDENTIALS: host/username/password missing or host unparseable",
                )
                return
            }
            AdbLoginHandler(
                coordinator = CoordinatorLoginAuthGateway(Graph.refreshCoordinator),
                cleartextConsent = Graph.cleartextConsent,
                launcher = ApplicationContextAdbLoginLauncher(context.applicationContext),
                scope = Graph.appScope,
            ).handleAsync(parsed)
        }
    }
}

/**
 * Sender allow-list for the injection broadcast. Pure so it is unit-tested on
 * the JVM; the receiver feeds it [BroadcastReceiver.getSentFromUid] on API 34+.
 */
internal object InjectionSenderPolicy {
    private const val ROOT_UID = 0

    /**
     * [sentFromUid] null or negative means the platform could not say who sent
     * it (API below 34, or -1); the registration's permission check has
     * already restricted the sender to shell/root in that case, so it is
     * accepted. Any known uid must be shell or root.
     */
    fun isTrusted(sentFromUid: Int?): Boolean =
        sentFromUid == null || sentFromUid < 0 ||
            sentFromUid == Process.SHELL_UID || sentFromUid == ROOT_UID
}

/**
 * Pure intent→request parsing — the only piece unit-tested without an Android
 * `Context`. Returning null means "ignore this broadcast; log it" (rather
 * than throwing in production).
 *
 * Kept in this file (instead of the receiver) so [AdbLoginInjection] can be
 * exercised from `src/test` purely with [Map] inputs — no `Intent` or
 * Robolectric required.
 */
internal fun parseExtras(extras: Map<String, String?>): AdbLoginRequest? {
    val host = extras[AdbLoginInjection.EXTRA_HOST]?.trim().orEmpty()
    val username = extras[AdbLoginInjection.EXTRA_USERNAME]?.trim().orEmpty()
    val password = extras[AdbLoginInjection.EXTRA_PASSWORD].orEmpty()
    val displayName = extras[AdbLoginInjection.EXTRA_DISPLAY_NAME]?.trim().orEmpty()
    if (host.isEmpty() || username.isEmpty() || password.isEmpty()) return null
    val parsed = parsePanelUrl(host) ?: return null
    return AdbLoginRequest(
        host = parsed.host,
        port = parsed.port,
        useTls = parsed.useTls,
        displayName = parsed.displayName,
        username = username,
        password = password,
        explicitDisplayName = displayName,
    )
}

/**
 * The receiver-side wrapper around [parseExtras] that pulls extras off an
 * [Intent] (an Android type JUnit can't stub for free). The action check is
 * also here — a broadcast carrying the wrong action must never reach the
 * downstream code, even if the extras happen to be valid.
 */
internal fun parseIntent(intent: Intent): AdbLoginRequest? {
    if (AdbLoginInjection.ACTION_INJECT_CREDENTIALS != intent.action) return null
    // Intent.getStringExtra returns null for absent keys; pull every extra
    // through that nullable type and hand to the pure parser.
    val extras = mapOf(
        AdbLoginInjection.EXTRA_HOST to intent.getStringExtra(AdbLoginInjection.EXTRA_HOST),
        AdbLoginInjection.EXTRA_USERNAME to intent.getStringExtra(AdbLoginInjection.EXTRA_USERNAME),
        AdbLoginInjection.EXTRA_PASSWORD to intent.getStringExtra(AdbLoginInjection.EXTRA_PASSWORD),
        AdbLoginInjection.EXTRA_DISPLAY_NAME to intent.getStringExtra(AdbLoginInjection.EXTRA_DISPLAY_NAME),
    )
    return parseExtras(extras)
}

/** A parsed adb-injection login request, decoupled from any Android type. */
internal data class AdbLoginRequest(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val displayName: String,
    val username: String,
    val password: String,
    /** Caller-supplied display name from the broadcast, if any. Empty == derive from host. */
    val explicitDisplayName: String,
) {
    /**
     * The display name handed to [com.rysal.happytv.data.network.RefreshCoordinator.login] —
     * the explicit one (if non-blank) wins over the host-derived fallback.
     */
    fun effectiveDisplayName(): String =
        if (explicitDisplayName.isNotBlank()) explicitDisplayName else displayName
}

/**
 * The Launcher's intent — a small typed seam so the post-login `startActivity`
 * call is testable on the JVM (plain-JVM unit tests cannot stub the Intent
 * the way Robolectric does, so we hide the Android types behind this).
 */
internal interface AdbLoginLauncher {
    fun launchMainFromBackground()

    /**
     * Application context for non-Intent side effects (F-47 follow-up:
     * the post-login guide-refresh one-shot). Returns the same context
     * every call — implementations cache it.
     */
    fun applicationContext(): Context
}

/** The real launcher: starts MainActivity with the same flags the UI uses on success. */
internal class ApplicationContextAdbLoginLauncher(
    private val appContext: Context,
) : AdbLoginLauncher {
    override fun launchMainFromBackground() {
        val launch = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        appContext.startActivity(launch)
        // No 200 ms cross-fade: `overridePendingTransition` is an Activity
        // method, and the receiver runs from the Application Context. The UI
        // keeps its own fade inside LoginActivity; debug UX is closer to a
        // cold start than a re-entered login. (Release never reaches here.)
    }

    override fun applicationContext(): Context = appContext
}

/**
 * The receiver's coroutine body — split off so the parse → consent → login →
 * intent pipeline reads top-down and a unit test can advance it
 * deterministically against a fake coordinator, fake cleartext store, and
 * fake launcher.
 *
 * Logging convention: every outcome prints a single line on
 * [AdbLoginInjection.TAG] so `adb logcat -s LoginInjection` is the one-liner
 * to verify success/failure. No network-level credential logging (only the
 * panel's *failure* message, which the UI already shows word-for-word).
 */
internal class AdbLoginHandler(
    private val coordinator: LoginAuthGateway,
    private val cleartextConsent: CleartextConsentStore,
    private val launcher: AdbLoginLauncher,
    private val scope: CoroutineScope,
) {
    /** Fire-and-forget on [scope]. The broadcast returns immediately. */
    fun handleAsync(request: AdbLoginRequest): Job = scope.launch { handle(request) }

    private suspend fun handle(request: AdbLoginRequest) {
        // Record cleartext consent up front if the host has none — the
        // coordinator's login path takes whatever scheme we hand it, so an
        // http:// host without consent would silently refuse when the panel
        // only answers on http (the QA panel does). The receiver is
        // debug-only and the human owner running the broadcast is
        // consenting deliberately; the log line names the host so the
        // operator sees it.
        if (!request.useTls && cleartextConsent.decision(request.host, request.port) == null) {
            cleartextConsent.remember(request.host, request.port, CleartextDecision.ContinueOverHttp)
            Log.i(
                AdbLoginInjection.TAG,
                "Auto-recorded cleartext consent for ${request.host}:${request.port} (debug adb injection)",
            )
        }

        val result = try {
            coordinator.login(
                host = request.host,
                port = request.port,
                useTls = request.useTls,
                username = request.username,
                password = request.password,
                displayName = request.effectiveDisplayName(),
            )
        } catch (t: Throwable) {
            Log.e(AdbLoginInjection.TAG, "login threw for ${request.host}", t)
            return
        }
        when (result) {
            is LoginResult.Success -> {
                Log.i(
                    AdbLoginInjection.TAG,
                    "Login OK: ${request.host}:${request.port} — launching MainActivity",
                )
                runCatching { coordinator.refreshAll() }
                // F-47 follow-up: the debug ADB injection skips
                // LoginActivity's UI flow entirely, so its
                // `LaunchedEffect(isAuthenticated)` trigger never fires.
                // Call the same entry-point-agnostic kick the
                // HappyTvApp.onCreate path uses, so the on-TV QA pass sees
                // the guide populated in the same window the UI flow does.
                com.rysal.happytv.data.guide.RefreshGuideScheduler
                    .kickPostSignIn(launcher.applicationContext())
                launcher.launchMainFromBackground()
            }
            is LoginResult.Failure -> {
                // The panel's own message ("Subscription expired", …) is what
                // RefreshCoordinator produces — log it so the operator sees
                // WHY. Same word-for-word as the UI's WrongCredentials slot.
                Log.w(
                    AdbLoginInjection.TAG,
                    "Login FAILED (${request.host}:${request.port} ): ${result.message}",
                )
            }
        }
    }
}
