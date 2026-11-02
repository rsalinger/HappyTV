package com.rysal.happytv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rysal.happytv.backup.HouseholdSettings
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.guide.XmlTvUrlDeriver
import com.rysal.happytv.security.Credentials
import com.rysal.happytv.security.EpgOverrideStore
import com.rysal.happytv.security.InMemoryEpgOverrideStore
import com.rysal.happytv.ui.player.PlayerUrlBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The app's single DataStore.
 *
 * Declared as a **file-level extension property** because DataStore's
 * `preferencesDataStore` delegate refuses to live inside a class: the delegate
 * itself guarantees one instance per process, so every reader/writer must go
 * through this exact property ([Graph.settings] does).
 */
val Context.topLevelDataStore: DataStore<Preferences> by preferencesDataStore(name = "happytv_settings")

/** Spec #7 §3.3: what Live TV shows first. Favorites is the default. */
enum class LiveTvOpenOn(val stored: String, val label: String) {
    FAVORITES("favorites", "Favorites"),
    CHOOSE("choose", "Choose a category"),
    LAST("last", "Last category"),
    ;

    companion object {
        /** Absent or unknown → the default, Favorites. */
        fun fromStored(raw: String?): LiveTvOpenOn = entries.firstOrNull { it.stored == raw } ?: FAVORITES
    }
}

/**
 * Spec #7 section 5 / #16: what Back does in full-screen live playback. Show
 * channels (default) shrinks the picture into the Live TV preview window;
 * Stop playback stops it and returns to the screen.
 */
enum class BackWhileWatching(val stored: String, val label: String) {
    SHOW_CHANNELS("show_channels", "Show channels"),
    STOP_PLAYBACK("stop_playback", "Stop playback"),
    ;

    companion object {
        /** Absent or unknown -> the default, Show channels. */
        fun fromStored(raw: String?): BackWhileWatching = entries.firstOrNull { it.stored == raw } ?: SHOW_CHANNELS
    }
}

/**
 * Spec #7 §3.6: which body the combined Live TV screen shows. The Guide | List
 * toggle writes it, and the dashboard's Live TV tile opens the last one. The
 * guide is the default.
 */
enum class LiveTvView(val stored: String) {
    GUIDE("guide"),
    LIST("list"),
    ;

    companion object {
        /** Absent or unknown -> the default, the guide. */
        fun fromStored(raw: String?): LiveTvView = entries.firstOrNull { it.stored == raw } ?: GUIDE
    }
}

/**
 * Immutable snapshot of every user-tunable setting.
 *
 * Defaults are applied here (absent key → default) rather than in the DataStore
 * file, so a fresh install and a cleared-prefs install behave identically.
 */
data class AppSettings(
    /** Re-fetch catalogs when the app comes to foreground (the 3 tiles). */
    val refreshOnStart: Boolean = true,
    /**
     * The guide-source **mode** (#18), never a URL with credentials:
     * null = auto (derive from saved credentials, not yet imported),
     * "" = explicit "From provider",
     * [SettingsRepository.EPG_AUTO] = auto, derived and imported,
     * [SettingsRepository.EPG_OVERRIDE] = a typed link, kept encrypted
     * (read it with [SettingsRepository.epgOverrideUrl]).
     * A raw URL here is a pre-#18 value awaiting migration.
     */
    val epgUrl: String? = null,
    /** Background EPG refresh period in hours. design spec default: 12. */
    val epgRefreshHours: Int = 12,
    /** Days of *past* programmes to keep after each XMLTV parse. */
    val epgPastDaysToKeep: Int = 7,
    /** Playback container: `"m3u8"` (HLS) or `"ts"` (MPEG-TS). */
    val preferredStreamFormat: String = "m3u8",
    /** Offer timeshift/catch-up controls on live channels that support it. */
    val catchupEnabled: Boolean = true,
    /**
     * design spec §"Settings > Player": "Start replays from the beginning" — open a
     * replayable channel's program from its start instead of live. Off by
     * default (user-confirmed 2026-10-16).
     */
    val replayFromStart: Boolean = false,
    /**
     * design spec §"Settings > Player": aspect ratio — `"auto"`, `"16:9"` or
     * `"4:3"`. `auto` fits the picture without cropping.
     */
    val aspectRatio: String = SettingsRepository.ASPECT_AUTO,
    /** Spec #7 §3.3 "Open Live TV on": what the screen shows first. */
    val liveTvOpenOn: LiveTvOpenOn = LiveTvOpenOn.FAVORITES,
    /** Spec #7 §3.2 "Keep categories open": the rail is a permanent left pane. */
    val keepCategoriesOpen: Boolean = false,
    /** Spec #7 section 4 "Back while watching" (#16). */
    val backWhileWatching: BackWhileWatching = BackWhileWatching.SHOW_CHANNELS,
    /**
     * #21: let playback follow an HTTPS to HTTP redirect even when its target
     * carries the sign-in details. Off by default. Device-level: it lives in
     * this DataStore, which sign-out, sign-in, Switch account and Refresh
     * everything never clear, so it is still set at the next sign-in.
     */
    val allowSignInOverUnencrypted: Boolean = false,
    /**
     * #19: include favorites, hidden items, recents, watch progress and
     * non-secret settings in the Android backup. On by default. Device-level
     * like [allowSignInOverUnencrypted]: sign-out and Switch account never
     * clear it. Never part of the backup file itself.
     */
    val backupHousehold: Boolean = true,
    /**
     * The category the user last selected in Live TV (rail key: a pinned-row
     * key or a provider category id), for "Last category". Null = none yet.
     */
    val lastLiveCategory: String? = null,
    /**
     * Spec #7 §3.6: the Guide | List view last used in Live TV. Not on the
     * settings screen (the header toggle writes it), so a reset leaves it alone.
     */
    val liveTvView: LiveTvView = LiveTvView.GUIDE,
    /** Last successful live-catalog refresh (epoch millis, 0 = never). */
    val lastLiveRefreshUtcMillis: Long = 0L,
    /** Last successful VOD-catalog refresh. */
    val lastVodRefreshUtcMillis: Long = 0L,
    /** Last successful series-catalog refresh. */
    val lastSeriesRefreshUtcMillis: Long = 0L,
    /** #195: where each catalog's sync stands; see [SyncMark]. */
    val liveSyncMark: SyncMark = SyncMark.NONE,
    val vodSyncMark: SyncMark = SyncMark.NONE,
    val seriesSyncMark: SyncMark = SyncMark.NONE,
    /** Xtream `user_info` JSON as bytes, kept for fast hydration before a refresh. */
    val userInfo: ByteArray? = null,
    /**
     * #126: the light palette. Off (dark) by default. Switched from the
     * dashboard header, not the Settings screen, so a settings reset leaves it.
     */
    val lightMode: Boolean = false,
) {
    // ByteArray breaks data-class equals/hashCode (reference identity), which would
    // defeat distinctUntilChanged() on the settings Flow and leak into logs.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppSettings) return false
        return refreshOnStart == other.refreshOnStart &&
            epgUrl == other.epgUrl &&
            epgRefreshHours == other.epgRefreshHours &&
            epgPastDaysToKeep == other.epgPastDaysToKeep &&
            preferredStreamFormat == other.preferredStreamFormat &&
            catchupEnabled == other.catchupEnabled &&
            replayFromStart == other.replayFromStart &&
            aspectRatio == other.aspectRatio &&
            liveTvOpenOn == other.liveTvOpenOn &&
            keepCategoriesOpen == other.keepCategoriesOpen &&
            backWhileWatching == other.backWhileWatching &&
            allowSignInOverUnencrypted == other.allowSignInOverUnencrypted &&
            backupHousehold == other.backupHousehold &&
            lastLiveCategory == other.lastLiveCategory &&
            liveTvView == other.liveTvView &&
            lastLiveRefreshUtcMillis == other.lastLiveRefreshUtcMillis &&
            lastVodRefreshUtcMillis == other.lastVodRefreshUtcMillis &&
            lastSeriesRefreshUtcMillis == other.lastSeriesRefreshUtcMillis &&
            liveSyncMark == other.liveSyncMark &&
            vodSyncMark == other.vodSyncMark &&
            seriesSyncMark == other.seriesSyncMark &&
            lightMode == other.lightMode &&
            userInfo.contentEquals(other.userInfo) // nullable overload: null == null → true
    }

    override fun hashCode(): Int {
        var result = refreshOnStart.hashCode()
        result = 31 * result + (epgUrl?.hashCode() ?: 0)
        result = 31 * result + epgRefreshHours
        result = 31 * result + epgPastDaysToKeep
        result = 31 * result + preferredStreamFormat.hashCode()
        result = 31 * result + catchupEnabled.hashCode()
        result = 31 * result + replayFromStart.hashCode()
        result = 31 * result + aspectRatio.hashCode()
        result = 31 * result + liveTvOpenOn.hashCode()
        result = 31 * result + keepCategoriesOpen.hashCode()
        result = 31 * result + backWhileWatching.hashCode()
        result = 31 * result + allowSignInOverUnencrypted.hashCode()
        result = 31 * result + backupHousehold.hashCode()
        result = 31 * result + (lastLiveCategory?.hashCode() ?: 0)
        result = 31 * result + liveTvView.hashCode()
        result = 31 * result + lastLiveRefreshUtcMillis.hashCode()
        result = 31 * result + lastVodRefreshUtcMillis.hashCode()
        result = 31 * result + lastSeriesRefreshUtcMillis.hashCode()
        result = 31 * result + liveSyncMark.hashCode()
        result = 31 * result + vodSyncMark.hashCode()
        result = 31 * result + seriesSyncMark.hashCode()
        result = 31 * result + lightMode.hashCode()
        result = 31 * result + (userInfo?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AppSettings(refreshOnStart=$refreshOnStart, epgUrl=${epgUrl?.let { PlayerUrlBuilder.redactCredentials(it) }}, epgRefreshHours=$epgRefreshHours, " +
            "epgPastDaysToKeep=$epgPastDaysToKeep, preferredStreamFormat=$preferredStreamFormat, " +
            "catchupEnabled=$catchupEnabled, replayFromStart=$replayFromStart, aspectRatio=$aspectRatio, " +
            "liveTvOpenOn=$liveTvOpenOn, keepCategoriesOpen=$keepCategoriesOpen, backWhileWatching=$backWhileWatching, allowSignInOverUnencrypted=$allowSignInOverUnencrypted, backupHousehold=$backupHousehold, " +
            "lastLiveRefreshUtcMillis=$lastLiveRefreshUtcMillis, lastVodRefreshUtcMillis=$lastVodRefreshUtcMillis, " +
            "lastSeriesRefreshUtcMillis=$lastSeriesRefreshUtcMillis, lightMode=$lightMode, " +
            "userInfo=${userInfo?.let { "${it.size}B" }})"
}

/**
 * #195: whether a catalog's stored rows can be trusted as a whole. The sync writes rows in
 * chunks and the categories last, so a process that dies midway leaves "55,000 movies, 0
 * categories". The mark is written before the first chunk and cleared when the sync ends.
 */
enum class SyncMark(val stored: String) {
    /** Nothing in flight; the stored rows are as complete as the last stamp says. */
    NONE("none"),

    /** A sync is running over a previously complete catalog. If this survives a restart, the run was cut short. */
    RUNNING("running"),

    /** The stored rows are known to be incomplete (a first sync, or a run cut short). */
    PARTIAL("partial"),
    ;

    companion object {
        fun fromStored(value: String?): SyncMark = entries.firstOrNull { it.stored == value } ?: NONE
    }
}

/**
 * Typed access to [topLevelDataStore].
 *
 * Reads are a single cold [Flow] that maps preferences → [AppSettings] with
 * defaults filled in; writes are one `edit` per setter (each atomic, each
 * cancelling cleanly if the scope dies). Inject the [DataStore] directly in
 * tests — construction never touches Android APIs.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val epgOverrideStore: EpgOverrideStore = InMemoryEpgOverrideStore(),
) {

    /** Emits on any preference change; [distinctUntilChanged] keeps recomposition cheap. */
    val settings: Flow<AppSettings> = dataStore.data
        .map { it.toAppSettings() }
        .distinctUntilChanged()

    suspend fun setRefreshOnStart(value: Boolean) = edit { it[KEY_REFRESH_ON_START] = value }

    /**
     * Sets the guide-source mode. `null`, `""` and the [EPG_AUTO] / [EPG_OVERRIDE]
     * markers go to the plain DataStore; any other value is a typed link, which
     * goes to the encrypted store behind an [EPG_OVERRIDE] marker (#18).
     */
    suspend fun setEpgUrl(value: String?) {
        if (value == null || value.isEmpty() || value == EPG_AUTO || value == EPG_OVERRIDE) {
            if (value != EPG_OVERRIDE) epgOverrideStore.set(null)
            edit { prefs -> if (value == null) prefs.remove(KEY_EPG_URL) else prefs[KEY_EPG_URL] = value }
        } else {
            epgOverrideStore.set(value)
            edit { it[KEY_EPG_URL] = EPG_OVERRIDE }
        }
    }

    /** The typed XMLTV link (decrypted), or null when none is stored. */
    suspend fun epgOverrideUrl(): String? = epgOverrideStore.get()?.takeIf { it.isNotBlank() }

    /**
     * #18 migration: before this fix the derived `xmltv.php?username=...&password=...`
     * URL was persisted in plain `happytv_settings`. A stored raw URL that equals
     * the one derivable from [credentials], or contains the password, becomes the
     * auto marker; any other raw URL is a typed override and moves to the
     * encrypted store. Idempotent; returns true when something changed.
     */
    suspend fun migrateLegacyEpgUrl(credentials: Credentials?): Boolean {
        val raw = dataStore.data.first()[KEY_EPG_URL] ?: return false
        if (raw.isEmpty() || raw == EPG_AUTO || raw == EPG_OVERRIDE) return false
        val password = credentials?.password.orEmpty()
        val derived = XmlTvUrlDeriver.derive(credentials)
        val isDerived = raw.trim() == derived ||
            (
                password.isNotEmpty() &&
                    (
                        raw.contains(password) ||
                            raw.contains(java.net.URLEncoder.encode(password, Charsets.UTF_8.name()))
                        )
                )
        if (isDerived) {
            edit { it[KEY_EPG_URL] = EPG_AUTO }
        } else {
            epgOverrideStore.set(raw.trim())
            edit { it[KEY_EPG_URL] = EPG_OVERRIDE }
        }
        return true
    }

    suspend fun setEpgRefreshHours(value: Int) {
        require(value > 0) { "epgRefreshHours must be > 0, was $value" }
        edit { it[KEY_EPG_REFRESH_HOURS] = value }
    }

    suspend fun setEpgPastDaysToKeep(value: Int) {
        require(value >= 0) { "epgPastDaysToKeep must be >= 0, was $value" }
        edit { it[KEY_EPG_PAST_DAYS_TO_KEEP] = value }
    }

    suspend fun setPreferredStreamFormat(value: String) {
        require(value == FORMAT_M3U8 || value == FORMAT_TS) {
            "preferredStreamFormat must be \"$FORMAT_M3U8\" or \"$FORMAT_TS\", was \"$value\""
        }
        edit { it[KEY_PREFERRED_STREAM_FORMAT] = value }
    }

    suspend fun setCatchupEnabled(value: Boolean) = edit { it[KEY_CATCHUP_ENABLED] = value }

    suspend fun setReplayFromStart(value: Boolean) = edit { it[KEY_REPLAY_FROM_START] = value }

    suspend fun setAspectRatio(value: String) {
        require(value == ASPECT_AUTO || value == ASPECT_16_9 || value == ASPECT_4_3) {
            "aspectRatio must be one of $ASPECT_AUTO / $ASPECT_16_9 / $ASPECT_4_3, was \"$value\""
        }
        edit { it[KEY_ASPECT_RATIO] = value }
    }

    suspend fun setLiveTvOpenOn(value: LiveTvOpenOn) = edit { it[KEY_LIVE_TV_OPEN_ON] = value.stored }

    suspend fun setKeepCategoriesOpen(value: Boolean) = edit { it[KEY_KEEP_CATEGORIES_OPEN] = value }

    suspend fun setBackWhileWatching(value: BackWhileWatching) = edit { it[KEY_BACK_WHILE_WATCHING] = value.stored }

    /** #21: the "Allow sign-in details over unencrypted connections" override. */
    suspend fun setAllowSignInOverUnencrypted(value: Boolean) =
        edit { it[KEY_ALLOW_SIGN_IN_OVER_UNENCRYPTED] = value }

    /** #19: the "Back up favorites and history" switch. */
    suspend fun setBackupHousehold(value: Boolean) = edit { it[KEY_BACKUP_HOUSEHOLD] = value }

    /**
     * #19: the non-secret settings that go into the household backup, only
     * those the user has set (a default is not worth saving). No guide link,
     * no sign-in override, no refresh stamps, no account data.
     */
    suspend fun householdSettingsSnapshot(): HouseholdSettings {
        val p = dataStore.data.first()
        return HouseholdSettings(
            refreshOnStart = p[KEY_REFRESH_ON_START],
            epgRefreshHours = p[KEY_EPG_REFRESH_HOURS],
            epgPastDaysToKeep = p[KEY_EPG_PAST_DAYS_TO_KEEP],
            preferredStreamFormat = p[KEY_PREFERRED_STREAM_FORMAT],
            catchupEnabled = p[KEY_CATCHUP_ENABLED],
            replayFromStart = p[KEY_REPLAY_FROM_START],
            aspectRatio = p[KEY_ASPECT_RATIO],
            liveTvOpenOn = p[KEY_LIVE_TV_OPEN_ON],
            keepCategoriesOpen = p[KEY_KEEP_CATEGORIES_OPEN],
            backWhileWatching = p[KEY_BACK_WHILE_WATCHING],
            lightMode = p[KEY_LIGHT_MODE],
        )
    }

    /** #19: restores [restored] for settings the user has not set yet; never overwrites a set one. Invalid values are skipped. */
    suspend fun importHouseholdSettings(restored: HouseholdSettings) = edit { p ->
        fun <T> fill(key: Preferences.Key<T>, value: T?, valid: Boolean = true) {
            if (value != null && valid && p[key] == null) p[key] = value
        }
        fill(KEY_REFRESH_ON_START, restored.refreshOnStart)
        fill(KEY_EPG_REFRESH_HOURS, restored.epgRefreshHours, (restored.epgRefreshHours ?: 0) > 0)
        fill(KEY_EPG_PAST_DAYS_TO_KEEP, restored.epgPastDaysToKeep, (restored.epgPastDaysToKeep ?: -1) >= 0)
        fill(
            KEY_PREFERRED_STREAM_FORMAT,
            restored.preferredStreamFormat,
            restored.preferredStreamFormat == FORMAT_M3U8 || restored.preferredStreamFormat == FORMAT_TS,
        )
        fill(KEY_CATCHUP_ENABLED, restored.catchupEnabled)
        fill(KEY_REPLAY_FROM_START, restored.replayFromStart)
        fill(
            KEY_ASPECT_RATIO,
            restored.aspectRatio,
            restored.aspectRatio == ASPECT_AUTO || restored.aspectRatio == ASPECT_16_9 || restored.aspectRatio == ASPECT_4_3,
        )
        fill(KEY_LIVE_TV_OPEN_ON, restored.liveTvOpenOn, LiveTvOpenOn.entries.any { it.stored == restored.liveTvOpenOn })
        fill(KEY_KEEP_CATEGORIES_OPEN, restored.keepCategoriesOpen)
        fill(KEY_LIGHT_MODE, restored.lightMode)
        fill(
            KEY_BACK_WHILE_WATCHING,
            restored.backWhileWatching,
            BackWhileWatching.entries.any { it.stored == restored.backWhileWatching },
        )
    }

    /** #126: the dashboard's light/dark switch. */
    suspend fun setLightMode(value: Boolean) = edit { it[KEY_LIGHT_MODE] = value }

    /** Remembers the Guide | List choice (spec #7 §3.6). */
    suspend fun setLiveTvView(value: LiveTvView) = edit { it[KEY_LIVE_TV_VIEW] = value.stored }

    /** Remembers the last category picked in Live TV; null forgets it. */
    suspend fun setLastLiveCategory(value: String?) = edit { prefs ->
        if (value == null) prefs.remove(KEY_LAST_LIVE_CATEGORY) else prefs[KEY_LAST_LIVE_CATEGORY] = value
    }

    /** The last rail category picked on the Movies or Shows screen (#72); null before the first pick. */
    fun lastCatalogCategory(type: com.rysal.happytv.data.entity.StreamType): Flow<String?> =
        dataStore.data.map { it[catalogCategoryKey(type)] }.distinctUntilChanged()

    suspend fun setLastCatalogCategory(type: com.rysal.happytv.data.entity.StreamType, value: String) =
        edit { it[catalogCategoryKey(type)] = value }

    /**
     * design spec §"Settings > About > Reset to defaults": puts every setting **on
     * the settings screen** back to its default, immediately.
     *
     * Deliberately scoped: the account ([KEY_USER_INFO]), the "last refreshed"
     * stamps the dashboard tiles read and the guide's retention window are not
     * user choices on that screen, so they survive a reset.
     */
    suspend fun resetUserSettings() {
        epgOverrideStore.set(null)
        resetUserSettingsKeys()
    }

    private suspend fun resetUserSettingsKeys() = edit { prefs ->
        prefs.remove(KEY_REFRESH_ON_START)
        prefs.remove(KEY_EPG_URL)
        prefs.remove(KEY_EPG_REFRESH_HOURS)
        prefs.remove(KEY_PREFERRED_STREAM_FORMAT)
        prefs.remove(KEY_CATCHUP_ENABLED)
        prefs.remove(KEY_REPLAY_FROM_START)
        prefs.remove(KEY_ASPECT_RATIO)
        prefs.remove(KEY_LIVE_TV_OPEN_ON)
        prefs.remove(KEY_KEEP_CATEGORIES_OPEN)
        prefs.remove(KEY_BACK_WHILE_WATCHING)
        prefs.remove(KEY_ALLOW_SIGN_IN_OVER_UNENCRYPTED)
        prefs.remove(KEY_BACKUP_HOUSEHOLD)
    }

    suspend fun setLastLiveRefreshUtcMillis(value: Long) = edit { it[KEY_LAST_LIVE_REFRESH] = value }

    suspend fun setLastVodRefreshUtcMillis(value: Long) = edit { it[KEY_LAST_VOD_REFRESH] = value }

    suspend fun setLastSeriesRefreshUtcMillis(value: Long) = edit { it[KEY_LAST_SERIES_REFRESH] = value }

    /** #195: the stored [SyncMark] for [type]; [SyncMark.NONE] when nothing is recorded. */
    suspend fun syncMark(type: StreamType): SyncMark =
        SyncMark.fromStored(dataStore.data.first()[syncMarkKey(type)])

    suspend fun setSyncMark(type: StreamType, mark: SyncMark) = edit { prefs ->
        if (mark == SyncMark.NONE) prefs.remove(syncMarkKey(type)) else prefs[syncMarkKey(type)] = mark.stored
    }

    /** Records one successful catalog refresh for the corresponding home tile. */
    suspend fun markRefreshed(type: StreamType, utcMillis: Long = System.currentTimeMillis()) {
        when (type) {
            StreamType.LIVE -> setLastLiveRefreshUtcMillis(utcMillis)
            StreamType.VOD -> setLastVodRefreshUtcMillis(utcMillis)
            StreamType.SERIES -> setLastSeriesRefreshUtcMillis(utcMillis)
        }
    }

    /** Xtream `user_info` JSON bytes; `null` clears the cached copy. */
    suspend fun setUserInfo(value: ByteArray?) = edit { prefs ->
        if (value == null) prefs.remove(KEY_USER_INFO) else prefs[KEY_USER_INFO] = value
    }

    private suspend fun edit(block: suspend (MutablePreferences) -> Unit) = dataStore.edit(block)

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        refreshOnStart = this[KEY_REFRESH_ON_START] ?: true,
        epgUrl = this[KEY_EPG_URL],
        epgRefreshHours = this[KEY_EPG_REFRESH_HOURS] ?: DEFAULT_EPG_REFRESH_HOURS,
        epgPastDaysToKeep = this[KEY_EPG_PAST_DAYS_TO_KEEP] ?: 7,
        preferredStreamFormat = this[KEY_PREFERRED_STREAM_FORMAT] ?: FORMAT_M3U8,
        catchupEnabled = this[KEY_CATCHUP_ENABLED] ?: true,
        replayFromStart = this[KEY_REPLAY_FROM_START] ?: false,
        aspectRatio = this[KEY_ASPECT_RATIO] ?: ASPECT_AUTO,
        liveTvOpenOn = LiveTvOpenOn.fromStored(this[KEY_LIVE_TV_OPEN_ON]),
        keepCategoriesOpen = this[KEY_KEEP_CATEGORIES_OPEN] ?: false,
        backWhileWatching = BackWhileWatching.fromStored(this[KEY_BACK_WHILE_WATCHING]),
        allowSignInOverUnencrypted = this[KEY_ALLOW_SIGN_IN_OVER_UNENCRYPTED] ?: false,
        backupHousehold = this[KEY_BACKUP_HOUSEHOLD] ?: true,
        lastLiveCategory = this[KEY_LAST_LIVE_CATEGORY],
        liveTvView = LiveTvView.fromStored(this[KEY_LIVE_TV_VIEW]),
        lastLiveRefreshUtcMillis = this[KEY_LAST_LIVE_REFRESH] ?: 0L,
        lastVodRefreshUtcMillis = this[KEY_LAST_VOD_REFRESH] ?: 0L,
        lastSeriesRefreshUtcMillis = this[KEY_LAST_SERIES_REFRESH] ?: 0L,
        liveSyncMark = SyncMark.fromStored(this[syncMarkKey(StreamType.LIVE)]),
        vodSyncMark = SyncMark.fromStored(this[syncMarkKey(StreamType.VOD)]),
        seriesSyncMark = SyncMark.fromStored(this[syncMarkKey(StreamType.SERIES)]),
        userInfo = this[KEY_USER_INFO],
        lightMode = this[KEY_LIGHT_MODE] ?: false,
    )

    companion object {
        /** #18: auto mode, derived from saved credentials at fetch time and imported. */
        const val EPG_AUTO = "@auto"

        /** #18: a typed link, stored encrypted; the DataStore holds only this marker. */
        const val EPG_OVERRIDE = "@override"

        const val FORMAT_M3U8 = "m3u8"
        const val FORMAT_TS = "ts"

        /** design spec §"Settings > Guide": "Update the guide every" defaults to 12 hours. */
        const val DEFAULT_EPG_REFRESH_HOURS = 12

        const val ASPECT_AUTO = "auto"
        const val ASPECT_16_9 = "16:9"
        const val ASPECT_4_3 = "4:3"

        private val KEY_REFRESH_ON_START = booleanPreferencesKey("refresh_on_start")
        private val KEY_EPG_URL = stringPreferencesKey("epg_url")
        private val KEY_EPG_REFRESH_HOURS = intPreferencesKey("epg_refresh_hours")
        private val KEY_EPG_PAST_DAYS_TO_KEEP = intPreferencesKey("epg_past_days_to_keep")
        private val KEY_PREFERRED_STREAM_FORMAT = stringPreferencesKey("preferred_stream_format")
        private val KEY_CATCHUP_ENABLED = booleanPreferencesKey("catchup_enabled")
        private val KEY_REPLAY_FROM_START = booleanPreferencesKey("replay_from_start")
        private val KEY_ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        private val KEY_LIVE_TV_OPEN_ON = stringPreferencesKey("live_tv_open_on")
        private val KEY_KEEP_CATEGORIES_OPEN = booleanPreferencesKey("keep_categories_open")
        private val KEY_BACK_WHILE_WATCHING = stringPreferencesKey("back_while_watching")
        private val KEY_ALLOW_SIGN_IN_OVER_UNENCRYPTED = booleanPreferencesKey("allow_sign_in_over_unencrypted")
        private val KEY_BACKUP_HOUSEHOLD = booleanPreferencesKey("backup_household")
        private val KEY_LAST_LIVE_CATEGORY = stringPreferencesKey("last_live_category")
        private fun catalogCategoryKey(type: com.rysal.happytv.data.entity.StreamType) =
            stringPreferencesKey("last_${type.name.lowercase()}_category")
        private val KEY_LIVE_TV_VIEW = stringPreferencesKey("live_tv_view")
        private val KEY_LAST_LIVE_REFRESH = longPreferencesKey("last_live_refresh_utc_millis")
        private val KEY_LAST_VOD_REFRESH = longPreferencesKey("last_vod_refresh_utc_millis")
        private val KEY_LAST_SERIES_REFRESH = longPreferencesKey("last_series_refresh_utc_millis")
        private fun syncMarkKey(type: StreamType) =
            stringPreferencesKey("sync_mark_${type.name.lowercase()}")
        private val KEY_LIGHT_MODE = booleanPreferencesKey("light_mode")
        private val KEY_USER_INFO = byteArrayPreferencesKey("user_info")
    }
}
