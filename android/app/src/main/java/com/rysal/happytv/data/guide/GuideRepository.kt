package com.rysal.happytv.data.guide

import android.util.Log
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.dao.CategoryDao
import com.rysal.happytv.data.dao.EpgProgramDao
import com.rysal.happytv.data.dao.FavoriteDao
import com.rysal.happytv.data.dao.LiveStreamDao
import com.rysal.happytv.data.dao.RecentChannelDao
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.network.EpgListingDto
import com.rysal.happytv.data.network.JsonCoerce
import com.rysal.happytv.data.network.XtreamRepository
import com.rysal.happytv.data.network.decodeBase64IfEncoded
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The guide's data access — one façade over both guide sources
 * (task body §1):
 *
 *  - **Provider-side**: Xtream `get_short_epg&stream_id=…` per channel
 *    (`limit=4` for the cold-start "now + next", larger for the
 *    on-demand 4-hour fetch), stored under the channel's `stream_id`.
 *  - **XMLTV** (auto-enabled when `settings.epgUrl` is set, or
 *    derivable from the saved Xtream credentials per F-47/F-52):
 *    full file download with a last-modified check
 *    ([HttpXmlTvFetcher]), parsed by [XmlTvParser], written with
 *    `EpgProgramDao.replaceAll` (`@Transaction`), stored under the
 *    programme's `channel` (= `epg_channel_id`).
 *
 * Both live in the pre-existing `epg_programs` table — same table, no
 * migration (task: "table name unchanged to avoid migration"). The key
 * spaces are disjoint (numeric stream ids vs dotted XMLTV ids) and the
 * sources are mutually exclusive (Settings → Guide source), so one table
 * serves both; the grid looks each channel up by its mode's key only.
 */
class GuideRepository(
    private val epgDao: EpgProgramDao,
    private val liveDao: LiveStreamDao,
    private val categoryDao: CategoryDao,
    private val favoriteDao: FavoriteDao,
    private val recentDao: RecentChannelDao,
    private val settings: SettingsRepository,
    private val session: suspend () -> XtreamRepository?,
    private val xmlTvFetcher: HttpXmlTvFetcher,
    /**
     * Optional credentials supplier. The XMLTV worker reads the saved
     * Xtream credentials ([com.rysal.happytv.security.SecuredCredentialsStore])
     * to derive the panel's `xmltv.php` URL when the user has not typed
     * one (F-47 + F-52). Inject `null` for tests that don't need that
     * path — the supplier is only consulted on the cache-miss branch.
     */
    private val credentials: (suspend () -> com.rysal.happytv.security.Credentials?)? = null,
    /**
     * Where the XMLTV parse + stage + promote runs (#43). Production passes the
     * low-priority [com.rysal.happytv.data.BackgroundWork.bulk] pool so the
     * minute-long parse cannot starve the UI thread; tests keep the default.
     */
    private val bulkDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * F-65: serializes XMLTV imports within this process.
     *
     * Two callers kick a guide refresh within seconds of a sign-in —
     * `RefreshGuideScheduler.kickPostSignIn` on app start and
     * `LoginActivity`'s UI effect (plus `RefreshGuideWorker` when the
     * periodic schedule fires). Left alone, each runs a full 369k-row
     * import back to back, so the app is busy for twice as long as it
     * needs to be and QA's logcat showed the store rewritten twice in six
     * minutes (15:40:07, then 15:46:01).
     *
     * Holding this across download → stage → promote means a second caller
     * simply waits and then finds the store already warm.
     */
    private val importMutex = Mutex()

    /**
     * True when an XMLTV import was promoted within [maxAgeMs] and the store still
     * holds rows. Cold start uses this to skip a full re-download, parse and
     * promotion (the promotion's index rebuild blocks every other database read for
     * 30 s or more on the Chromecast HD, #35).
     */
    suspend fun isGuideFresh(maxAgeMs: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        isGuideReusable(xmlTvFetcher.lastImportedAtMillis(), epgDao.count(), maxAgeMs, nowMillis)

    /**
     * Forget the stored conditional-GET validators so the next XMLTV import
     * downloads the file again instead of accepting a 304 (#48: "Refresh
     * everything now" keeps the old guide in place until the new one promotes).
     */
    fun forceNextDownload() = xmlTvFetcher.clearValidators()

    /** Is XMLTV the chosen guide source? (Default: provider-side only.) */
    suspend fun isXmltvEnabled(): Boolean = isXmltvGuideEnabled(settings.settings.first().epgUrl)

    // ----------------------------------------------------------------- rows

    /**
     * The guide's channel rows for the rail's selection (#10): [categoryId] (null
     * = All channels) in catalog order, the first [limit] rows only. The guide used
     * to hydrate and map every channel of the catalog (21k on the real provider) on
     * each favorite toggle; now it reads one window. Each row carries its genre
     * (resolved from the provider's live-category name), the favorite flag and the
     * category the rail filters and labels by (F-44).
     */
    fun observeChannels(categoryId: String? = null, limit: Int = Int.MAX_VALUE): Flow<List<GuideChannel>> = combine(
        if (categoryId == null) liveDao.observeAllWindow(limit) else liveDao.observeByCategoryWindow(categoryId, limit),
        categoryDao.observeAll(StreamType.LIVE),
        favoriteDao.observeForType(StreamType.LIVE),
    ) { channels, categories, favorites ->
        val byCategoryId = categories.associateBy { it.id }
        val favoriteIds = favorites.mapTo(HashSet()) { it.streamId }
        channels.map { stream ->
            val category = byCategoryId[stream.categoryId]
            GuideChannel(
                streamId = stream.id,
                num = stream.num,
                name = stream.name,
                genre = category?.let { knownGenre(it.name) },
                isFavorite = stream.id in favoriteIds,
                hasReplay = stream.tvArchive,
                epgChannelId = stream.epgChannelId,
                categoryId = stream.categoryId,
                categoryName = category?.name,
                logoUrl = stream.streamIcon,
            )
        }
    }

    /** `{categoryId → channel count}` across the LIVE catalog: the rail's counts, without hydrating channels. */
    fun observeLiveCategoryCounts(): Flow<Map<String, Int>> =
        liveDao.observeCounts().map { rows -> rows.associate { it.categoryId to it.count } }

    /**
     * The visible LIVE categories (`isHidden = 0`) — the guide rail's row
     * source (F-44). Hidden categories vanish from here, which is the same
     * list Live TV's rail reads, so one hide empties both rails.
     */
    fun observeLiveCategories(): Flow<List<Category>> =
        categoryDao.observeVisible(StreamType.LIVE)

    /**
     * Long-press "Hide category" on the guide rail: flips the durable hidden
     * flag through the same [CategoryDao.toggleHidden] the other screens use,
     * so the category leaves the Live TV and Movies/Shows rails too.
     */
    suspend fun toggleCategoryHidden(categoryId: String) {
        categoryDao.toggleHidden(categoryId, StreamType.LIVE)
    }

    /**
     * Programmes overlapping the window for [keys] only (the grid's channels),
     * grouped by store key. Re-queried when the guide store is written, so a fetch
     * landing still rebuilds the grid; reads use the `(channelId, start)` index
     * instead of scanning every channel's programmes.
     */
    fun observeWindowPrograms(
        keys: Collection<String>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): Flow<Map<String, List<GuideProgram>>> =
        epgDao.observeStoreVersion().distinctUntilChanged().map {
            // Titles and times only (#15): the focused program's description is one
            // single-row read, [programAt], not part of every window.
            epgDao.getWindowForChannels(keys, fromUtcMillis, toUtcMillis)
                .groupBy { it.channelId }
                .mapValues { (_, list) ->
                    list.map {
                        GuideProgram(
                            channelId = it.channelId,
                            title = it.title,
                            description = null,
                            startUtcMillis = it.startUtcMillis,
                            endUtcMillis = it.endUtcMillis,
                        )
                    }
                }
        }

    /**
     * The one programme of [key] on at [atUtcMillis], with its description (#15), or
     * null. Runs on the IO dispatcher; callers debounce it on focus changes.
     */
    suspend fun programAt(key: String, atUtcMillis: Long): GuideProgram? =
        withContext(Dispatchers.IO) { epgDao.programAt(key, atUtcMillis)?.toGuideProgram() }

    /** Per-channel "has ever had guide rows" counts for [keys], for No guide info vs data. */
    fun observeGuideCounts(keys: Collection<String>): Flow<Map<String, Int>> =
        epgDao.observeStoreVersion().distinctUntilChanged().map {
            epgDao.getCountsForChannels(keys).associate { it.channelId to it.count }
        }

    /**
     * One window of programmes **and** the "has guide info" counts, behind one store-version
     * observer (#52). The counts used to be a second full `COUNT(*) GROUP BY` over every stored
     * programme of the shown channels (66 to 132 ms on the real 370k-row store), run next to
     * the window read; the grid only asks "any programme at all?". A channel with a programme in
     * the window has some by definition, so only the channels with none in the window are
     * counted, and those have few or no rows to scan. Counts of channels with programmes are the
     * window's own sizes (a lower bound; callers only test for > 0).
     */
    fun observeWindowWithCounts(
        keys: Collection<String>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): Flow<Pair<Map<String, List<GuideProgram>>, Map<String, Int>>> =
        epgDao.observeStoreVersion().distinctUntilChanged().map {
            val programs = epgDao.getWindowForChannels(keys, fromUtcMillis, toUtcMillis)
                .groupBy { it.channelId }
                .mapValues { (_, list) ->
                    list.map {
                        GuideProgram(
                            channelId = it.channelId,
                            title = it.title,
                            description = null,
                            startUtcMillis = it.startUtcMillis,
                            endUtcMillis = it.endUtcMillis,
                        )
                    }
                }
            val counts = HashMap<String, Int>(keys.size * 2)
            programs.forEach { (key, list) -> counts[key] = list.size }
            val missing = keys.filterNot { it in programs }
            if (missing.isNotEmpty()) {
                epgDao.getCountsForChannels(missing).forEach { counts[it.channelId] = it.count }
            }
            programs to counts
        }

    /** True while the store holds at least one programme of any channel/time (O(1)). */
    fun observeHasAnyGuide(): Flow<Boolean> = epgDao.observeStoreVersion().map { it > 0L }

    /** True when the store holds at least one programme of any channel/time. */
    suspend fun hasAnyGuide(): Boolean = observeHasAnyGuide().first()

    // ------------------------------------------------------------- provider

    /**
     * Shallow listing ("now + next", [limit] entries) for one channel —
     * the cold-start/visible-row fetch.
     *
     * Returns **null when there is no signed-in session** (nobody to ask),
     * which callers must treat as "do not touch the store". An **empty
     * list** is different: the provider answered and sent nothing, so the
     * channel really has no guide right now and its rows should go.
     * (Conflating the two made every signed-out guide open delete the
     * stored rows the user still had from their last session.)
     */
    suspend fun fetchProviderListings(streamId: String, limit: Int): List<GuideProgram>? {
        val repo = session() ?: return null
        return repo.getShortEpg(streamId, limit).mapNotNull { it.toGuideProgram(streamId) }
    }

    /**
     * Writes (upserts) one channel's provider listings: previous rows for
     * that channel are replaced so re-fetches never duplicate and a
     * channel whose guide vanished ends up empty ("No guide info").
     */
    suspend fun storeProviderPrograms(streamId: String, programs: List<GuideProgram>) {
        epgDao.deleteForChannel(streamId)
        if (programs.isNotEmpty()) {
            epgDao.insertAll(programs.map { it.copy(channelId = streamId).toEntity() })
        }
    }

    /**
     * Worker path when XMLTV is off (§6): refresh the guide for the
     * user's favorites + recently watched channels, bounded to
     * [maxChannels] per run. Each channel fails independently — one
     * flaky listing doesn't abort the refresh.
     *
     * F-47: on a brand-new sign-in the user has neither favorites nor
     * recently-watched channels yet, so the union would be empty and
     * the Live TV rail would still say "No guide info" on every row.
     * To avoid that dead-start the run **tops up to [maxChannels]**:
     * favorites + recents first (the channels the user demonstrably
     * cares about), then the catalog head sorted by `num` so every
     * worker pass leaves *some* now-line visible on the rail — and
     * stops after 25 channels so the budget never regresses.
     *
     * Returns how many channels were refreshed.
     */
    suspend fun refreshProviderGuides(maxChannels: Int = PROVIDER_REFRESH_MAX_CHANNELS): Int {
        val favoriteIds = favoriteDao.observeForType(StreamType.LIVE).first().map { it.streamId }
        val recentIds = recentDao.recent().first().map { it.streamId }
        val catalog = liveDao.observeAll().first()
        val catalogIds = catalog.mapTo(HashSet()) { it.id }
        val preferred = (favoriteIds + recentIds)
            .distinct()
            .filter { it in catalogIds }
        // Catalog head (by `num`, then by id for ties) is the fallback
        // when the user has no favorites / recent yet — a stable order
        // across runs so the same rows keep warming up. `num` is
        // Int?-typed in the entity and nullable on the wire, so absent
        // `num` sorts last with the id as a tiebreaker.
        val catalogHead = catalog.sortedWith(
            compareBy(
                { it.num ?: Int.MAX_VALUE },
                { it.id },
            ),
        ).map { it.id }
        // Preferred first (the user's actual picks), then top up from the
        // catalog head up to the cap. This is what fills the Live TV rail
        // on a fresh sign-in AND keeps the user's picks first as before.
        val preferredSet = preferred.toHashSet()
        val targets = (preferred + catalogHead.filterNot { it in preferredSet })
            .take(maxChannels)

        var refreshed = 0
        for (streamId in targets) {
            try {
                // null = signed out (nothing to ask): skip without touching
                // the store; the worker only runs for a signed-in user anyway.
                val listings = fetchProviderListings(streamId, PROVIDER_REFRESH_LIMIT) ?: continue
                storeProviderPrograms(streamId, listings)
                refreshed++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "guide refresh failed for channel $streamId: ${e.message}")
            }
        }
        return refreshed
    }

    // ---------------------------------------------------------------- XMLTV

    /**
     * Worker path when XMLTV is on (§6): download (with the last-modified
     * check and F-52's size guard) into a staged temp file, parse it into
     * `epg_programs_stage` in chunks (F-65), promote it into the live store
     * in one short transaction and trim to the `epgPastDaysToKeep` window.
     * The whole body runs on [Dispatchers.IO] — the multi-MB parse must
     * never touch the main thread, whichever caller starts it (the worker,
     * or GuideViewModel's first-open kick).
     *
     * F-47 / F-52: when no `settings.epgUrl` is stored at all —
     * the default on a fresh install — the panel's XMLTV is still available at
     * `{scheme}://{host}/xmltv.php?username=…&password=…` (Xtream §2.1).
     * We derive that URL from the saved Xtream credentials and use it on
     * the cache-miss path; on success the mode is **persisted as**
     * the `EPG_AUTO` marker (#18: the URL itself is never stored, it is
     * rebuilt from the encrypted credentials on every fetch) so the
     * `isXmltvEnabled` check sees XMLTV as active. The Settings UI can
     * still set a typed override (kept encrypted) for non-standard panels.
     *
     * The stored value is a three-state contract (F-52):
     *  - **absent (`null`)** — auto: derive from the saved credentials.
     *  - **empty (`""`)** — the user chose "From provider" in Settings;
     *    derivation must not silently undo that choice.
     *  - **a URL** — the user's override for a non-standard panel; always
     *    wins over derivation.
     *
     * Last-good guarantee (F-52): nothing touches `epg_programs` until a
     * complete, parseable file is staged on disk **and** fully parsed into
     * the staging table; only then does one atomic promotion replace the
     * live guide — so a dropped connection, a truncated body or malformed
     * XML all leave the guide the user was watching exactly as it was.
     *
     * @return true when the store was rewritten; false when there was no
     * URL configured (or derivable), or the server said "not modified".
     */
    suspend fun fetchAndStoreXmlTv(): Boolean = withContext(Dispatchers.IO) {
        // F-65: one import at a time, per process. QA's logcat showed the
        // sign-in kick and `RefreshGuideWorker` both driving a 369k-row
        // import seconds apart (15:40:07 and 15:46:01), so the app paid the
        // full cost twice and was never free while either ran. The mutex
        // collapses concurrent callers into one import; the second one then
        // sees the warm store and returns quickly (the downloader's
        // conditional GET answers 304, or the stage is already current).
        importMutex.withLock { fetchAndStoreXmlTvLocked() }
    }

    /**
     * [fetchAndStoreXmlTv]'s body, minus the gate. Kept separate so the
     * mutex wraps the whole "download + parse + promote" sequence rather
     * than just the promotion.
     */
    private suspend fun fetchAndStoreXmlTvLocked(): Boolean = withContext(bulkDispatcher) {
        val storedUrl = settings.settings.first().epgUrl
        var url = ""
        var derivedThisPass = false
        // #18: the stored value is a mode marker, never a URL carrying the
        // password. Auto derives the URL from the encrypted credentials now,
        // and a typed link comes from the encrypted store.
        suspend fun deriveFromCredentials(): String? = credentials?.let { supplier ->
            XmlTvUrlDeriver.derive(supplier())
        }?.takeIf { it.isNotBlank() }
        when {
            storedUrl == null || storedUrl == SettingsRepository.EPG_AUTO -> {
                url = deriveFromCredentials() ?: return@withContext false
                // Only an import that actually wrote rows flips a pending
                // auto (null) to the active marker, so a failed first fetch
                // never leaves XMLTV "enabled" with an empty store.
                derivedThisPass = storedUrl == null
            }
            storedUrl.isEmpty() -> {
                // EXPLICIT "From provider" (stored as ""): honour it.
                return@withContext false
            }
            storedUrl == SettingsRepository.EPG_OVERRIDE -> {
                // A marker whose encrypted link is gone (signed out, wiped)
                // falls back to auto instead of fetching nothing.
                url = settings.epgOverrideUrl() ?: deriveFromCredentials() ?: return@withContext false
            }
            else -> url = storedUrl.trim() // pre-#18 raw URL awaiting migration
        }

        // Never trust a 304 while the store is empty (a parse failure after
        // a 200 could otherwise wedge us on "not modified" forever).
        if (epgDao.count() == 0) xmlTvFetcher.clearValidators()

        // F-52: stage the size-guarded download on disk first — a dropped
        // connection mid-body must not wipe the last-good guide — then
        // stream-parse from the file (reusing XmlTvParser's streaming walk).
        val staged = xmlTvFetcher.stageIfModified(url) ?: return@withContext false

        // F-65: parse into the staging table in chunks, then promote in one
        // short transaction. Until the promotion commits, `epg_programs`
        // still holds the previous guide and every reader (Live TV, Search,
        // the dashboard tile, the guide grid) is served normally — the write
        // lock is never held for the length of the whole import.
        val importStartedAt = System.currentTimeMillis()
        var totalStored = 0
        try {
            staged.inputStream().use { stream ->
                totalStored = epgDao.stageAllStreamed(XmlTvParser.parseSequence(stream))
            }
        } catch (e: CancellationException) {
            // Half a stage table is worth nothing: drop it so the next run
            // starts clean and the file doesn't carry two guides.
            runCatching { epgDao.clearStaged() }
            throw e
        } catch (e: Exception) {
            runCatching { epgDao.clearStaged() }
            Log.w(
                TAG,
                "XMLTV import failed after ${System.currentTimeMillis() - importStartedAt} ms " +
                    "(staged so far: $totalStored rows, live guide untouched) — " +
                    "${e.message ?: e::class.java.simpleName}",
            )
            throw e
        } finally {
            staged.delete()
        }

        // The one moment `epg_programs` is written. Everything above could
        // fail without the user ever noticing; promotion is per-channel atomic, so a reader
        // sees each channel as its old guide or its new one, never empty.
        val promoteStartedAt = System.currentTimeMillis()
        epgDao.promoteStaged()
        val promoteMs = System.currentTimeMillis() - promoteStartedAt
        xmlTvFetcher.markImported(System.currentTimeMillis())
        // Not a blocking window any more (#43): promotion runs in short batches.
        com.rysal.happytv.perf.HtvPerf.log("guide.import.promote", promoteMs, "rows=$totalStored")
        val totalMs = System.currentTimeMillis() - importStartedAt

        // Persist the derived URL only now that we've actually written rows —
        // this is what makes the "guide loads from credentials alone, no
        // separate entry" promise user-visible (F-52) without risking the
        // empty-store wedge above. Subsequent reads (Settings UI, the
        // `isXmltvEnabled` check, the cached-If-Modified validator) all see
        // one consistent value. A user-typed URL is never touched by this
        // path — derivation only kicks in when nothing is stored.
        if (derivedThisPass) settings.setEpgUrl(SettingsRepository.EPG_AUTO)

        val pastDays = settings.settings.first().epgPastDaysToKeep
        val cutoff = Instant.now().minus(pastDays.toLong(), ChronoUnit.DAYS).toEpochMilli()
        val trimStartedAt = System.currentTimeMillis()
        epgDao.deleteBefore(cutoff)
        val trimMs = System.currentTimeMillis() - trimStartedAt
        // F-65 asks for the import duration in logcat so a regression is
        // visible without a debugger: `promoteMs` is the only window that
        // blocks readers, and it is the number that must stay small.
        Log.i(
            TAG,
            "XMLTV stored: $totalStored programmes (keeping $pastDays past days) — " +
                "total ${totalMs}ms (stage ${totalMs - promoteMs - trimMs}ms, " +
                "promote ${promoteMs}ms, trim ${trimMs}ms)",
        )
        true
    }

    // ------------------------------------------------------------- plumbing

    /** Short-EPG entry → store row under [streamId] (see [EpgListingDto]). */
    private fun EpgListingDto.toGuideProgram(streamId: String): GuideProgram? {
        val start = JsonCoerce.toLongOrNull(start_timestamp)?.let { it * 1000 } ?: return null
        val end = JsonCoerce.toLongOrNull(stop_timestamp)?.let { it * 1000 } ?: return null
        if (end < start) return null
        return GuideProgram(
            channelId = streamId,
            title = title.decodeBase64IfEncoded().orEmpty(),
            description = description.decodeBase64IfEncoded()?.takeIf { it.isNotBlank() },
            startUtcMillis = start,
            endUtcMillis = end,
        )
    }

    companion object {
        private const val TAG = "GuideRepository"

        /** Task §6: provider-side worker refresh is bounded per run. */
        const val PROVIDER_REFRESH_MAX_CHANNELS = 25

        /**
         * Listings to pull per channel. 4 = the spec's "now + next" shallow
         * fetch; the worker asks for more so a warmed row covers the whole
         * three-hour window (4 hours ahead of the window's mid-point even
         * when programs are short).
         */
        const val PROVIDER_REFRESH_LIMIT = 16
    }
}

/**
 * The cold-start reuse rule (#35): a guide imported at [lastImportedAt] (0 = never)
 * that still has [rowCount] rows is reused while it is younger than [maxAgeMs].
 * A clock that moved backwards (a stamp in the future) counts as stale.
 */
internal fun isGuideReusable(lastImportedAt: Long, rowCount: Int, maxAgeMs: Long, nowMillis: Long): Boolean {
    if (lastImportedAt <= 0L || rowCount <= 0) return false
    val age = nowMillis - lastImportedAt
    return age in 0..maxAgeMs
}
