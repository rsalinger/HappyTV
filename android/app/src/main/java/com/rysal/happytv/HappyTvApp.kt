package com.rysal.happytv

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.rysal.happytv.data.Graph
import com.rysal.happytv.ui.login.AdbLoginInjection
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale
import com.rysal.happytv.perf.HtvPerf
import com.rysal.happytv.ui.theme.HappyTvThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Application subclass — referenced from AndroidManifest as ".HappyTvApp".
 *
 * Startup order:
 *  1. [attachBaseContext] — [verifySigningCertificate] runs **before** `super` so
 *     an official release APK that was re-signed with another key dies here,
 *     before any code we care about runs. The check only exists in release
 *     builds made with `HAPPYTV_RELEASE_CERT_SHA256` set; everywhere else it is
 *     skipped, so a build from source starts normally.
 *  2. [onCreate] — `Graph.init(this)` (manual DI; every Graph member stays
 *     lazy), then the [ProcessLifecycleOwner] foreground/background observer.
 *
 * Manual DI wiring lives in [Graph] (database, okHttpClient, json, settings,
 * securedCredentials, xtreamApi); this class only hands it the Application
 * context. Swapping to Hilt in v2 means annotating this class and deleting the
 * [Graph.init] call — nothing else in the app constructs those singletons
 * directly.
 */
class HappyTvApp : Application() {

    override fun attachBaseContext(base: Context) {
        // Fail-fast first (official release builds only): nothing else should
        // run in a process whose signing certificate is not the expected one.
        verifySigningCertificate(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        // First and only wiring step for v1: everything on Graph then resolves
        // lazily on first access (no startup cost for code paths not taken).
        Graph.init(this)

        // #126: light/dark is process-wide state, set before any activity draws
        // its first frame so there is no dark-then-light flash inside the app.
        // One small DataStore read; later changes flow in from the collector.
        loadSavedTheme()

        // Debug-build-only sign-in shortcut: a `adb shell am broadcast
        // -a com.rysal.happytv.INJECT_CREDENTIALS …` that runs the same
        // RefreshCoordinator.login path as the UI, then launches MainActivity.
        // No-op on release builds (AdbLoginInjection.enabled gates it), so
        // the production broadcast surface is unchanged.
        AdbLoginInjection.register(this)

        // Foreground/background hooks (process-wide, with the 700ms stop-delay
        // semantics ProcessLifecycleOwner defines). v1 only logs; work that must
        // pause/resume on background registers against this same observer later.
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver)

        // #18: a derived XMLTV URL (with the password) used to be persisted in
        // plain settings. Replace it with the auto marker (or move a typed link
        // into the encrypted store) before anything reads it.
        Graph.appScope.launch {
            runCatching { Graph.settings.migrateLegacyEpgUrl(Graph.securedCredentials.current()) }
                .onFailure { Log.w(TAG, "XMLTV link migration failed: ${it::class.java.simpleName}") }
        }

        // Guide background refresh: enqueue the periodic RefreshGuideWorker
        // (settings.epgRefreshHours, KEEP so reopening never resets the timer).
        com.rysal.happytv.data.guide.RefreshGuideScheduler.ensureScheduledFromSettings(this)

        // F-47 follow-up: if the user lands here cold with a cached session
        // (LoginActivity was never started → its LaunchedEffect trigger
        // didn't fire), kick the post-sign-in one-shot so the Live TV
        // rail has its now-line populated by the time the dashboard
        // composes. Idempotent with the LoginActivity path thanks to
        // WorkManager's enqueueUniqueWork(UNIQUE_ONESHOT, REPLACE).
        com.rysal.happytv.data.guide.RefreshGuideScheduler.kickPostSignIn(this)

        Log.i(
            TAG,
            "HappyTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — " +
                "buildType=${BuildConfig.BUILD_TYPE}, applicationId=${BuildConfig.APPLICATION_ID}",
        )
    }

    /**
     * The saved choice is mirrored in a tiny SharedPreferences file so the first
     * read is synchronous and cheap (a blocking DataStore read cost about 750 ms
     * on the Chromecast). DataStore stays the source of truth: the collector
     * below applies its value and refreshes the mirror.
     */
    private fun loadSavedTheme() {
        val started = android.os.SystemClock.elapsedRealtime()
        val mirror = getSharedPreferences(THEME_MIRROR, Context.MODE_PRIVATE)
        HappyTvThemeState.light = runCatching { mirror.getBoolean(THEME_MIRROR_KEY, false) }.getOrDefault(false)
        HtvPerf.log("theme.load", android.os.SystemClock.elapsedRealtime() - started, "light=${HappyTvThemeState.light}")
        // Keep it in step with the setting (the dashboard button, a backup restore).
        Graph.appScope.launch(Dispatchers.Main.immediate) {
            Graph.settings.settings.map { it.lightMode }.distinctUntilChanged().collect {
                HappyTvThemeState.light = it
                mirror.edit().putBoolean(THEME_MIRROR_KEY, it).apply()
            }
        }
    }

    /**
     * Official-build check: when this build carries an expected certificate
     * digest ([BuildConfig.EXPECTED_CERT_SHA256], release builds made with
     * `HAPPYTV_RELEASE_CERT_SHA256` set), SHA-256 the signing certificate of our
     * own package and compare. With no digest baked in the check is skipped.
     *
     * `PackageManager.GET_SIGNATURES` (= 64) works on every API we support
     * (23+); it reports the app's signing certs whether the APK was signed with
     * scheme v1, v2 or v3.
     *
     * @throws RuntimeException when a digest is expected and the certificate is
     * missing, there is more than one, or it does not match.
     */
    @Suppress("DEPRECATION") // the constant is deprecated since API 28; its behaviour is not
    private fun verifySigningCertificate(context: Context) {
        if (normalizeCertDigest(BuildConfig.EXPECTED_CERT_SHA256).isEmpty()) return
        val signatures = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures
        val actual = signatures.orEmpty().map { sha256Hex(it.toByteArray()) }
        checkSigningCertificates(BuildConfig.EXPECTED_CERT_SHA256, actual, context.packageName)
    }

    /** SHA-256 over the certificate's DER bytes — zero-padded lowercase hex. */
    private fun sha256Hex(certificateBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificateBytes)
        return String.format(Locale.ROOT, "%064x", BigInteger(1, digest))
    }

    /** Process-wide foreground/background transitions (one shared instance). */
    private object AppLifecycleObserver : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_START -> Log.i(TAG, "app in foreground")
                Lifecycle.Event.ON_STOP -> Log.i(TAG, "app in background")
                else -> Unit
            }
        }
    }

    companion object {
        private const val TAG = "HappyTvApp"
        private const val THEME_MIRROR = "happytv_theme"
        private const val THEME_MIRROR_KEY = "light"

        /** Lowercase hex with colons and whitespace removed (keytool and apksigner formats). */
        internal fun normalizeCertDigest(raw: String): String =
            raw.filterNot { it == ':' || it.isWhitespace() }.lowercase(Locale.ROOT)

        /**
         * The decision, as a pure function. [expectedRaw] is the build's expected
         * digest in any common format; empty skips the check. Otherwise exactly
         * one certificate is required and its SHA-256 must match, ignoring case.
         *
         * @param actualDigests SHA-256 hex digests of the package's signing certs.
         */
        internal fun checkSigningCertificates(
            expectedRaw: String,
            actualDigests: List<String>,
            packageName: String = "this package",
        ) {
            val expected = normalizeCertDigest(expectedRaw)
            if (expected.isEmpty()) return
            if (actualDigests.isEmpty()) {
                throw RuntimeException("Signing certificates not found for $packageName")
            }
            if (actualDigests.size != 1) {
                throw RuntimeException(
                    "Package is signed by multiple signing certificates [${actualDigests.size}]",
                )
            }
            val actual = actualDigests[0]
            if (!actual.equals(expected, ignoreCase = true)) {
                throw RuntimeException(
                    "Certificate mismatch. Expecting sha256[$expected], current sha256[$actual]",
                )
            }
        }
    }
}
