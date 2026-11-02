package com.rysal.happytv.ui.settings

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.LiveTvOpenOn
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.StreamType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Settings' write-through behaviour (design spec §"Settings"): every change saves
 * immediately, the two destructive rows only act after their confirmation,
 * and "Reset to defaults" restores exactly the settings on the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var settings: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    private val actions = mutableListOf<String>()
    private val scheduleChanges = mutableListOf<Int>()
    private val effects = mutableListOf<SettingsEffect>()
    private val unhides = mutableListOf<String>()
    private var dataStoreFiles = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        // DataStore's own writes run on its scope: hand it the test dispatcher
        // so "saves right away" is observable without sleeping.
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(mainDispatcher)) {
            File(context.cacheDir, "settings-vm-${dataStoreFiles++}.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
        viewModel = buildViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(
        startSection: SettingsSection = SettingsSection.ACCOUNT,
        hiddenCategories: List<Category> = emptyList(),
        // F-52: the Guide pane's URL prefill source (null = nothing derivable).
        deriveXmlTvUrl: suspend () -> String? = { null },
    ): SettingsViewModel {
        val vm = SettingsViewModel(
            settings = settings,
            loadAccount = {
                SettingsAccount(
                    serverName = "Home",
                    host = "provider.example",
                    port = 8080,
                    username = "demo-user",
                    signedInAtUtcMillis = 0L,
                )
            },
            deriveXmlTvUrl = deriveXmlTvUrl,
            onRefreshEverything = { actions += "refresh-everything" },
            onSignOut = { actions += "sign-out" },
            onGuideScheduleChanged = { hours -> scheduleChanges += hours },
            // Unconfined, so a confirmed dialog runs to its effect inline.
            appScope = CoroutineScope(mainDispatcher),
            startSection = startSection,
            // F-44: the hidden-category management list, fed like the DAO feed.
            hiddenCategories = kotlinx.coroutines.flow.MutableStateFlow(hiddenCategories),
            onUnhideCategory = { id, type -> unhides += "$type:$id" },
            onBackupSwitchChanged = { actions += "backup-changed" },
        )
        CoroutineScope(mainDispatcher).launch { vm.effects.collect { effects += it } }
        return vm
    }

    // -------------------------------------------------------------- defaults

    @Test
    fun `defaults match the handoff table`() = runTest {
        val state = settings.settings.first()
        assertTrue(state.refreshOnStart)
        assertNull(state.epgUrl)
        assertEquals(SettingsRepository.DEFAULT_EPG_REFRESH_HOURS, state.epgRefreshHours)
        assertEquals(SettingsRepository.FORMAT_M3U8, state.preferredStreamFormat)
        assertFalse(state.replayFromStart)
        assertEquals(SettingsRepository.ASPECT_AUTO, state.aspectRatio)
        // Spec #7 section 4: Favorites by default, categories folded away, nothing remembered.
        assertEquals(LiveTvOpenOn.FAVORITES, state.liveTvOpenOn)
        assertFalse(state.keepCategoriesOpen)
        assertNull(state.lastLiveCategory)
        // "Update the guide every" is 12 hours in the design spec, not 6.
        assertEquals(12, SettingsRepository.DEFAULT_EPG_REFRESH_HOURS)
    }

    @Test
    fun `the screen opens on the section the caller asked for`() = runTest {
        assertEquals(SettingsSection.GUIDE, SettingsSection.fromExtra("guide"))
        assertEquals(SettingsSection.PLAYER, SettingsSection.fromExtra("player"))
        assertEquals(SettingsSection.ACCOUNT, SettingsSection.fromExtra(null))
        assertEquals(SettingsSection.ACCOUNT, SettingsSection.fromExtra("nonsense"))

        val guideFirst = buildViewModel(SettingsSection.GUIDE)
        assertEquals(SettingsSection.GUIDE, guideFirst.ui.value.section)
    }

    @Test
    fun `hidden channel rows carry name and category, and gone channels stay`() {
        val rows = listOf(
            HiddenChannelRow("1", "Alpha One", "News"),
            HiddenChannelRow("2", "Bravo Two", null),
            HiddenChannelRow("3", null, null),
        )
        assertEquals("Show Alpha One", rows[0].label)
        assertEquals("News", rows[0].caption)
        assertEquals("No category", rows[1].caption)
        assertEquals("Show removed channel", rows[2].label)
        assertEquals("No longer offered by your provider", rows[2].caption)
    }

    @Test
    fun `hidden items are fed from the database flows and unhide a channel by id`() = runTest {
        val channelUnhides = mutableListOf<String>()
        val vm = SettingsViewModel(
            settings = SettingsRepository(
                androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) {
                    java.io.File.createTempFile("hidden-vm", ".preferences_pb").also { it.delete() }
                },
            ),
            loadAccount = { null },
            onRefreshEverything = {},
            onSignOut = {},
            onGuideScheduleChanged = {},
            appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            hiddenCategories = kotlinx.coroutines.flow.flowOf(
                listOf(
                    Category(id = "9", type = StreamType.SERIES, name = "zeta", isHidden = true),
                    Category(id = "1", type = StreamType.LIVE, name = "Alpha", isHidden = true),
                ),
            ),
            hiddenChannels = kotlinx.coroutines.flow.flowOf(
                listOf(com.rysal.happytv.data.dao.HiddenChannelListing("5", "Five", "News")),
            ),
            onUnhideChannel = { channelUnhides += it },
        )
        kotlinx.coroutines.yield()
        // Sorted by name, case-insensitive, whatever the catalog.
        assertEquals(listOf("Alpha", "zeta"), vm.ui.value.hiddenCategories.map { it.name })
        assertEquals(listOf("Five"), vm.ui.value.hiddenChannels.map { it.name })
        vm.unhideChannel("5")
        kotlinx.coroutines.yield()
        assertEquals(listOf("5"), channelUnhides)
    }

    @Test
    fun `hidden categories are listed per catalog and unhide writes through`() = runTest {
        val vm = buildViewModel(
            hiddenCategories = listOf(
                Category(id = "1", type = StreamType.LIVE, name = "News", isHidden = true),
                Category(id = "7", type = StreamType.VOD, name = "Docs", isHidden = true),
            ),
        )
        val rows = vm.ui.value.hiddenCategories
        // #123: sorted by name, whatever the catalog.
        assertEquals(listOf("Docs", "News"), rows.map { it.name })
        assertEquals(listOf(StreamType.VOD, StreamType.LIVE), rows.map { it.type })
        // The caption source: "Hidden from Live TV" / "Hidden from Movies".
        assertEquals("Movies", rows[0].catalogLabel)
        assertEquals("Live TV", rows[1].catalogLabel)

        vm.unhideCategory("1", StreamType.LIVE)
        kotlinx.coroutines.yield()
        assertEquals(listOf("LIVE:1"), unhides)
        // Nothing hidden → the pane's "None" info row, not a phantom list.
        assertTrue(buildViewModel().ui.value.hiddenCategories.isEmpty())
    }

    @Test
    fun `the account pane reads the stored server username and sign-in time`() = runTest {
        val state = viewModel.ui.value
        // The account lookup answers off the main dispatcher; give it a tick.
        kotlinx.coroutines.yield()
        val resolved = viewModel.ui.first { it.accountResolved }
        assertTrue(resolved.signedIn)
        assertEquals("Home · provider.example:8080", resolved.account?.serverLabel)
        assertEquals("demo-user", resolved.account?.username)
        assertEquals(SettingsSection.ACCOUNT, state.section)
    }

    // --------------------------------------------------------- write through

    @Test
    fun `every change saves right away - there is no save button`() = runTest {
        viewModel.setRefreshOnStart(false)
        assertFalse(settings.settings.first().refreshOnStart)

        viewModel.setStreamFormat(StreamFormatOption.TS)
        assertEquals(SettingsRepository.FORMAT_TS, settings.settings.first().preferredStreamFormat)

        viewModel.setReplayFromStart(true)
        assertTrue(settings.settings.first().replayFromStart)

        viewModel.setAspectRatio(AspectRatioOption.CLASSIC)
        assertEquals(SettingsRepository.ASPECT_4_3, settings.settings.first().aspectRatio)

        viewModel.setGuideRefreshHours(GuideRefreshOption.HOURS_24)
        assertEquals(24, settings.settings.first().epgRefreshHours)
        // The guide's worker is rescheduled with the new period.
        assertEquals(listOf(24), scheduleChanges)
    }

    @Test
    fun `the Live TV settings save right away and survive a re-read`() = runTest {
        viewModel.setLiveTvOpenOn(LiveTvOpenOn.CHOOSE)
        assertEquals(LiveTvOpenOn.CHOOSE, settings.settings.first().liveTvOpenOn)
        viewModel.setLiveTvOpenOn(LiveTvOpenOn.LAST)
        assertEquals(LiveTvOpenOn.LAST, settings.settings.first().liveTvOpenOn)

        viewModel.setKeepCategoriesOpen(true)
        assertTrue(settings.settings.first().keepCategoriesOpen)
        assertTrue(viewModel.ui.value.settings.keepCategoriesOpen)

        settings.setLastLiveCategory("news")
        assertEquals("news", settings.settings.first().lastLiveCategory)
        settings.setLastLiveCategory(null)
        assertNull(settings.settings.first().lastLiveCategory)
    }

    @Test
    fun `Hidden items sits after Guide`() {
        assertEquals(SettingsSection.HIDDEN, SettingsSection.fromExtra("hidden"))
        assertEquals("Hidden items", SettingsSection.HIDDEN.label)
    }

    @Test
    fun `Live TV is its own section between Refresh and Guide`() {
        val order = SettingsSection.entries.map { it.label }
        assertEquals(
            listOf("Account", "Refresh", "Live TV", "Guide", "Hidden items", "Player", "About"),
            order,
        )
        assertEquals(SettingsSection.LIVE_TV, SettingsSection.fromExtra("livetv"))
        // An unknown stored value reads as the default, Favorites.
        assertEquals(LiveTvOpenOn.FAVORITES, LiveTvOpenOn.fromStored("nonsense"))
        assertEquals(LiveTvOpenOn.FAVORITES, LiveTvOpenOn.fromStored(null))
    }

    @Test
    fun `choosing the XMLTV link reveals the field and the link only saves when typed`() = runTest {
        assertFalse(viewModel.ui.value.xmltvLinkShown)

        viewModel.chooseGuideSource(GuideSource.XMLTV)
        assertTrue(viewModel.ui.value.xmltvLinkShown)
        // Nothing typed yet, so the stored source is still "from provider".
        assertNull(settings.settings.first().epgUrl)

        viewModel.setXmltvUrl("  http://provider.example:8080/xmltv.php  ")
        // #18: the plain store holds only the marker; the link is encrypted.
        assertEquals(SettingsRepository.EPG_OVERRIDE, settings.settings.first().epgUrl)
        assertEquals("http://provider.example:8080/xmltv.php", settings.epgOverrideUrl())
    }

    @Test
    fun `going back to From provider forgets the link and hides the field`() = runTest {
        viewModel.chooseGuideSource(GuideSource.XMLTV)
        viewModel.setXmltvUrl("http://provider.example/xmltv.php")

        viewModel.chooseGuideSource(GuideSource.PROVIDER)
        // F-52: "From provider" stores the explicit `""` sentinel — null
        // now means "auto, derive from the credentials" — so the worker's
        // derivation cannot undo this choice on the next refresh. The typed
        // link is still forgotten either way.
        assertEquals("", settings.settings.first().epgUrl)
        assertFalse(viewModel.ui.value.xmltvLinkShown)
    }

    @Test
    fun `a leading zero in the link field is stored verbatim`() = runTest {
        // The acceptance case: "0" is a value, never "empty".
        viewModel.chooseGuideSource(GuideSource.XMLTV)
        viewModel.setXmltvUrl("0")

        assertEquals("0", settings.epgOverrideUrl())
        assertTrue(viewModel.ui.value.xmltvLinkShown)

        // And a field the user cleared falls back to the provider guide.
        viewModel.setXmltvUrl("   ")
        assertNull(settings.settings.first().epgUrl)
    }

    @Test
    fun `a link saved elsewhere shows its field when the pane opens`() = runTest {
        settings.setEpgUrl("http://provider.example/xmltv.php")
        val fresh = buildViewModel(SettingsSection.GUIDE)
        assertEquals(GuideSource.XMLTV, fresh.ui.first { it.accountResolved }.guideSource)
        assertTrue(fresh.ui.value.xmltvLinkShown)
    }

    // ------------------------------------------------------- F-52 prefill

    private val derivedUrl = "http://panel.example:8080/xmltv.php?username=demo&password=s3cret"

    @Test
    fun `automatic guide reads as From provider with the link field hidden`() = runTest {
        val vm = buildViewModel(SettingsSection.GUIDE, deriveXmlTvUrl = { derivedUrl })
        val state = vm.ui.first { it.accountResolved }
        // #18: a non-secret label, never the URL with the password.
        assertTrue(state.autoGuideAvailable)
        assertEquals(SettingsViewModel.AUTO_GUIDE_LABEL, state.effectiveXmlTvUrl)
        assertFalse(state.toString().contains("s3cret"))
        // #94: nothing typed, so the control and the field agree: From provider, no field.
        assertFalse(state.hasTypedLink)
        assertFalse(state.xmltvLinkShown)
        assertEquals(GuideSource.PROVIDER, state.guideSource)
    }

    @Test
    fun `the stored auto marker also reads as From provider`() = runTest {
        settings.setEpgUrl(SettingsRepository.EPG_AUTO)
        val vm = buildViewModel(SettingsSection.GUIDE, deriveXmlTvUrl = { derivedUrl })
        val state = vm.ui.first { it.accountResolved }
        assertEquals(GuideSource.PROVIDER, state.guideSource)
        assertFalse(state.xmltvLinkShown)
    }

    @Test
    fun `choosing XMLTV link from the automatic guide moves the highlight and shows the field`() = runTest {
        val vm = buildViewModel(SettingsSection.GUIDE, deriveXmlTvUrl = { derivedUrl })
        vm.ui.first { it.accountResolved }
        vm.chooseGuideSource(GuideSource.XMLTV)
        kotlinx.coroutines.yield()
        assertEquals(GuideSource.XMLTV, vm.ui.value.guideSource)
        assertTrue(vm.ui.value.xmltvLinkShown)
        assertFalse(vm.ui.value.hasTypedLink)
    }

    @Test
    fun `a typed url overrides the prefill and clearing it falls back to it`() = runTest {
        val vm = buildViewModel(SettingsSection.GUIDE, deriveXmlTvUrl = { derivedUrl })
        vm.ui.first { it.accountResolved }

        vm.setXmltvUrl("http://custom.example/guide.xml")
        kotlinx.coroutines.yield()
        assertEquals("http://custom.example/guide.xml", vm.ui.value.effectiveXmlTvUrl)
        assertEquals(SettingsRepository.EPG_OVERRIDE, settings.settings.first().epgUrl)
        assertEquals("http://custom.example/guide.xml", settings.epgOverrideUrl())

        // Clearing the field drops the override — the derived URL returns.
        vm.setXmltvUrl("   ")
        kotlinx.coroutines.yield()
        assertNull(settings.settings.first().epgUrl)
        assertNull(settings.epgOverrideUrl())
        assertEquals(SettingsViewModel.AUTO_GUIDE_LABEL, vm.ui.value.effectiveXmlTvUrl)
        assertTrue(vm.ui.value.xmltvLinkShown)
    }

    @Test
    fun `From provider hides the derived url and keeps it hidden until XMLTV is chosen`() = runTest {
        val vm = buildViewModel(SettingsSection.GUIDE, deriveXmlTvUrl = { derivedUrl })
        vm.ui.first { it.accountResolved }

        vm.chooseGuideSource(GuideSource.PROVIDER)
        kotlinx.coroutines.yield()
        assertEquals("", settings.settings.first().epgUrl)
        assertEquals("", vm.ui.value.effectiveXmlTvUrl)
        assertFalse(vm.ui.value.xmltvLinkShown)

        // Re-entering the pane must not resurrect the prefill: "" is the
        // explicit sentinel that suppresses derivation.
        val reopened = buildViewModel(SettingsSection.GUIDE, deriveXmlTvUrl = { derivedUrl })
        val state = reopened.ui.first { it.accountResolved }
        assertEquals("", state.effectiveXmlTvUrl)
        assertFalse(state.xmltvLinkShown)

        // Choosing "XMLTV link" again returns to auto — the prefill is back.
        reopened.chooseGuideSource(GuideSource.XMLTV)
        kotlinx.coroutines.yield()
        assertNull(settings.settings.first().epgUrl)
        assertEquals(GuideSource.XMLTV, reopened.ui.value.guideSource)
        assertTrue(reopened.ui.value.xmltvLinkShown)
    }

    // ------------------------------------------------------------- #18

    @Test
    fun `a typed link with credentials is shown redacted and stored only encrypted`() = runTest {
        val vm = buildViewModel(SettingsSection.GUIDE)
        vm.ui.first { it.accountResolved }
        vm.setXmltvUrl("http://custom.example/xmltv.php?username=bob&password=hunter2")
        kotlinx.coroutines.yield()

        val shown = vm.ui.value.effectiveXmlTvUrl
        assertFalse(shown, shown.contains("hunter2"))
        assertFalse(shown, shown.contains("bob"))
        assertTrue(shown.startsWith("http://custom.example/xmltv.php"))
        val stored = settings.settings.first()
        assertEquals(SettingsRepository.EPG_OVERRIDE, stored.epgUrl)
        assertFalse(stored.toString().contains("hunter2"))
        assertFalse(vm.ui.value.toString().contains("hunter2"))
    }

    // ------------------------------------------------- Guide | List (#15)

    @Test
    fun `the guide is the default Live TV view and the toggle is remembered`() = runTest {
        assertEquals(com.rysal.happytv.data.LiveTvView.GUIDE, settings.settings.first().liveTvView)

        settings.setLiveTvView(com.rysal.happytv.data.LiveTvView.LIST)
        assertEquals(com.rysal.happytv.data.LiveTvView.LIST, settings.settings.first().liveTvView)

        settings.setLiveTvView(com.rysal.happytv.data.LiveTvView.GUIDE)
        assertEquals(com.rysal.happytv.data.LiveTvView.GUIDE, settings.settings.first().liveTvView)
    }

    @Test
    fun `an unknown stored view falls back to the guide`() {
        assertEquals(com.rysal.happytv.data.LiveTvView.GUIDE, com.rysal.happytv.data.LiveTvView.fromStored(null))
        assertEquals(com.rysal.happytv.data.LiveTvView.GUIDE, com.rysal.happytv.data.LiveTvView.fromStored("carousel"))
        assertEquals(com.rysal.happytv.data.LiveTvView.LIST, com.rysal.happytv.data.LiveTvView.fromStored("list"))
    }

    // ----------------------------------------------------------------- reset

    @Test
    fun `reset to defaults restores the on-screen settings and keeps the account`() = runTest {
        viewModel.setRefreshOnStart(false)
        viewModel.setStreamFormat(StreamFormatOption.TS)
        viewModel.setReplayFromStart(true)
        viewModel.setAspectRatio(AspectRatioOption.CLASSIC)
        viewModel.setGuideRefreshHours(GuideRefreshOption.HOURS_6)
        viewModel.setLiveTvOpenOn(LiveTvOpenOn.LAST)
        viewModel.setKeepCategoriesOpen(true)
        settings.setLastLiveCategory("news")
        settings.setLiveTvView(com.rysal.happytv.data.LiveTvView.LIST)
        viewModel.chooseGuideSource(GuideSource.XMLTV)
        viewModel.setXmltvUrl("http://provider.example/xmltv.php")

        settings.setUserInfo(byteArrayOf(1, 2, 3))
        settings.setLastLiveRefreshUtcMillis(1234L)

        viewModel.resetToDefaults()
        viewModel.confirmDialog()

        val state = settings.settings.first()
        assertTrue(state.refreshOnStart)
        assertNull(state.epgUrl)
        assertEquals(12, state.epgRefreshHours)
        assertEquals(SettingsRepository.FORMAT_M3U8, state.preferredStreamFormat)
        assertFalse(state.replayFromStart)
        assertEquals(SettingsRepository.ASPECT_AUTO, state.aspectRatio)
        assertEquals(LiveTvOpenOn.FAVORITES, state.liveTvOpenOn)
        assertFalse(state.keepCategoriesOpen)
        // Not on the screen, so a reset leaves them alone.
        assertEquals("news", state.lastLiveCategory)
        assertEquals(com.rysal.happytv.data.LiveTvView.LIST, state.liveTvView)
        assertNotNull(state.userInfo)
        assertEquals(1234L, state.lastLiveRefreshUtcMillis)
    }

    // ------------------------------------------- #21 unencrypted sign-in override

    @Test
    fun `the unencrypted sign-in override is off by default`() = runTest {
        assertFalse(settings.settings.first().allowSignInOverUnencrypted)
    }

    @Test
    fun `turning the override on asks first and only the confirmation saves it`() = runTest {
        viewModel.setAllowSignInOverUnencrypted(true)

        assertEquals(SettingsDialog.ALLOW_UNENCRYPTED_SIGN_IN, viewModel.ui.value.dialog)
        assertFalse("not saved until confirmed", settings.settings.first().allowSignInOverUnencrypted)

        viewModel.dismissDialog()
        assertFalse("Cancel leaves it off", settings.settings.first().allowSignInOverUnencrypted)

        viewModel.setAllowSignInOverUnencrypted(true)
        viewModel.confirmDialog()
        assertTrue(settings.settings.first().allowSignInOverUnencrypted)
        assertNull(viewModel.ui.value.dialog)
    }

    @Test
    fun `turning the override off saves at once without a dialog`() = runTest {
        settings.setAllowSignInOverUnencrypted(true)

        viewModel.setAllowSignInOverUnencrypted(false)

        assertNull(viewModel.ui.value.dialog)
        assertFalse(settings.settings.first().allowSignInOverUnencrypted)
    }

    @Test
    fun `the override survives sign out, sign in and refresh everything`() = runTest {
        settings.setAllowSignInOverUnencrypted(true)

        // Switch account: confirmed dialog -> sign-out work runs -> sign-in screen.
        viewModel.switchAccount()
        viewModel.confirmDialog()
        assertTrue(actions.contains("sign-out"))
        // What the real sign-out does to settings (RefreshCoordinator.signOut): drop the cached user_info.
        settings.setUserInfo(null)
        assertTrue("after sign-out", settings.settings.first().allowSignInOverUnencrypted)

        // The next sign-in: a new Settings screen over the same device-level store still shows it on.
        val afterSignIn = buildViewModel(SettingsSection.PLAYER)
        assertTrue("after sign-in", afterSignIn.ui.value.settings.allowSignInOverUnencrypted)

        afterSignIn.refreshEverything()
        afterSignIn.confirmDialog()
        assertTrue(actions.contains("refresh-everything"))
        assertTrue("after refresh everything", settings.settings.first().allowSignInOverUnencrypted)
    }

    // ------------------------------------------------ #19 back up favorites and history

    @Test
    fun `the backup switch is on by default, saves at once and asks Android for a new backup`() = runTest {
        assertTrue(settings.settings.first().backupHousehold)

        viewModel.setBackupHousehold(false)

        assertFalse(settings.settings.first().backupHousehold)
        assertTrue("dataChanged is requested", actions.contains("backup-changed"))
        assertNull("no confirmation dialog", viewModel.ui.value.dialog)

        viewModel.setBackupHousehold(true)
        assertTrue(settings.settings.first().backupHousehold)
    }

    @Test
    fun `the backup switch survives sign out, sign in and refresh everything`() = runTest {
        settings.setBackupHousehold(false)

        viewModel.switchAccount()
        viewModel.confirmDialog()
        settings.setUserInfo(null)
        assertFalse("after sign-out", settings.settings.first().backupHousehold)

        val afterSignIn = buildViewModel(SettingsSection.ACCOUNT)
        assertFalse("after sign-in", afterSignIn.ui.value.settings.backupHousehold)
        afterSignIn.refreshEverything()
        afterSignIn.confirmDialog()
        assertFalse("after refresh everything", settings.settings.first().backupHousehold)
    }

    @Test
    fun `the backup copy says sign-in details are never backed up, in sentence case`() {
        assertEquals("Back up favorites and history", BackupCopy.LABEL)
        assertEquals(
            "Saved with your Google account backup. Sign-in details are never backed up.",
            BackupCopy.CAPTION_ON,
        )
        listOf(BackupCopy.LABEL, BackupCopy.CAPTION_ON, BackupCopy.CAPTION_OFF).forEach {
            assertFalse(it, it.contains('!'))
        }
        assertTrue(BackupCopy.CAPTION_OFF.contains("Sign-in details are never backed up."))
    }

    @Test
    fun `reset to defaults turns the override back off`() = runTest {
        settings.setAllowSignInOverUnencrypted(true)

        viewModel.resetToDefaults()
        viewModel.confirmDialog()

        assertFalse(settings.settings.first().allowSignInOverUnencrypted)
    }

    @Test
    fun `the override copy is sentence case with no exclamation point`() {
        listOf(
            UnencryptedSignInCopy.LABEL,
            UnencryptedSignInCopy.CAPTION_OFF,
            UnencryptedSignInCopy.CAPTION_ON,
            UnencryptedSignInCopy.TITLE,
            UnencryptedSignInCopy.BODY,
            UnencryptedSignInCopy.CONFIRM,
        ).forEach { assertFalse(it, it.contains("!")) }
        assertEquals("Allow sign-in details over unencrypted connections", UnencryptedSignInCopy.LABEL)
    }

    // --------------------------------------------------------- confirmations

    @Test
    fun `refresh everything dialog says what is kept`() {
        val body = RefreshEverythingCopy.BODY
        assertTrue(body.contains("favorites"))
        assertTrue(body.contains("recently watched"))
        assertTrue(body.contains("hidden channels"))
        assertTrue(body.contains("stay"))
        assertFalse(body.contains("clears"))
        assertFalse(body.contains("!"))
        assertEquals("Refresh everything", RefreshEverythingCopy.CONFIRM)
        assertFalse(RefreshEverythingCopy.ROW_CAPTION.contains("!"))
    }

    @Test
    fun `refresh everything only acts after its confirmation`() = runTest {
        viewModel.refreshEverything()
        assertEquals(SettingsDialog.REFRESH_EVERYTHING, viewModel.ui.value.dialog)
        assertTrue(actions.isEmpty())

        viewModel.confirmDialog()
        assertNull(viewModel.ui.value.dialog)
        assertEquals(listOf("refresh-everything"), actions)
        assertEquals(listOf<SettingsEffect>(SettingsEffect.GoToDashboard), effects)
    }

    @Test
    fun `sign out only acts after its confirmation, and cancel does nothing`() = runTest {
        viewModel.switchAccount()
        assertEquals(SettingsDialog.SIGN_OUT, viewModel.ui.value.dialog)

        viewModel.dismissDialog()
        assertNull(viewModel.ui.value.dialog)
        assertTrue(actions.isEmpty())
        assertTrue(effects.isEmpty())

        viewModel.switchAccount()
        viewModel.confirmDialog()
        assertEquals(listOf("sign-out"), actions)
        assertEquals(listOf<SettingsEffect>(SettingsEffect.GoToSignIn), effects)
    }

    @Test
    fun `sign out title names the signed-in server and the body says what is wiped`() = runTest {
        assertEquals("Sign out of Lab?", SignOutCopy.title("Lab"))
        assertEquals("Sign out of Lab?", SignOutCopy.title("  Lab "))
        assertEquals("Sign out?", SignOutCopy.title(null))
        assertEquals("Sign out?", SignOutCopy.title("  "))
        listOf("favorites", "recently watched", "hidden items").forEach {
            assertTrue(it, SignOutCopy.BODY.contains(it))
        }
        listOf(SignOutCopy.BODY, SignOutCopy.PROGRESS_TITLE, SignOutCopy.PROGRESS_BODY).forEach {
            assertFalse(it, it.contains("!"))
        }
        // The dialog gets its name from the loaded account, not from a constant.
        assertEquals("Home", viewModel.ui.first { it.accountResolved }.account?.serverName)
    }

    @Test
    fun `sign out shows progress until the work is done, then opens sign in`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = SettingsViewModel(
            settings = settings,
            loadAccount = { null },
            onRefreshEverything = {},
            onSignOut = { gate.await(); actions += "sign-out" },
            onGuideScheduleChanged = {},
            appScope = CoroutineScope(mainDispatcher),
        )
        CoroutineScope(mainDispatcher).launch { slow.effects.collect { effects += it } }

        slow.switchAccount()
        assertFalse(slow.ui.value.signingOut)
        slow.confirmDialog()

        // The confirmation is gone and the screen shows progress; nothing has opened yet.
        assertNull(slow.ui.value.dialog)
        assertTrue(slow.ui.value.signingOut)
        assertTrue(effects.isEmpty())

        gate.complete(Unit)
        assertFalse(slow.ui.value.signingOut)
        assertEquals(listOf("sign-out"), actions)
        assertEquals(listOf<SettingsEffect>(SettingsEffect.GoToSignIn), effects)
    }

    @Test
    fun `refresh everything leaves Settings at once while the work runs on`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = SettingsViewModel(
            settings = settings,
            loadAccount = { null },
            onRefreshEverything = { gate.await(); actions += "refresh-everything" },
            onSignOut = {},
            onGuideScheduleChanged = {},
            appScope = CoroutineScope(mainDispatcher),
        )
        CoroutineScope(mainDispatcher).launch { slow.effects.collect { effects += it } }

        slow.refreshEverything()
        slow.confirmDialog()

        assertEquals(listOf<SettingsEffect>(SettingsEffect.GoToDashboard), effects)
        assertTrue(actions.isEmpty())
        gate.complete(Unit)
        assertEquals(listOf("refresh-everything"), actions)
    }

    @Test
    fun `reset to defaults asks first, then says it is done until the section changes`() = runTest {
        viewModel.setReplayFromStart(true)

        viewModel.resetToDefaults()
        assertEquals(SettingsDialog.RESET_DEFAULTS, viewModel.ui.value.dialog)
        assertTrue("nothing changes before the confirmation", settings.settings.first().replayFromStart)
        assertFalse(viewModel.ui.value.resetDone)

        viewModel.dismissDialog()
        assertTrue("cancel changes nothing", settings.settings.first().replayFromStart)
        assertFalse(viewModel.ui.value.resetDone)

        viewModel.resetToDefaults()
        viewModel.confirmDialog()
        assertNull(viewModel.ui.value.dialog)
        assertFalse(settings.settings.first().replayFromStart)
        assertTrue(viewModel.ui.value.resetDone)

        viewModel.selectSection(SettingsSection.PLAYER)
        assertFalse(viewModel.ui.value.resetDone)
    }

    @Test
    fun `reset copy is sentence case with no exclamation point`() {
        listOf(
            ResetDefaultsCopy.TITLE,
            ResetDefaultsCopy.BODY,
            ResetDefaultsCopy.CONFIRM,
            ResetDefaultsCopy.ROW_CAPTION,
            ResetDefaultsCopy.DONE_CAPTION,
        ).forEach { assertFalse(it, it.contains("!")) }
    }

    @Test
    fun `the signed-out account state is signable`() = runTest {
        val signedOut = SettingsViewModel(
            settings = settings,
            loadAccount = { null },
            onRefreshEverything = { actions += "refresh-everything" },
            onSignOut = { actions += "sign-out" },
            onGuideScheduleChanged = { scheduleChanges += it },
            appScope = CoroutineScope(mainDispatcher),
        )
        val state = signedOut.ui.first { it.accountResolved }
        assertFalse(state.signedIn)
        assertNull(state.account)

        CoroutineScope(mainDispatcher).launch { signedOut.effects.collect { effects += it } }
        signedOut.goToSignIn()
        assertEquals(listOf<SettingsEffect>(SettingsEffect.GoToSignIn), effects)
    }
}
