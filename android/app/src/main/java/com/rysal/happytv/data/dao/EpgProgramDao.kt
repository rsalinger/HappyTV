package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.EpgProgramStage
import com.rysal.happytv.data.entity.toStage
import kotlinx.coroutines.flow.Flow

/**
 * XMLTV programme cache for one channel.
 *
 * The whole file is parsed and written per refresh, so the write side
 * swaps the table in one transaction; [deleteBefore] trims past days
 * according to the `epgPastDaysToKeep` setting so the table doesn't grow
 * unbounded.
 *
 * **F-65 — why the replace is staged, not inline.** `replaceAllStreamed`
 * used to `deleteAll()` and then insert all 369,366 rows inside one
 * `@Transaction`. On the Chromecast HD that is ~120 MB of WAL written
 * while the database's single write lock is held for *minutes*: every
 * other query in the app blocks behind it, SQLite stops granting pool
 * connections (`unable to grant a connection to thread …  for 270s`),
 * and the UI shows nothing — 0 channels, "Updating…", zero search
 * results — until the COMMIT finally lands.
 *
 * The fix splits the import in two, so the write lock is held only for
 * the cheap part:
 *
 *  1. [stageChunk] writes parsed rows into `epg_programs_stage`, one
 *     transaction per chunk. Untouched by Room observers, so **no
 *     reader is ever blocked here** and the previous guide stays live
 *     and readable throughout.
 *  2. [promoteStaged] runs once, at the end, and (since #43) moves the
 *     staged rows across a batch of channels at a time, each batch its
 *     own short transaction with the live index left in place. F-65's
 *     original single drop-index / copy / rebuild transaction measured
 *     19 to 53 s of database lock on the device.
 *
 * A reader can therefore never observe the empty-table window (every
 * channel is its old guide or its new one), and no transaction holds the
 * write lock for more than one batch.
 *
 * Note the rename-swap alternative (`ALTER TABLE … RENAME TO`) was
 * measured and **rejected**: SQLite carries Room's
 * `room_table_modification_trigger_epg_programs` over to the renamed
 * table, leaving the live table with no invalidation trigger — every
 * `epg_programs` Flow would silently stop re-emitting. The copy keeps
 * those Flows alive.
 */
@Dao
abstract class EpgProgramDao {

    /**
     * The database handle, injected by Room.
     *
     * Kept for DDL access (F-65's promotion used it to drop/rebuild the live
     * index; the batched promotion of #43 no longer needs it): Room's `@Query` processor accepts SELECT/INSERT/UPDATE/DELETE
     * only, so `CREATE INDEX` / `DROP INDEX` have to go through the
     * support database — which Room runs on the current transaction's
     * connection, keeping the index rebuild atomic with the row swap.
     *
     * Room injects this because the constructor takes a [RoomDatabase];
     * tests that need a DAO by hand use the database's own accessor.
     */
    constructor(db: androidx.room.RoomDatabase) {
        this.db = db
    }

    private val db: androidx.room.RoomDatabase

    /**
     * Live window query for the EPG strip: any programme overlapping
     * `[fromUtcMillis, toUtcMillis]`, earliest first.
     */
    @Query(
        "SELECT * FROM epg_programs " +
            "WHERE channelId = :channelId AND endUtcMillis >= :fromUtcMillis AND startUtcMillis <= :toUtcMillis " +
            "ORDER BY startUtcMillis",
    )
    abstract fun observeBetween(
        channelId: String,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): Flow<List<EpgProgram>>

    /** One-shot variant of [observeBetween] for "now/next" badges and tests. */
    @Query(
        "SELECT * FROM epg_programs " +
            "WHERE channelId = :channelId AND endUtcMillis >= :fromUtcMillis AND startUtcMillis <= :toUtcMillis " +
            "ORDER BY startUtcMillis",
    )
    abstract suspend fun getBetween(
        channelId: String,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): List<EpgProgram>

    @Insert
    abstract suspend fun insertAll(programs: List<EpgProgram>)

    @Query("DELETE FROM epg_programs")
    abstract suspend fun deleteAll()

    /** Drop every stored programme for one channel (provider-mode upsert). */
    @Query("DELETE FROM epg_programs WHERE channelId = :channelId")
    abstract suspend fun deleteForChannel(channelId: String)

    /**
     * All channels' programmes overlapping a time range in one call — the
     * guide grid loads every row's block list from this instead of
     * N per-channel queries (keys are the current source's channel keys).
     */
    @Query(
        "SELECT * FROM epg_programs " +
            "WHERE endUtcMillis >= :fromUtcMillis AND startUtcMillis <= :toUtcMillis " +
            "ORDER BY channelId, startUtcMillis",
    )
    abstract suspend fun getBetweenRange(fromUtcMillis: Long, toUtcMillis: Long): List<EpgProgram>

    /** [getBetweenRange] as a Flow — the grid rebuilds when a fetch lands. */
    @Query(
        "SELECT * FROM epg_programs " +
            "WHERE endUtcMillis >= :fromUtcMillis AND startUtcMillis <= :toUtcMillis " +
            "ORDER BY channelId, startUtcMillis",
    )
    abstract fun observeBetweenRange(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<EpgProgram>>

    /**
     * Stored-programme counts per channel, all time — the guide's
     * "has this channel ever had guide info?" signal
     * and the "no guide at all" empty-state signal (zero total rows).
     */
    @Query("SELECT channelId, COUNT(*) AS c FROM epg_programs GROUP BY channelId")
    abstract fun observeChannelCounts(): Flow<List<ChannelProgramCount>>

    /** Drop programmes that ended before [cutoffUtcMillis] (keep-window pruning). */
    @Query("DELETE FROM epg_programs WHERE endUtcMillis < :cutoffUtcMillis")
    abstract suspend fun deleteBefore(cutoffUtcMillis: Long)

    @Query("SELECT COUNT(*) FROM epg_programs")
    abstract suspend fun count(): Int

    // ----------------------------------------------- #10: windowed reads
    //
    // The screens used to read the guide for *every* channel (a full scan of
    // 370k rows) and filter in memory. These reads are scoped to the channel
    // keys a screen actually shows and use the `(channelId, startUtcMillis)`
    // index: one index seek per key, then a short range walk.

    /** Bumps whenever `epg_programs` is written; `0` for an empty table. O(1): MAX(rowid). */
    @Query("SELECT COALESCE(MAX(id), 0) FROM epg_programs")
    abstract fun observeStoreVersion(): Flow<Long>

    /** What is on now for each of [keys] (one row per key at most); no descriptions. */
    @Query(
        "SELECT channelId, title, endUtcMillis FROM epg_programs " +
            "WHERE channelId IN (:keys) AND startUtcMillis <= :nowUtcMillis AND endUtcMillis > :nowUtcMillis",
    )
    abstract suspend fun nowForKeys(keys: List<String>, nowUtcMillis: Long): List<NowProgram>

    /** [nowForKeys] for any number of keys (split to stay under SQLite's variable limit). */
    open suspend fun getNowForChannels(keys: Collection<String>, nowUtcMillis: Long): List<NowProgram> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<NowProgram>(keys.size)
        for (chunk in keys.chunked(KEYS_PER_QUERY)) out += nowForKeys(chunk, nowUtcMillis)
        return out
    }

    /** Programmes of [keys] overlapping `[from, to]`, channel then start order. */
    @Query(
        "SELECT * FROM epg_programs " +
            "WHERE channelId IN (:keys) AND endUtcMillis >= :fromUtcMillis AND startUtcMillis <= :toUtcMillis " +
            "ORDER BY channelId, startUtcMillis",
    )
    abstract suspend fun betweenForKeys(
        keys: List<String>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): List<EpgProgram>

    /** [betweenForKeys] for any number of keys. */
    open suspend fun getBetweenForChannels(
        keys: Collection<String>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): List<EpgProgram> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<EpgProgram>()
        for (chunk in keys.chunked(KEYS_PER_QUERY)) out += betweenForKeys(chunk, fromUtcMillis, toUtcMillis)
        return out
    }

    /** All-time stored-programme counts of [keys] (the guide's "has guide info" signal). */
    @Query("SELECT channelId, COUNT(*) AS c FROM epg_programs WHERE channelId IN (:keys) GROUP BY channelId")
    abstract suspend fun countsForKeys(keys: List<String>): List<ChannelProgramCount>

    /** [countsForKeys] for any number of keys. */
    open suspend fun getCountsForChannels(keys: Collection<String>): List<ChannelProgramCount> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<ChannelProgramCount>(keys.size)
        for (chunk in keys.chunked(KEYS_PER_QUERY)) out += countsForKeys(chunk)
        return out
    }

    /**
     * [betweenForKeys] without the description column (#15): the guide grid draws
     * titles only, and a description per programme multiplies the rows' size for
     * text nobody reads until a program is focused (see [programAt]).
     */
    @Query(
        "SELECT channelId, title, startUtcMillis, endUtcMillis FROM epg_programs " +
            "WHERE channelId IN (:keys) AND endUtcMillis >= :fromUtcMillis AND startUtcMillis <= :toUtcMillis " +
            "ORDER BY channelId, startUtcMillis",
    )
    abstract suspend fun windowForKeys(
        keys: List<String>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): List<WindowProgram>

    /** [windowForKeys] for any number of keys. */
    open suspend fun getWindowForChannels(
        keys: Collection<String>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): List<WindowProgram> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<WindowProgram>()
        for (chunk in keys.chunked(KEYS_PER_QUERY)) out += windowForKeys(chunk, fromUtcMillis, toUtcMillis)
        return out
    }

    /**
     * The one programme of [key] that is on at [atUtcMillis], with its description
     * (#15). A single indexed row: the info band and the details dialog ask for the
     * focused program only, so descriptions never ride along with the batched reads.
     */
    @Query(
        "SELECT * FROM epg_programs " +
            "WHERE channelId = :key AND startUtcMillis <= :atUtcMillis AND endUtcMillis > :atUtcMillis " +
            "ORDER BY startUtcMillis DESC LIMIT 1",
    )
    abstract suspend fun programAt(key: String, atUtcMillis: Long): EpgProgram?

    /** Full replace after an XMLTV parse, atomically (readers never see an empty EPG). */
    @Transaction
    open suspend fun replaceAll(programs: List<EpgProgram>) {
        deleteAll()
        insertAll(programs)
    }

    /**
     * The in-memory replace, kept for tests and small writes.
     *
     * **Not for the XMLTV import** — that path is [stageAllStreamed] +
     * [promoteStaged], which is what keeps the app responsive on a slow
     * disk (F-65). Holding the whole document in memory is still fine
     * for the handful of rows the unit tests build.
     */
    @Transaction
    open suspend fun replaceAllStreamed(programs: Sequence<EpgProgram>) {
        deleteAll()
        val batch = ArrayList<EpgProgram>(INSERT_BATCH_SIZE)
        for (program in programs) {
            batch.add(program)
            if (batch.size >= INSERT_BATCH_SIZE) {
                insertAll(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) insertAll(batch)
    }

    // ------------------------------------------------------- F-65: staging

    @Insert
    abstract suspend fun insertAllStaged(programs: List<EpgProgramStage>)

    /** Empty the staging table (also the first step of every import). */
    @Query("DELETE FROM epg_programs_stage")
    abstract suspend fun clearStaged()

    @Query("SELECT COUNT(*) FROM epg_programs_stage")
    abstract suspend fun stagedCount(): Int

    /**
     * The promotion: moves the staged guide into `epg_programs` in many short
     * transactions, one batch of channels at a time (#43).
     *
     * F-65 did this as a single transaction (drop the index, delete, copy,
     * rebuild the index). Measured on the device that transaction held the
     * database for 19 to 53 s, and everything that needs a write in that
     * window (Room's invalidation-trigger bookkeeping for a newly opened
     * screen, favorites, refresh stamps) waited behind it. A batch here is
     * a few thousand rows (about 0.1 s of write time on the host bench), so
     * the worst wait is one batch.
     *
     * Per batch, in one transaction: delete the old rows of those channels,
     * copy their staged rows in, and drain them from the stage. A channel is
     * therefore always either its old guide or its new one, and a reader never
     * sees the table empty. At the end, channels the new document no longer
     * lists are removed. The live index stays in place throughout and so does
     * Room's invalidation trigger (the reason F-65 rejected the rename swap),
     * so every `epg_programs` Flow re-emits as batches commit.
     *
     * Not atomic across channels: while it runs some channels show the new
     * guide and others the old one. If the process dies half way the stage
     * keeps the remaining channels and the next import clears it.
     *
     * @return the number of programmes now in the live table from this import.
     */
    suspend fun promoteStaged(): Int = try {
        promoteStagedBatches()
    } catch (e: Throwable) {
        // A failed or cancelled promotion leaves some channels on the new guide
        // and the rest on the old one (every channel is still whole, never
        // empty). Drop what is left in the stage so it is not mistaken for a
        // finished import; if the process dies instead, nothing runs here and
        // the stale stage survives until the next import, whose first step
        // clears it and whose promotion rewrites every channel, repairing the mix.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { clearStaged() } }
        throw e
    }

    private suspend fun promoteStagedBatches(): Int {
        val counts = stagedChannelCounts()
        val promoted = HashSet<String>(counts.size * 2)
        var rows = 0
        val batch = ArrayList<String>(PROMOTE_MAX_CHANNELS)
        var batchRows = 0
        suspend fun flush() {
            if (batch.isEmpty()) return
            promoteChannels(batch.toList())
            rows += batchRows
            batch.clear()
            batchRows = 0
            // Let readers and other writers in between batches; the import is
            // background work and nothing waits for it to finish quickly.
            kotlinx.coroutines.delay(PROMOTE_PAUSE_MS)
        }
        for (count in counts) {
            batch.add(count.channelId)
            promoted.add(count.channelId)
            batchRows += count.count
            if (batch.size >= PROMOTE_MAX_CHANNELS || batchRows >= PROMOTE_BATCH_ROWS) flush()
        }
        flush()

        // Channels the new document dropped: remove their old rows.
        val stale = liveChannelIds().filter { it !in promoted }
        for (chunk in stale.chunked(PROMOTE_MAX_CHANNELS)) {
            deleteForChannels(chunk)
        }
        return rows
    }

    /** One promotion batch, atomic: swap these channels' rows old to new and drain them from the stage. */
    @Transaction
    open suspend fun promoteChannels(keys: List<String>) {
        deleteForChannels(keys)
        copyStagedForChannels(keys)
        deleteStagedForChannels(keys)
    }

    @Query("SELECT channelId, COUNT(*) AS c FROM epg_programs_stage GROUP BY channelId")
    abstract suspend fun stagedChannelCounts(): List<ChannelProgramCount>

    @Query("SELECT DISTINCT channelId FROM epg_programs")
    abstract suspend fun liveChannelIds(): List<String>

    @Query("DELETE FROM epg_programs WHERE channelId IN (:keys)")
    abstract suspend fun deleteForChannels(keys: List<String>)

    @Query(
        "INSERT INTO epg_programs (channelId, title, description, startUtcMillis, endUtcMillis) " +
            "SELECT channelId, title, description, startUtcMillis, endUtcMillis FROM epg_programs_stage " +
            "WHERE channelId IN (:keys) ORDER BY channelId, startUtcMillis",
    )
    abstract suspend fun copyStagedForChannels(keys: List<String>)

    @Query("DELETE FROM epg_programs_stage WHERE channelId IN (:keys)")
    abstract suspend fun deleteStagedForChannels(keys: List<String>)

    /**
     * Prepare the staging table: wipe anything a killed run left behind
     * so the next import starts from zero.
     */
    @Transaction
    open suspend fun prepareStaging() {
        clearStaged()
    }

    /**
     * F-65's stage writer — one transaction per chunk of parsed rows.
     *
     * Runs against the staging table, which Room does not observe, so the
     * write lock is never contended by Live TV, Search, the dashboard or
     * the guide grid while a large import is in flight. Peak heap is
     * bounded by [chunk] (default [STAGE_CHUNK_SIZE]), not by the file's
     * programme count.
     *
     * @param programs the caller's parsed rows for this chunk.
     * @return how many rows were written.
     */
    @Transaction
    open suspend fun stageChunk(programs: List<EpgProgram>): Int {
        if (programs.isEmpty()) return 0
        // Split further: one INSERT statement per statement is still
        // 369k statements across the import, and Room's generated INSERT
        // binds 5 variables per row — above SQLite's 999-variable limit a
        // single statement can never carry a full chunk, so batching is
        // a correctness requirement here, not just a speed one.
        var written = 0
        for (i in programs.indices step MAX_ROWS_PER_INSERT) {
            val end = minOf(i + MAX_ROWS_PER_INSERT, programs.size)
            insertAllStaged(programs.subList(i, end).map { it.toStage() })
            written += end - i
        }
        return written
    }

    /**
     * Streams a whole parsed document into the staging table, chunk by
     * chunk, yielding to the caller's dispatcher between chunks.
     *
     * The whole point of F-65: the document is fully parsed and written
     * **before** the live table is touched at all, so a malformed file or
     * a dropped connection costs nothing (F-52's last-good guide stays
     * exactly as it was) and no reader ever waits. Only when this has
     * drained does the caller run [promoteStaged].
     *
     * @return the number of rows staged.
     */
    suspend fun stageAllStreamed(programs: Sequence<EpgProgram>): Int {
        prepareStaging()
        var staged = 0
        val batch = ArrayList<EpgProgram>(STAGE_CHUNK_SIZE)
        for (program in programs) {
            batch.add(program)
            if (batch.size >= STAGE_CHUNK_SIZE) {
                staged += stageChunk(batch)
                batch.clear()
                // Yield between chunks: the parse is CPU-bound and this
                // keeps the caller's dispatcher (and anything else sharing
                // it) responsive on a device that would otherwise run
                // this for minutes.
                kotlinx.coroutines.delay(STAGE_PAUSE_MS)
            }
        }
        if (batch.isNotEmpty()) staged += stageChunk(batch)
        return staged
    }

    companion object {
        /**
         * Keys per `IN (...)` query. SQLite caps host parameters at 999 on older
         * builds (minSdk 23); the windowed reads carry two more binds, so stay well under.
         */
        const val KEYS_PER_QUERY = 400

        /** Rows per `insertAll` call inside [replaceAllStreamed]. */
        const val INSERT_BATCH_SIZE = 500

        /**
         * Rows per staging transaction. Measured on a desktop with 369k
         * rows: 10k/chunk keeps each COMMIT in the low tens of
         * milliseconds while still amortising the transaction overhead
         * across thousands of rows.
         */
        const val STAGE_CHUNK_SIZE = 2_000

        /** Pause after each staging transaction so a waiting writer (trigger sync, favorites) gets the lock. */
        const val STAGE_PAUSE_MS = 20L

        /**
         * Rows per generated INSERT statement. Room binds 5 variables per
         * `epg_programs_stage` row, and SQLite caps a statement at 999
         * host parameters, so anything above ~199 rows in one statement
         * throws at runtime. 199 leaves headroom on older builds that
         * report a lower limit.
         */
        const val MAX_ROWS_PER_INSERT = 199

        /** Target rows per promotion batch ([promoteStaged]); one batch is one write transaction. */
        const val PROMOTE_BATCH_ROWS = 4_000

        /** Channel keys per promotion statement; one bind each, well under SQLite's 999. */
        const val PROMOTE_MAX_CHANNELS = 300

        /** Pause between promotion batches so readers and other writers get the lock. */
        const val PROMOTE_PAUSE_MS = 40L

        /**
         * Room's generated name for [EpgProgram]'s `(channelId,
         * startUtcMillis)` index — see `app/schemas/…/3.json`.
         *
         * [promoteStaged] drops and rebuilds exactly this index around the
         * bulk copy. It is spelled out here rather than derived, because
         * the name is part of the exported schema: renaming the entity or
         * the index without updating this constant would silently leave the
         * guide un-indexed (still correct, just slow).
         */
        const val LIVE_INDEX = "index_epg_programs_channelId_startUtcMillis"
    }
}

/** A row in `SELECT channelId, COUNT(*) … GROUP BY channelId`. */
data class ChannelProgramCount(
    val channelId: String,
    @androidx.room.ColumnInfo(name = "c") val count: Int,
)
/** A row in `SELECT channelId, title, endUtcMillis …` — Live TV's now line, without the description. */
data class NowProgram(
    val channelId: String,
    val title: String,
    val endUtcMillis: Long,
)

/** A row in `SELECT channelId, title, start, end …` — the guide grid's programmes, without descriptions. */
data class WindowProgram(
    val channelId: String,
    val title: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
)
