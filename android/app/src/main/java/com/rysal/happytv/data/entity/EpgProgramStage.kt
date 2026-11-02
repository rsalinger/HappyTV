package com.rysal.happytv.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One parsed XMLTV `<programme>` row waiting to be promoted into
 * [EpgProgram] — F-65's staging table.
 *
 * **Why this table exists.** The import used to `deleteAll()` and then
 * write all 369k rows inside ONE Room transaction. On a Chromecast HD's
 * eMMC that is a ~120 MB WAL written under a write lock held for
 * minutes: every other query in the app waits, the SQLite connection
 * pool can't grant a connection (`SQLiteConnectionPool: … unable to
 * grant`), and Live TV / Search / the dashboard tile all sit empty until
 * the COMMIT lands.
 *
 * Parsing into this table first splits the work in two:
 *
 *  1. **[EpgProgramDao.stageChunk] — chunked, each its own
 *     transaction.** No live-table lock, so readers are served
 *     normally from the *previous* guide the whole time.
 *  2. **[EpgProgramDao.promoteStaged] — one short transaction** that
 *     empties `epg_programs`, copies the staged rows in and rebuilds the
 *     index. This is the only moment the live table is touched, and it
 *     is the only moment readers have to wait.
 *
 * The two-table shape also keeps F-52's last-good guarantee intact: a
 * malformed document or a dropped connection aborts during phase 1,
 * where the guide the user was watching is still untouched and simply
 * never promoted.
 *
 * Shape is deliberately identical to [EpgProgram] so the promotion is
 * a column-for-column `INSERT … SELECT` with no mapping layer.
 */
@Entity(
    tableName = "epg_programs_stage",
    // v4 (#43): the chunked promotion reads and drains the stage a batch of
    // channels at a time, so it needs a channelId index (see promoteStaged).
    indices = [androidx.room.Index("channelId")],
)
data class EpgProgramStage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: String,
    val title: String,
    val description: String? = null,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
)

/** [EpgProgram] → its staged twin (see [EpgProgramStage]). */
fun EpgProgram.toStage(): EpgProgramStage = EpgProgramStage(
    channelId = channelId,
    title = title,
    description = description,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
)