package com.rysal.happytv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rysal.happytv.data.AppSettings
import com.rysal.happytv.data.Graph
import com.rysal.happytv.data.BackWhileWatching
import com.rysal.happytv.data.LiveTvOpenOn
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.dao.HiddenChannelListing
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.player.PlayerUrlBuilder
import com.rysal.happytv.ui.browse.relativeTimeLabel
import com.rysal.happytv.ui.main.tileTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Account pane's facts, derived from the stored login profile and the
 * encrypted credential set. [signedIn] false means the signed-out card, not an
 * error — the other four sections keep working (design spec §"Settings > States").
 */
data class SettingsAccount(
    val serverName: String,
    val host: String,
    val port: Int,
    val username: String,
    val signedInAtUtcMillis: Long,
) {
    /** "Home · provider.example:8080" — the Account → Server row's value. */
    val serverLabel: String get() = "$serverName · $host:$port"

    /** "12 hours ago" — the Account → Signed in row's value. */
    fun signedInLabel(nowUtcMillis: Long): String = relativeTimeLabel(signedInAtUtcMillis, nowUtcMillis)
}

/**
 * The confirmations: the two design spec §"Settings > Confirmations" specifies, the unencrypted
 * override (#21) and Reset to defaults (#166).
 */
enum class SettingsDialog { REFRESH_EVERYTHING, SIGN_OUT, ALLOW_UNENCRYPTED_SIGN_IN, RESET_DEFAULTS }

/** Copy for the sign-out confirmation (#159) and the progress card shown while it runs (#160). */
object SignOutCopy {
    /** Names the signed-in server; a blank label (no account loaded) falls back to a plain title. */
    fun title(serverName: String?): String =
        serverName?.trim().takeUnless { it.isNullOrEmpty() }?.let { "Sign out of $it?" } ?: "Sign out?"

    const val BODY = "HappyTV forgets this server's password and clears its saved lists, favorites, " +
        "recently watched and hidden items. You'll need the server URL, username and password to sign in again."
    const val CONFIRM = "Sign out"
    const val PROGRESS_TITLE = "Signing out"
    const val PROGRESS_BODY = "Clearing this server's saved lists. This can take a minute."
}

/** Copy for the Reset to defaults confirmation and its result (#166). */
object ResetDefaultsCopy {
    const val TITLE = "Reset to defaults?"
    const val BODY = "Every setting goes back to the way it started. Your sign-in, favorites and history stay."
    const val CONFIRM = "Reset settings"
    const val ROW_CAPTION = "Puts every setting back the way it started."
    const val DONE_CAPTION = "Done. Every setting is back to the way it started."
}

/** Copy for the "Allow sign-in details over unencrypted connections" row and its confirmation (#21). */
object UnencryptedSignInCopy {
    const val LABEL = "Allow sign-in details over unencrypted connections"
    const val CAPTION_OFF = "Off. Playback stops instead of sending your username and password without encryption."
    const val CAPTION_ON = "On. Playback may send your username and password without encryption."
    const val TITLE = "Allow sign-in details over unencrypted connections?"
    const val BODY = "When a stream is redirected to an unencrypted address, HappyTV will send your username " +
        "and password with it, where others on your network could read them. Turn this on only if you trust " +
        "your network and your provider."
    const val CONFIRM = "Turn on"
}

/** #19: the backup switch. Sign-in details are never in the backup, in either state. */
object BackupCopy {
    const val LABEL = "Back up favorites and history"
    const val CAPTION_ON = "Saved with your Google account backup. Sign-in details are never backed up."
    const val CAPTION_OFF = "Off. Nothing is saved, and the next scheduled backup replaces an older one. Sign-in details are never backed up."
}

/** Copy for the "Refresh everything now" confirmation and its row (#48). */
object RefreshEverythingCopy {
    const val TITLE = "Refresh everything now?"
    const val BODY = "HappyTV downloads your channels, movies, shows and guide again. " +
        "Your favorites, recently watched and hidden channels stay. It usually takes a few minutes."
    const val CONFIRM = "Refresh everything"
    const val ROW_CAPTION = "Downloads channels, movies, shows and the guide again. Your favorites stay."
}

/**
 * One hidden category in the Guide pane's management list (F-44): what the
 * user hid, in which catalog, so the row can say "Show News — Hidden from
 * Live TV" and unhide exactly that row.
 */
data class HiddenCategoryRow(
    val id: String,
    val type: StreamType,
    val name: String,
) {
    /** "Live TV" / "Movies" / "Shows" — the dashboard's tile titles. */
    val catalogLabel: String get() = tileTitle(type)
}

/**
 * One hidden channel in the Hidden items pane (#123). [name] is null when the channel is no longer
 * in the catalog; the row stays so it can still be removed from the hidden list.
 */
data class HiddenChannelRow(
    val id: String,
    val name: String?,
    val categoryName: String?,
) {
    val label: String get() = if (name.isNullOrBlank()) "Show removed channel" else "Show $name"

    val caption: String
        get() = when {
            name == null -> "No longer offered by your provider"
            categoryName.isNullOrBlank() -> "No category"
            else -> categoryName
        }
}

/** One-shot events the activity acts on (never replayed on rotation). */
sealed interface SettingsEffect {
    /** "Refresh everything now?" confirmed — back to the dashboard, tiles refreshing. */
    data object GoToDashboard : SettingsEffect

    /** "Sign out of Home?" confirmed — credentials gone; open sign in. */
    data object GoToSignIn : SettingsEffect
}

data class SettingsUiState(
    val section: SettingsSection = SettingsSection.ACCOUNT,
    /** True once the account lookup has answered — until then, seed focus. */
    val accountResolved: Boolean = false,
    val signedIn: Boolean = false,
    val account: SettingsAccount? = null,
    val settings: AppSettings = AppSettings(),
    /**
     * F-52 / #18: true when the guide can be derived from the saved Xtream
     * credentials with nothing typed. The derived URL itself (it carries the
     * password) is never held in UI state or shown.
     */
    val autoGuideAvailable: Boolean = false,
    /** #18: the typed link with credentials masked; blank when none is stored. */
    val overrideLabel: String = "",
    /**
     * The Guide pane's XMLTV field is shown while this is true — i.e. from
     * the moment **XMLTV link** is chosen, even before a link has been typed. On
     * entry it follows the saved fact: true only when a link of the user's own
     * is stored ([hasTypedLink]). The automatic source is **From provider**
     * (#94), so it never opens the pane on XMLTV link.
     */
    val xmltvLinkShown: Boolean = false,
    /** Hidden categories across every catalog, each one an unhide row (F-44). */
    val hiddenCategories: List<HiddenCategoryRow> = emptyList(),
    /** #123: every hidden channel, sorted by name; gone channels last. */
    val hiddenChannels: List<HiddenChannelRow> = emptyList(),
    val dialog: SettingsDialog? = null,
    /** #160: sign-out is clearing the saved lists; the screen shows progress and takes no input. */
    val signingOut: Boolean = false,
    /** #166: settings were just reset; the About row says so until the section changes. */
    val resetDone: Boolean = false,
) {
    /** #94: a link the user typed (or a pre-#18 raw one) is stored; auto and "" are not. */
    val hasTypedLink: Boolean
        get() = when (settings.epgUrl?.trim()) {
            null, "", SettingsRepository.EPG_AUTO -> false
            else -> true
        }

    /**
     * #94: what the Guide source control highlights. One fact drives both the
     * control and the link field: the field is visible exactly when
     * **XMLTV link** is highlighted, and the automatic guide reads as
     * **From provider**.
     */
    val guideSource: GuideSource
        get() = if (xmltvLinkShown) GuideSource.XMLTV else GuideSource.PROVIDER

    /**
     * What the Guide pane's field shows (#18: never a credential). A typed
     * override shows redacted; auto shows [AUTO_GUIDE_LABEL]; the explicit
     * "From provider" sentinel (`""`) resolves to nothing.
     */
    val effectiveXmlTvUrl: String
        get() {
            val auto = if (autoGuideAvailable) SettingsViewModel.AUTO_GUIDE_LABEL else ""
            return when (val stored = settings.epgUrl?.trim()) {
                null, SettingsRepository.EPG_AUTO -> auto
                "" -> ""
                SettingsRepository.EPG_OVERRIDE -> overrideLabel.ifBlank { auto }
                else -> PlayerUrlBuilder.redactCredentials(stored) // pre-#18 raw value
            }
        }
}

/**
 * Settings' state machine: every change is written through immediately (there
 * is no Save button — design spec §"Settings"), and the two destructive rows go
 * through a confirmation dialog that names exactly what will happen.
 *
 * Long-running confirmations ([refreshEverything], [switchAccount]) run on
 * [appScope], not [viewModelScope]: "Refresh everything" returns to the
 * dashboard while three catalog downloads keep going, so the work must outlive
 * this screen.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val loadAccount: suspend () -> SettingsAccount?,
    private val onRefreshEverything: suspend () -> Unit,
    private val onSignOut: suspend () -> Unit,
    private val onGuideScheduleChanged: (hours: Int) -> Unit,
    private val appScope: CoroutineScope,
    startSection: SettingsSection = SettingsSection.ACCOUNT,
    private val clockUtcMillis: () -> Long = System::currentTimeMillis,
    /**
     * F-52: derives the panel's `xmltv.php` URL from the saved Xtream
     * credentials, for the Guide pane's prefill. Null-returning default for
     * tests that don't exercise the derivation path.
     */
    private val deriveXmlTvUrl: suspend () -> String? = { null },
    /** Every hidden category, all catalogs — the Guide pane's management list (F-44). */
    hiddenCategories: Flow<List<Category>> = emptyFlow(),
    /** Unhide one row; wired to the shared CategoryDao toggle in production. */
    private val onUnhideCategory: suspend (id: String, type: StreamType) -> Unit = { _, _ -> },
    /** #123: every hidden channel with its name and category name (Hidden items pane). */
    hiddenChannels: Flow<List<HiddenChannelListing>> = emptyFlow(),
    /** #123: unhide one channel; wired to the shared HiddenChannelDao in production. */
    private val onUnhideChannel: suspend (id: String) -> Unit = {},
    /** #19: asks Android for a backup soon, so a turned-off switch replaces the old backup. */
    private val onBackupSwitchChanged: () -> Unit = {},
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState(section = startSection))
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            // F-52: resolve the derived URL before the entry state is
            // computed, so the Guide pane opens prefilled with the URL the
            // guide actually fetches — no typing required.
            val derived = runCatching { deriveXmlTvUrl() }.getOrNull()
            val account = loadAccount()
            // Fresh read — the state's snapshot may not have arrived yet.
            val snapshot = settings.settings.first()
            val label = overrideLabelFor(snapshot.epgUrl)
            _ui.update {
                val next = it.copy(
                    accountResolved = true,
                    signedIn = account != null,
                    account = account,
                    autoGuideAvailable = !derived.isNullOrBlank(),
                    overrideLabel = label,
                    settings = snapshot,
                )
                next.copy(xmltvLinkShown = it.xmltvLinkShown || next.hasTypedLink)
            }
        }
        viewModelScope.launch {
            settings.settings.collect { snapshot ->
                val label = overrideLabelFor(snapshot.epgUrl)
                _ui.update { current ->
                    val next = current.copy(settings = snapshot, overrideLabel = label)
                    // The explicit "From provider" (`""`) collapses the field; a
                    // typed link reveals it; otherwise the user's choice stands.
                    next.copy(
                        xmltvLinkShown = (current.xmltvLinkShown || next.hasTypedLink) &&
                            next.settings.epgUrl != "",
                    )
                }
            }
        }
        // F-44: the hidden-category list follows the database, so a hide
        // from any screen lands here and an unhide in this pane removes
        // its own row.
        viewModelScope.launch {
            hiddenCategories.collect { rows ->
                _ui.update { current ->
                    current.copy(
                        // #123: sorted by name, not grouped by catalog; the caption says which.
                        hiddenCategories = rows.map { category ->
                            HiddenCategoryRow(id = category.id, type = category.type, name = category.name)
                        }.sortedWith(
                            compareBy<HiddenCategoryRow, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                                .thenBy { it.type.ordinal }.thenBy { it.id },
                        ),
                    )
                }
            }
        }
        // #123: the hidden-channel list follows the database too; Room runs the join off the main thread.
        viewModelScope.launch {
            hiddenChannels.collect { rows ->
                _ui.update { current ->
                    current.copy(hiddenChannels = rows.map { HiddenChannelRow(it.streamId, it.name, it.categoryName) })
                }
            }
        }
    }

    // ----------------------------------------------------------------- panes

    fun selectSection(section: SettingsSection) {
        _ui.update { if (it.section == section) it else it.copy(section = section, resetDone = false) }
    }

    /**
     * The signed-out Account card's **Sign in**: the screen leaves for the
     * sign-in screen, so this is a one-shot effect, not state.
     */
    fun goToSignIn() {
        _effects.tryEmit(SettingsEffect.GoToSignIn)
    }

    // -------------------------------------------------------------- Account

    fun switchAccount() = _ui.update { it.copy(dialog = SettingsDialog.SIGN_OUT) }

    // -------------------------------------------------------------- Refresh

    fun setRefreshOnStart(value: Boolean) = launchWrite { settings.setRefreshOnStart(value) }

    fun setStreamFormat(option: StreamFormatOption) = launchWrite {
        settings.setPreferredStreamFormat(option.stored)
    }

    fun refreshEverything() = _ui.update { it.copy(dialog = SettingsDialog.REFRESH_EVERYTHING) }

    // -------------------------------------------------------------- Live TV

    fun setLiveTvOpenOn(option: LiveTvOpenOn) = launchWrite { settings.setLiveTvOpenOn(option) }

    fun setKeepCategoriesOpen(value: Boolean) = launchWrite { settings.setKeepCategoriesOpen(value) }

    // --------------------------------------------------------------- Guide

    fun chooseGuideSource(source: GuideSource) {
        when (source) {
            // "From provider" stores the explicit `""` sentinel (F-52's
            // three-state contract) so the worker's derivation can't undo
            // the choice on the next refresh. The field disappears with it.
            GuideSource.PROVIDER -> {
                _ui.update { it.copy(xmltvLinkShown = false) }
                launchWrite { settings.setEpgUrl("") }
            }
            // Choosing the link reveals the field — prefilled with the
            // derived URL (F-52) when nothing is typed. Coming back from an
            // explicit "From provider" returns to auto (null); an
            // already-typed URL is never touched. Either way the source
            // flips for real once a URL is effective.
            GuideSource.XMLTV -> {
                _ui.update { it.copy(xmltvLinkShown = true) }
                if (_ui.value.settings.epgUrl == "") launchWrite { settings.setEpgUrl(null) }
            }
        }
    }

    /**
     * Every keystroke saves (trimmed). Clearing the field drops the
     * override and falls back to the auto-derived URL (F-52) — "From
     * provider" is the Guide source row's job, not the field's.
     */
    fun setXmltvUrl(raw: String) {
        val trimmed = raw.trim()
        launchWrite { settings.setEpgUrl(trimmed.ifBlank { null }) }
    }

    fun setGuideRefreshHours(option: GuideRefreshOption) {
        onGuideScheduleChanged(option.hours)
        launchWrite { settings.setEpgRefreshHours(option.hours) }
    }

    /** F-44: "Show <category>" on a hidden-category row — flips the durable flag back. */
    fun unhideCategory(id: String, type: StreamType) = launchWrite { onUnhideCategory(id, type) }

    /** #123: "Show <channel>" on a hidden-channel row; removes it from the hidden list. */
    fun unhideChannel(id: String) = launchWrite { onUnhideChannel(id) }

    // -------------------------------------------------------------- Player

    fun setReplayFromStart(value: Boolean) = launchWrite { settings.setReplayFromStart(value) }

    fun setBackWhileWatching(option: BackWhileWatching) = launchWrite { settings.setBackWhileWatching(option) }

    /**
     * #21: turning the override **on** asks first (the dialog's [confirmDialog]
     * writes it); turning it off saves at once, because off is the safe state.
     */
    fun setAllowSignInOverUnencrypted(value: Boolean) {
        if (value) {
            _ui.update { it.copy(dialog = SettingsDialog.ALLOW_UNENCRYPTED_SIGN_IN) }
        } else {
            launchWrite { settings.setAllowSignInOverUnencrypted(false) }
        }
    }

    /** #19: saves at once (neither state is risky) and nudges Android to back up again. */
    fun setBackupHousehold(value: Boolean) = launchWrite {
        settings.setBackupHousehold(value)
        onBackupSwitchChanged()
    }

    fun setAspectRatio(option: AspectRatioOption) = launchWrite { settings.setAspectRatio(option.stored) }

    // --------------------------------------------------------------- About

    /** Asks first (#166); [confirmDialog] does the reset. */
    fun resetToDefaults() = _ui.update { it.copy(dialog = SettingsDialog.RESET_DEFAULTS, resetDone = false) }

    // ---------------------------------------------------------- confirmations

    fun dismissDialog() = _ui.update { it.copy(dialog = null) }

    fun confirmDialog() {
        when (_ui.value.dialog) {
            SettingsDialog.REFRESH_EVERYTHING -> {
                _ui.update { it.copy(dialog = null) }
                // #166: the dashboard is the feedback (its tiles show Loading), so go there
                // now instead of after the minutes the downloads take. The work outlives
                // this screen on appScope.
                _effects.tryEmit(SettingsEffect.GoToDashboard)
                appScope.launch { onRefreshEverything() }
            }
            SettingsDialog.SIGN_OUT -> {
                // #160: clearing 150k+ catalog rows takes tens of seconds; show that it is
                // working until the sign-in screen can open.
                _ui.update { it.copy(dialog = null, signingOut = true) }
                appScope.launch {
                    try {
                        onSignOut()
                    } finally {
                        _ui.update { it.copy(signingOut = false) }
                    }
                    _effects.tryEmit(SettingsEffect.GoToSignIn)
                }
            }
            SettingsDialog.RESET_DEFAULTS -> {
                _ui.update { it.copy(dialog = null) }
                viewModelScope.launch {
                    settings.resetUserSettings()
                    _ui.update { it.copy(resetDone = true) }
                }
            }
            SettingsDialog.ALLOW_UNENCRYPTED_SIGN_IN -> {
                _ui.update { it.copy(dialog = null) }
                launchWrite { settings.setAllowSignInOverUnencrypted(true) }
            }
            null -> Unit
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * One `launch` per change: DataStore `edit` is atomic, so typing fast in
     * the XMLTV field writes a sequence of whole values, never a torn one.
     */
    /** #18: the masked form of the typed link, read from the encrypted store. */
    private suspend fun overrideLabelFor(stored: String?): String = when {
        stored == SettingsRepository.EPG_OVERRIDE ->
            settings.epgOverrideUrl()?.let { PlayerUrlBuilder.redactCredentials(it) }.orEmpty()
        else -> ""
    }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        /** #18: the non-secret stand-in shown for the auto-derived guide link. */
        const val AUTO_GUIDE_LABEL = "From your provider (automatic)"

        /** Production wiring — everything else is constructor-injectable for tests. */
        fun factory(startSection: SettingsSection = SettingsSection.ACCOUNT): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                    settings = Graph.settings,
                    loadAccount = { SettingsAccountSource.load() },
                    // F-52: the Guide pane prefills the XMLTV field with the
                    // URL derived from the saved credentials, so the guide
                    // needs no second entry on any standard Xtream panel.
                    deriveXmlTvUrl = {
                        com.rysal.happytv.data.guide.XmlTvUrlDeriver.derive(
                            Graph.securedCredentials.current(),
                        )
                    },
                    onRefreshEverything = {
                        // #48: re-download the catalog and the guide; favorites,
                        // recently watched and hidden items are kept. The old
                        // guide stays until the staged import promotes.
                        Graph.guideRepository.forceNextDownload()
                        com.rysal.happytv.data.guide.RefreshGuideScheduler.refreshNow(Graph.appContext)
                        Graph.refreshCoordinator.refreshEverything()
                    },
                    onSignOut = {
                        Graph.refreshCoordinator.signOut()
                        Graph.refreshCoordinator.clearCatalogs()
                    },
                    onGuideScheduleChanged = { hours ->
                        com.rysal.happytv.data.guide.RefreshGuideScheduler.reschedule(
                            Graph.appContext,
                            hours,
                        )
                    },
                    appScope = Graph.appScope,
                    startSection = startSection,
                    // F-44: the hidden-category management list reads the same
                    // table every hide writes, and unhides through the same
                    // CategoryDao toggle Live TV / Guide / Movies use.
                    hiddenCategories = Graph.database.categoryDao().observeHidden(),
                    onUnhideCategory = { id, type ->
                        Graph.database.categoryDao().toggleHidden(id, type)
                    },
                    hiddenChannels = Graph.database.hiddenChannelDao().observeListing(),
                    onUnhideChannel = { id ->
                        // Delete, not toggle: a second press racing the first must not re-hide it.
                        Graph.database.hiddenChannelDao().delete(id, StreamType.LIVE)
                    },
                    onBackupSwitchChanged = {
                        // Schedules Android's own backup pass; it never starts one here.
                        runCatching { android.app.backup.BackupManager.dataChanged(Graph.appContext.packageName) }
                    },
                ) as T
            }
    }
}
