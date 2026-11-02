package com.rysal.happytv.ui.login

import com.rysal.happytv.data.network.LoginResult
import com.rysal.happytv.security.CleartextConsentStore
import com.rysal.happytv.security.CleartextDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every transition of [LoginViewModel]'s state machine, against plain fakes —
 * no Room, no OkHttp, no panel (the gateway seams in `LoginDependencies.kt`
 * exist exactly for this).
 *
 * Maps 1:1 to the task's sign-in scenarios: first run, saved servers, missing
 * fields, URL formats dialog, cleartext sheet, signing in, wrong password,
 * can't-reach server (including the 15 s watchdog).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val fakeAuth = FakeAuthGateway()
    private val fakeServers = FakeSavedServersSource()
    private val fakeConsent = FakeCleartextConsentStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(timeoutMillis: Long = 15_000L) = LoginViewModel(
        auth = fakeAuth,
        savedServersSource = fakeServers,
        cleartextConsent = fakeConsent,
        loginTimeoutMillis = timeoutMillis,
        io = UnconfinedTestDispatcher(scheduler),
    )

    // ------------------------------------------------------------- first run

    @Test
    fun firstRun_noSavedServers_fieldsEmptyFocusOnUrl() = runTest(scheduler) {
        val vm = viewModel()
        val state = vm.state.value

        assertTrue(state.savedServers.isEmpty())
        assertTrue(state.savedServersLoaded)
        assertEquals("", state.serverUrl)
        assertEquals("", state.username)
        assertEquals("", state.password)
        assertEquals(LoginFocusTarget.ServerUrl, state.focusTarget)
    }

    // -------------------------------------------------------- saved servers

    @Test
    fun savedServers_prefillUrlUsername_focusPassword() = runTest(scheduler) {
        fakeServers.servers += SavedServer(
            id = "profile-1",
            name = "Home",
            hostLabel = "panel.example:8080",
            username = "demo-user",
            lastSignedInLabel = "Last signed in Oct 14",
            inUse = true,
        )
        val vm = viewModel()
        val state = vm.state.value

        assertEquals(listOf("profile-1"), state.savedServers.map { it.id })
        assertEquals("panel.example:8080", state.serverUrl)
        assertEquals("demo-user", state.username)
        assertEquals("", state.password)
        assertEquals("profile-1", state.selectedServerId)
        assertEquals(LoginFocusTarget.Password, state.focusTarget)
    }

    @Test
    fun pickSavedServer_fillsFields_clearsPassword_focusPassword() = runTest(scheduler) {
        fakeServers.servers += SavedServer(
            id = "profile-1", name = "Home", hostLabel = "panel.example:8080",
            username = "demo-user", lastSignedInLabel = null, inUse = true,
        )
        val vm = viewModel()
        vm.onFocusHandled()
        vm.onPasswordChange("stale")

        vm.onPickSavedServer("profile-1")

        val state = vm.state.value
        assertEquals("panel.example:8080", state.serverUrl)
        assertEquals("demo-user", state.username)
        assertEquals("", state.password)
        assertEquals(LoginFocusTarget.Password, state.focusTarget)
    }

    @Test
    fun pickSavedServer_unknownId_changesNothing() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("typed.example:1234")

        vm.onPickSavedServer("does-not-exist")

        assertEquals("typed.example:1234", vm.state.value.serverUrl)
    }

    // ---------------------------------------------------------- missing fields

    @Test
    fun submit_emptyForm_outlinesAllFields_focusFirst() = runTest(scheduler) {
        val vm = viewModel()

        vm.onSubmit()

        val state = vm.state.value
        assertEquals("Enter your server URL.", state.serverUrlError)
        assertEquals("Enter your username.", state.usernameError)
        assertEquals("Enter your password.", state.passwordError)
        assertEquals(LoginFocusTarget.ServerUrl, state.focusTarget)
        // Nothing was sent anywhere.
        assertEquals(0, fakeAuth.loginCalls.size)
    }

    @Test
    fun submit_partiallyEmpty_onlyMissingFieldsErrored() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("panel.example:8080")
        vm.onPasswordChange("pw")

        vm.onSubmit()

        val state = vm.state.value
        assertNull(state.serverUrlError)
        assertEquals("Enter your username.", state.usernameError)
        assertNull(state.passwordError)
        assertEquals(LoginFocusTarget.Username, state.focusTarget)
    }

    @Test
    fun typing_clearsThatFieldsError() = runTest(scheduler) {
        val vm = viewModel()
        vm.onSubmit()
        assertTrue(vm.state.value.serverUrlError != null)

        vm.onServerUrlChange("p")

        assertNull(vm.state.value.serverUrlError)
    }

    @Test
    fun submit_invalidUrl_inlineError_noLoginCall() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("not a url at all")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")

        vm.onSubmit()

        val state = vm.state.value
        assertEquals(LoginViewModel.INVALID_URL_MESSAGE, state.serverUrlError)
        assertEquals(LoginFocusTarget.ServerUrl, state.focusTarget)
        assertEquals(0, fakeAuth.loginCalls.size)
    }

    // ------------------------------------------------------- URL formats dialog

    @Test
    fun urlFormats_pickOption_fillsUrl_closes_returnsFocus() = runTest(scheduler) {
        val vm = viewModel()

        vm.onOpenUrlFormats()
        assertTrue(vm.state.value.overlay is LoginOverlay.UrlFormats)
        assertEquals(LoginFocusTarget.FormatOption, vm.state.value.focusTarget)

        vm.onPickUrlFormat("http://provider.example:8080")

        val state = vm.state.value
        assertEquals("http://provider.example:8080", state.serverUrl)
        assertEquals(LoginOverlay.None, state.overlay)
        assertEquals(LoginFocusTarget.ServerUrl, state.focusTarget)
    }

    @Test
    fun urlFormats_close_returnsFocusToLink() = runTest(scheduler) {
        val vm = viewModel()

        vm.onOpenUrlFormats()
        vm.onCloseUrlFormats()

        assertEquals(LoginOverlay.None, vm.state.value.overlay)
        assertEquals(LoginFocusTarget.UrlFormatsLink, vm.state.value.focusTarget)
    }

    // -------------------------------------------------------------- cleartext

    @Test
    fun submit_httpUrl_neverAnswered_raisesSheet_neverSilentlyLogsIn() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("http://panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")

        vm.onSubmit()

        val state = vm.state.value
        val sheet = state.overlay as? LoginOverlay.CleartextSheet
        assertEquals("panel.example:8080", sheet?.hostLabel)
        assertEquals(0, fakeAuth.loginCalls.size)
    }

    /**
     * F-66: the sheet must hand the D-pad its first stop. Without the ask the
     * sheet renders three buttons that nothing owns focus for and a plain-HTTP
     * provider cannot be signed into from the TV (QA dump: every node
     * `focused="false"`). The handoff names "Use HTTPS" as that stop.
     */
    @Test
    fun sheet_opening_asksFocusForUseHttps() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("http://panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(LoginFocusTarget.SheetUseHttps, vm.state.value.focusTarget)
    }

    /** The ask is one-shot: the screen clears it via `onFocusHandled`. */
    @Test
    fun sheet_opening_focusAskIsConsumedOnce() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("http://panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        vm.onSubmit()
        advanceUntilIdle()
        assertEquals(LoginFocusTarget.SheetUseHttps, vm.state.value.focusTarget)

        vm.onFocusHandled()

        assertEquals(null, vm.state.value.focusTarget)
    }

    @Test
    fun sheet_useHttps_rewritesUrl_recordsConsent_signsInTls() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("http://panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        vm.onSubmit()

        fakeAuth.result = LoginResult.Success(null, null)
        vm.onSheetUseHttps()
        advanceUntilIdle()

        assertEquals("https://panel.example:8080", vm.state.value.serverUrl)
        assertEquals(CleartextDecision.UseHttps, fakeConsent.decisions["panel.example:8080"])
        assertEquals(true, fakeAuth.loginCalls.single().useTls)
        assertTrue(vm.state.value.isAuthenticated)
        assertEquals(1, fakeAuth.refreshCalls)
    }

    @Test
    fun sheet_continueAnyway_recordsConsent_signsInHttp() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("http://panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        vm.onSubmit()

        fakeAuth.result = LoginResult.Success(null, null)
        vm.onSheetContinueAnyway()
        advanceUntilIdle()

        assertEquals(CleartextDecision.ContinueOverHttp, fakeConsent.decisions["panel.example:8080"])
        assertEquals(false, fakeAuth.loginCalls.single().useTls)
        assertTrue(vm.state.value.isAuthenticated)
    }

    @Test
    fun sheet_cancel_returnsToSignInButton_noLoginCall() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("http://panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        vm.onSubmit()

        vm.onSheetCancel()

        assertEquals(LoginOverlay.None, vm.state.value.overlay)
        assertEquals(LoginFocusTarget.Submit, vm.state.value.focusTarget)
        assertEquals(0, fakeAuth.loginCalls.size)
    }

    @Test
    fun httpUrl_consentedOnOtherPort_asksAgain() = runTest(scheduler) {
        fakeConsent.decisions["panel.example:8089"] = CleartextDecision.ContinueOverHttp
        val vm = viewModel()
        vm.onServerUrlChange("http://panel.example:8090")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")

        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(LoginOverlay.CleartextSheet("panel.example:8090"), vm.state.value.overlay)
        assertEquals(0, fakeAuth.loginCalls.size)
    }

    @Test
    fun httpUrl_alreadyConsented_skipsSheet() = runTest(scheduler) {
        fakeConsent.decisions["panel.example:8080"] = CleartextDecision.ContinueOverHttp
        val vm = viewModel()
        vm.onServerUrlChange("http://panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")

        fakeAuth.result = LoginResult.Success(null, null)
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(LoginOverlay.None, vm.state.value.overlay)
        assertEquals(1, fakeAuth.loginCalls.size)
        assertTrue(vm.state.value.isAuthenticated)
    }

    // ------------------------------------------------------------ signing in

    @Test
    fun submit_success_authenticatesAndKicksRefresh() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        fakeAuth.result = LoginResult.Success(null, null)

        vm.onSubmit()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.phase is LoginPhase.Authenticated)
        assertTrue(state.isAuthenticated)
        assertEquals(1, fakeAuth.refreshCalls)
        val call = fakeAuth.loginCalls.single()
        assertEquals("panel.example", call.host)
        assertEquals(8080, call.port)
        assertEquals("u", call.username)
        assertEquals("p", call.password)
    }

    @Test
    fun submit_whileSigningIn_isIgnored() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        fakeAuth.blockOn = kotlinx.coroutines.CompletableDeferred()

        vm.onSubmit()
        vm.onSubmit()

        assertEquals(1, fakeAuth.loginCalls.size)
        fakeAuth.blockOn!!.complete(Unit)
        advanceUntilIdle()
    }

    // --------------------------------------------------------- wrong password

    @Test
    fun authZero_serverMessageShownWordForWord_focusPassword() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("bad")
        fakeAuth.result = LoginResult.Failure("Subscription expired")

        vm.onSubmit()
        advanceUntilIdle()

        val phase = vm.state.value.phase as LoginPhase.Error
        assertEquals(LoginErrorKind.WrongCredentials, phase.kind)
        assertEquals("Subscription expired", phase.message)
        assertEquals(LoginFocusTarget.Password, vm.state.value.focusTarget)
    }

    // ------------------------------------------------------ can't reach server

    @Test
    fun unreachableFailure_showsCantReach_retryFocused() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        fakeAuth.result = LoginResult.Failure("Couldn't reach panel.example:8080")

        vm.onSubmit()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.isUnreachable)
        assertEquals(LoginViewModel.CANT_REACH_MESSAGE, state.statusError)
        assertEquals(LoginFocusTarget.Retry, state.focusTarget)
    }

    @Test
    fun noAnswerWithinWatchdog_showsCantReach() = runTest(scheduler) {
        val vm = viewModel(timeoutMillis = 15_000L)
        vm.onServerUrlChange("panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        fakeAuth.result = LoginResult.Success(null, null)
        fakeAuth.delayMillis = 60_000L // panel goes quiet well past 15 s

        vm.onSubmit()
        advanceUntilIdle()

        assertTrue(vm.state.value.isUnreachable)
    }

    @Test
    fun changeServer_fromUnreachable_idleFocusUrl() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        fakeAuth.result = LoginResult.Failure("Couldn't reach panel.example:8080")
        vm.onSubmit()
        advanceUntilIdle()

        vm.onChangeServer()

        val state = vm.state.value
        assertTrue(state.phase is LoginPhase.Idle)
        assertFalse(state.isUnreachable)
        assertEquals(LoginFocusTarget.ServerUrl, state.focusTarget)
    }

    @Test
    fun retry_retriesTheSameLogin() = runTest(scheduler) {
        val vm = viewModel()
        vm.onServerUrlChange("panel.example:8080")
        vm.onUsernameChange("u")
        vm.onPasswordChange("p")
        fakeAuth.result = LoginResult.Failure("Couldn't reach panel.example:8080")
        vm.onSubmit()
        advanceUntilIdle()

        fakeAuth.result = LoginResult.Success(null, null)
        vm.onRetry()
        advanceUntilIdle()

        assertEquals(2, fakeAuth.loginCalls.size)
        assertTrue(vm.state.value.isAuthenticated)
    }

    // ------------------------------------------------------------ status slot

    @Test
    fun m3u_showsInfoLine_notAnError() = runTest(scheduler) {
        val vm = viewModel()

        vm.onM3uPressed()

        assertEquals(LoginViewModel.M3U_INFO_LINE, vm.state.value.infoLine)
        assertNull(vm.state.value.statusError)
    }

    @Test
    fun rememberMe_toggles() = runTest(scheduler) {
        val vm = viewModel()
        assertTrue(vm.state.value.rememberMe)

        vm.onRememberMeToggle()
        assertFalse(vm.state.value.rememberMe)
        vm.onRememberMeToggle()
        assertTrue(vm.state.value.rememberMe)
    }

    @Test
    fun focusTarget_isClearedOnceHandled() = runTest(scheduler) {
        val vm = viewModel()
        vm.onOpenUrlFormats()

        vm.onFocusHandled()

        assertNull(vm.state.value.focusTarget)
    }
}

// --------------------------------------------------------------------- fakes

private data class LoginCall(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String,
    val password: String,
    val displayName: String,
)

private class FakeAuthGateway : LoginAuthGateway {
    var result: LoginResult = LoginResult.Failure("unset fake result")
    var delayMillis: Long = 0L
    var blockOn: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val loginCalls = mutableListOf<LoginCall>()
    var refreshCalls = 0

    override suspend fun login(
        host: String,
        port: Int,
        useTls: Boolean,
        username: String,
        password: String,
        displayName: String,
    ): LoginResult {
        loginCalls += LoginCall(host, port, useTls, username, password, displayName)
        if (delayMillis > 0) delay(delayMillis)
        blockOn?.await()
        return result
    }

    override suspend fun refreshAll() {
        refreshCalls++
    }
}

private class FakeSavedServersSource : SavedServersSource {
    val servers = mutableListOf<SavedServer>()
    override suspend fun savedServers(): List<SavedServer> = servers.toList()
}

private class FakeCleartextConsentStore : CleartextConsentStore {
    val decisions = mutableMapOf<String, CleartextDecision>()
    override suspend fun decision(host: String, port: Int): CleartextDecision? = decisions["${host.trim().lowercase()}:$port"]
    override suspend fun remember(host: String, port: Int, decision: CleartextDecision) {
        decisions["${host.trim().lowercase()}:$port"] = decision
    }

    override suspend fun clear() = decisions.clear()
}
