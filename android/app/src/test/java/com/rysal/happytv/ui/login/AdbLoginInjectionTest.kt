package com.rysal.happytv.ui.login

import com.rysal.happytv.data.network.LoginResult
import com.rysal.happytv.security.CleartextConsentStore
import com.rysal.happytv.security.CleartextDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit-testable parts of [AdbLoginInjection]:
 *
 *  * [parseExtras] — the pure extras → request parser (covers the receivers'
 *    "ignore me" paths and the host/username/password/displayName plumbing).
 *    `src/test` cannot stub [android.content.Intent] without Robolectric, so
 *    the test hands the parser a plain map and the production receiver does
 *    the `Intent` → map step in [parseIntent].
 *  * [AdbLoginHandler.handleAsync] — the consent → login → log pipeline,
 *    against a fake coordinator and cleartext store, with
 *    [StandardTestDispatcher] so the assertion order is deterministic.
 *
 * The receiver itself (the `BroadcastReceiver` inside [AdbLoginInjection])
 * is a one-line wrapper around these two seams.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdbLoginInjectionTest {

    // ------------------------------------------------------------- parseExtras

    @Test
    fun parseExtras_httpsHost_defaultsPort_useTlsTrue() {
        val req = parseExtras(
            extras(
                host = "panel.example:8080",
                username = "u",
                password = "p",
            ),
        )!!
        assertEquals("panel.example", req.host)
        assertEquals(8080, req.port)
        assertTrue(req.useTls)
        assertEquals("u", req.username)
        assertEquals("p", req.password)
        assertEquals("", req.explicitDisplayName)
        assertEquals("panel.example", req.effectiveDisplayName())
    }

    @Test
    fun parseExtras_hostWithoutScheme_isTreatedAsHttps() {
        // The UI's parsePanelUrl semantics — propagate the same so the adb
        // path behaves the same when the operator forgets the scheme.
        val req = parseExtras(extras(host = "panel.example", username = "u", password = "p"))!!
        assertTrue(req.useTls)
        assertEquals(443, req.port)
        assertEquals("panel.example", req.host)
    }

    @Test
    fun parseExtras_explicitHttp_keepsHttpAndRecordsConsent() = runTest {
        val consent = InjectionConsent()
        val (handler, _) = handler(
            consent = consent,
            auth = InjectionAuth(result = LoginResult.Success(null, null)),
            scope = backgroundScope,
        )
        val request = parseExtras(
            extras(host = "http://panel.example:8080", username = "u", password = "p"),
        )!!
        runHandler(handler, request)

        assertFalse(request.useTls)
        assertEquals(CleartextDecision.ContinueOverHttp, consent.remembered["panel.example:8080"])
    }

    @Test
    fun parseExtras_alreadyConsentedHttp_doesNotOverwrite() = runTest {
        // Already-UseHttps must NOT be overwritten with ContinueOverHttp by
        // the handler's auto-consent path (the user previously chose HTTPS,
        // so signing in over http is a UX regression we should never do
        // silently).
        val consent = InjectionConsent(initial = mapOf("panel.example:8080" to CleartextDecision.UseHttps))
        val (handler, _) = handler(
            consent = consent,
            auth = InjectionAuth(result = LoginResult.Success(null, null)),
            scope = backgroundScope,
        )
        val req = parseExtras(extras(host = "http://panel.example:8080", username = "u", password = "p"))!!
        runHandler(handler, req)

        // The pre-seeded UseHttps decision is left alone — we did not ask
        // the panel via http, and the store still has the user's answer.
        assertEquals(CleartextDecision.UseHttps, consent.remembered["panel.example:8080"])
    }

    @Test
    fun parseExtras_httpsHost_doesNotTouchConsentStore() = runTest {
        val consent = InjectionConsent()
        val (handler, _) = handler(
            consent = consent,
            auth = InjectionAuth(result = LoginResult.Success(null, null)),
            scope = backgroundScope,
        )
        val req = parseExtras(extras(host = "https://panel.example", username = "u", password = "p"))!!
        runHandler(handler, req)

        assertTrue(consent.remembered.isEmpty())
        assertTrue(consent.decisionsAsked.isEmpty())
    }

    @Test
    fun parseExtras_trimsHostAndUsernameButNotPassword() {
        // Passwords may legitimately contain whitespace; only leading/trailing
        // blanks should be left alone. Host/username we trim because the UI
        // does (parsePanelUrl -> text.trim()).
        val req = parseExtras(
            extras(host = "  panel.example  ", username = "  u  ", password = " p ass "),
        )!!
        assertEquals("panel.example", req.host)
        assertEquals("u", req.username)
        assertEquals(" p ass ", req.password)
    }

    @Test
    fun parseExtras_explicitDisplayNameWinsOverHostDerived() {
        val req = parseExtras(
            extras(host = "panel.example", username = "u", password = "p", displayName = "  Home  "),
        )!!
        assertEquals("Home", req.explicitDisplayName)
        assertEquals("Home", req.effectiveDisplayName())
    }

    @Test
    fun parseExtras_returnsNull_whenHostMissing() {
        assertNull(parseExtras(extras(host = null, username = "u", password = "p")))
    }

    @Test
    fun parseExtras_returnsNull_whenUsernameBlank() {
        assertNull(parseExtras(extras(username = " ", password = "p")))
    }

    @Test
    fun parseExtras_returnsNull_whenUsernameMissing() {
        assertNull(parseExtras(extras(username = null, password = "p")))
    }

    @Test
    fun parseExtras_returnsNull_whenPasswordMissing() {
        // Null password (no extra at all) = null. Empty = null too.
        assertNull(parseExtras(extras(password = null)))
        assertNull(parseExtras(extras(password = "")))
    }

    @Test
    fun parseExtras_returnsNull_whenHostUnparseable() {
        // A bare token that OkHttp's URL parser rejects still flows through
        // parsePanelUrl and comes out null here.
        assertNull(parseExtras(extras(host = "not a url at all", username = "u", password = "p")))
    }

    // ------------------------------------------------------------ handler flow

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun handler_loginSuccess_recordsNoExtraConsent_callsRefreshAll() = runTest {
        val auth = InjectionAuth(result = LoginResult.Success(null, null))
        val consent = InjectionConsent()
        // Use backgroundScope (TestScope-coroutine) so advanceUntilIdle drains
        // the launched coroutine deterministically.
        val (h, launcher) = handler(consent = consent, auth = auth, scope = backgroundScope)

        val req = AdbLoginRequest(
            host = "panel.example",
            port = 8080,
            useTls = true,
            displayName = "panel.example",
            username = "u",
            password = "p",
            explicitDisplayName = "",
        )
        runHandler(h, req)

        assertEquals(1, auth.loginCalls.size)
        assertEquals("panel.example", auth.loginCalls.single().host)
        assertTrue(auth.refreshCalls == 1)
        assertEquals(1, launcher.calls)
        assertTrue(consent.remembered.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun handler_loginFailure_doesNotRefresh() = runTest {
        val auth = InjectionAuth(result = LoginResult.Failure("Subscription expired"))
        val consent = InjectionConsent()
        val (h, launcher) = handler(consent = consent, auth = auth, scope = backgroundScope)

        val req = AdbLoginRequest(
            host = "panel.example",
            port = 8080,
            useTls = true,
            displayName = "panel.example",
            username = "u",
            password = "p",
            explicitDisplayName = "",
        )
        runHandler(h, req)

        assertEquals(1, auth.loginCalls.size)
        assertEquals(0, auth.refreshCalls)
        assertEquals(0, launcher.calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun handler_loginFailure_usesPanelMessageWordForWord() = runTest {
        // The operator reading logcat sees the exact text the panel returned —
        // mirror the UI's "show panel's message word-for-word for wrong
        // password" semantics (design spec "Sign in", wrong password).
        val auth = InjectionAuth(result = LoginResult.Failure("Subscription expired"))
        val (h, _) = handler(auth = auth, scope = backgroundScope)

        val req = parseExtras(extras(host = "https://panel.example", username = "u", password = "p"))!!
        runHandler(h, req)

        assertEquals(1, auth.loginCalls.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun handler_explicitDisplayName_overridesHostDerived() = runTest {
        val auth = InjectionAuth(result = LoginResult.Success(null, null))
        val consent = InjectionConsent()
        val (h, _) = handler(consent = consent, auth = auth, scope = backgroundScope)

        val req = AdbLoginRequest(
            host = "panel.example",
            port = 8080,
            useTls = true,
            displayName = "panel.example",
            username = "u",
            password = "p",
            explicitDisplayName = "QA Box",
        )
        runHandler(h, req)

        assertEquals("QA Box", auth.loginCalls.single().displayName)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun handler_passesAllArgsToCoordinator_login() = runTest {
        val auth = InjectionAuth(result = LoginResult.Failure("Wrong username or password."))
        val (h, _) = handler(auth = auth, scope = backgroundScope)

        val req = parseExtras(
            extras(host = "https://panel.example:443", username = "bob", password = "bad"),
        )!!
        runHandler(h, req)

        val call = auth.loginCalls.singleOrNull()!!
        assertEquals("bob", call.username)
        assertEquals("bad", call.password)
        assertTrue(call.useTls)
        assertEquals(443, call.port)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun handler_doesNotRequestCleartextDecisionOnHttps() = runTest {
        val consent = InjectionConsent()
        val (h, _) = handler(consent = consent, scope = backgroundScope)

        val req = parseExtras(extras(host = "https://panel.example", username = "u", password = "p"))!!
        runHandler(h, req)

        assertTrue(consent.decisionsAsked.isEmpty())
        assertTrue(consent.remembered.isEmpty())
    }

    // ------------------------------------------------------------------ fakes

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun handler(
        auth: InjectionAuth = InjectionAuth(result = LoginResult.Success(null, null)),
        consent: InjectionConsent = InjectionConsent(),
        launcher: RecordingLauncher = RecordingLauncher(),
        scope: CoroutineScope,
    ): Pair<AdbLoginHandler, RecordingLauncher> = AdbLoginHandler(
        coordinator = auth,
        cleartextConsent = consent,
        launcher = launcher,
        scope = scope,
    ) to launcher

    /** Helper: handleAsync + advanceUntilIdle + join on the runTest scheduler. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.runHandler(handler: AdbLoginHandler, request: AdbLoginRequest) {
        val job = handler.handleAsync(request)
        // Drain queued tasks on the runTest scheduler so the launched
        // coroutine actually runs, then await its completion.
        advanceUntilIdle()
        job.join()
    }

    private fun extras(
        host: String? = "panel.example",
        username: String? = "u",
        password: String? = "p",
        displayName: String? = null,
    ): Map<String, String?> = mapOf(
        AdbLoginInjection.EXTRA_HOST to host,
        AdbLoginInjection.EXTRA_USERNAME to username,
        AdbLoginInjection.EXTRA_PASSWORD to password,
        AdbLoginInjection.EXTRA_DISPLAY_NAME to displayName,
    )
}

// --------------------------------------------------------------------- fakes

private class InjectionAuth(
    var result: LoginResult,
) : LoginAuthGateway {
    data class Call(
        val host: String,
        val port: Int,
        val useTls: Boolean,
        val username: String,
        val password: String,
        val displayName: String,
    )

    val loginCalls = mutableListOf<Call>()
    var refreshCalls = 0

    override suspend fun login(
        host: String,
        port: Int,
        useTls: Boolean,
        username: String,
        password: String,
        displayName: String,
    ): LoginResult {
        loginCalls += Call(host, port, useTls, username, password, displayName)
        return result
    }

    override suspend fun refreshAll() {
        refreshCalls++
    }
}

private class InjectionConsent(
    initial: Map<String, CleartextDecision> = emptyMap(),
) : CleartextConsentStore {
    val decisionsAsked = mutableListOf<String>()
    val remembered: MutableMap<String, CleartextDecision> = initial.toMutableMap()

    // No withContext(Dispatchers.IO) — the production store hops, but the
    // fake has nothing to wait on, and runTest does not advance the real
    // IO dispatcher, so a no-op body keeps the test ordering deterministic.
    override suspend fun decision(host: String, port: Int): CleartextDecision? {
        decisionsAsked += "$host:$port"
        return remembered["$host:$port"]
    }

    override suspend fun remember(host: String, port: Int, decision: CleartextDecision) {
        remembered["$host:$port"] = decision
    }

    override suspend fun clear() {
        remembered.clear()
    }
}

/**
 * Minimal Launcher stand-in for [AdbLoginHandler] in unit tests. The handler
 * uses an [AdbLoginLauncher] interface precisely so this can record calls
 * without touching `android.content.Intent` (which plain-JVM JUnit cannot
 * stub for free). The receiver's success path is exercised end-to-end on
 * device instead.
 */
private class RecordingLauncher : AdbLoginLauncher {
    var calls = 0
    override fun launchMainFromBackground() {
        calls++
    }

    /**
     * Returns a fresh [android.app.Application] — the on-device ADB
     * injection's success path tries to enqueue a guide refresh, but the
     * existing handler tests run on the JVM without Graph initialized, so
     * the production kick swallows the resulting
     * [IllegalStateException] internally and the test still passes.
     */
    override fun applicationContext(): android.content.Context = android.app.Application()
}
