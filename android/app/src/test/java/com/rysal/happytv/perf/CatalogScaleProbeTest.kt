package com.rysal.happytv.perf

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.guide.GuideChannel
import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.data.guide.GuideWindow
import com.rysal.happytv.data.guide.toGuideProgram
import com.rysal.happytv.ui.guide.GuideDay
import com.rysal.happytv.ui.guide.buildGuideState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Host-side stand-in for the Chromecast measurements: a catalog the size of the
 * real provider's (about 21k channels, about 370k guide rows) in an in-memory
 * Room database, timing the queries and the state builders that sit behind Live
 * TV and the guide. Robolectric's SQLite is faster than the dongle's, so the
 * absolute numbers are *lower bounds*; what matters is which step scales with
 * the whole catalog and which with the visible window.
 *
 * Seeding 370k rows takes a while, so the full size only runs when
 * `HTV_PERF_FULL=1`; otherwise a tenth of the catalog keeps the gate quick
 * while still exercising the code paths.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CatalogScaleProbeTest {

    private lateinit var db: HappyTvDatabase

    private val full = System.getenv("HTV_PERF_FULL") == "1"
    private val channelCount = if (full) 21_500 else 2_150
    private val epgChannels = if (full) 8_000 else 800
    private val programsPerChannel = 46

    private val now = 1_800_000_000_000L // fixed instant; only differences matter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed() {
        val categories = (0 until 380).map { Category(id = "c$it", type = StreamType.LIVE, name = "Category $it") }
        db.categoryDao().replaceAll(StreamType.LIVE, categories)
        val streams = (0 until channelCount).map { i ->
            LiveStream(
                id = "s$i",
                categoryId = "c${i % 380}",
                num = i + 1,
                name = "Channel $i",
                epgChannelId = if (i % (channelCount / epgChannels) == 0 && i / (channelCount / epgChannels) < epgChannels) {
                    "epg.${i / (channelCount / epgChannels)}"
                } else {
                    null
                },
                tvArchive = i % 5 == 0,
            )
        }
        db.liveStreamDao().replaceAll(streams)
        val dao = db.epgProgramDao()
        val base = now - 36 * HOUR
        db.withTransaction {
            for (e in 0 until epgChannels) {
                val batch = ArrayList<EpgProgram>(programsPerChannel)
                for (p in 0 until programsPerChannel) {
                    val start = base + p * 90 * 60_000L
                    batch += EpgProgram(
                        channelId = "epg.$e",
                        title = "Programme $e-$p",
                        description = "A description of roughly one hundred and twenty characters, as the provider sends them. " +
                            "Episode $p.",
                        startUtcMillis = start,
                        endUtcMillis = start + 90 * 60_000L,
                    )
                    if (batch.size == 199) {
                        dao.insertAll(batch.toList())
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) dao.insertAll(batch)
            }
        }
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val runs = ArrayList<Long>()
        var result: T? = null
        repeat(3) {
            val t0 = System.nanoTime()
            result = block()
            runs += (System.nanoTime() - t0) / 1_000_000L
        }
        println("PROBE ${if (full) "full" else "tenth"} $label median=${runs.sorted()[1]}ms runs=$runs")
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun plan(sql: String): String {
        val c = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql")
        val out = StringBuilder()
        while (c.moveToNext()) out.append(c.getString(c.getColumnIndexOrThrow("detail"))).append(" | ")
        c.close()
        return out.toString()
    }

    @Test
    fun probeBaselineQueries() = runBlocking {
        val epg = db.epgProgramDao()
        val live = db.liveStreamDao()
        val window = GuideWindow.fromNow(now, java.time.ZoneId.of("UTC"), 0)

        println("PROBE plan observeNow(old): " + plan("SELECT * FROM epg_programs WHERE startUtcMillis <= $now AND endUtcMillis > $now ORDER BY startUtcMillis"))
        println("PROBE plan nowForKeys(new): " + plan("SELECT channelId, title, endUtcMillis FROM epg_programs WHERE channelId IN ('epg.1','epg.2') AND startUtcMillis <= $now AND endUtcMillis > $now"))

        val range = timed("epg.observeBetweenRange (all channels, 3h)") {
            runBlocking { epg.observeBetweenRange(window.startUtcMillis, window.endUtcMillis).first() }
        }
        println("PROBE rows range=${range.size}")
        println("PROBE plan range: " + plan("SELECT * FROM epg_programs WHERE endUtcMillis >= ${window.startUtcMillis} AND startUtcMillis <= ${window.endUtcMillis} ORDER BY channelId, startUtcMillis"))

        timed("epg.observeChannelCounts") { runBlocking { epg.observeChannelCounts().first() } }
        timed("live.observeCounts") { runBlocking { live.observeCounts().first() } }
        timed("live.observeAll (21k entities)") { runBlocking { live.observeAll().first() } }
        timed("live.observeAllWindow(240)") { runBlocking { live.observeAllWindow(240).first() } }

        // The guide's per-rebuild cost: every channel row built on each key press.
        val all = runBlocking { live.observeAll().first() }
        val channels = all.map {
            GuideChannel(
                streamId = it.id, num = it.num, name = it.name, genre = null, isFavorite = false,
                hasReplay = it.tvArchive, epgChannelId = it.epgChannelId, categoryId = it.categoryId,
                categoryName = null,
            )
        }
        val programs: Map<String, List<GuideProgram>> = range.groupBy { it.channelId }
            .mapValues { (_, l) -> l.map { p -> p.toGuideProgram() } }
        val counts = runBlocking { epg.observeChannelCounts().first() }.associate { it.channelId to it.count }
        timed("buildGuideState (all ${channels.size} channels)") {
            buildGuideState(
                channels = channels, programsByKey = programs, guideCounts = counts, xmltvMode = true,
                day = GuideDay.TODAY,
                nowUtcMillis = now, zone = java.time.ZoneId.of("UTC"),
            )
        }
        Unit
    }

    /** The same screens' reads after #10: scoped to the visible window's keys. */
    @Test
    fun probeWindowedQueries() = runBlocking {
        val epg = db.epgProgramDao()
        val live = db.liveStreamDao()
        val zone = java.time.ZoneId.of("UTC")
        val window = GuideWindow.fromNow(now, zone, 0)

        val page = runBlocking { live.observeAllWindow(240).first() }
        // XMLTV mode keys (the real provider's), one per channel that has one.
        val liveTvKeys = page.mapNotNull { it.epgChannelId }
        val nowFound = timed("epg.getNowForChannels (Live TV first page, ${liveTvKeys.size} keys)") {
            runBlocking { epg.getNowForChannels(liveTvKeys, now) }
        }
        println("PROBE rows nowFound=${nowFound.size}")

        val guidePage = runBlocking { live.observeAllWindow(120).first() }
        val guideKeys = guidePage.mapNotNull { it.epgChannelId }
        val range = timed("epg.getBetweenForChannels (guide window, ${guideKeys.size} keys, 3h)") {
            runBlocking { epg.getBetweenForChannels(guideKeys, window.startUtcMillis, window.endUtcMillis) }
        }
        println("PROBE rows rangeWindow=${range.size}")
        val counts = timed("epg.getCountsForChannels (guide window)") {
            runBlocking { epg.getCountsForChannels(guideKeys) }
        }.associate { it.channelId to it.count }
        timed("epg.observeStoreVersion") { runBlocking { epg.observeStoreVersion().first() } }

        val channels = guidePage.map {
            GuideChannel(
                streamId = it.id, num = it.num, name = it.name, genre = null, isFavorite = false,
                hasReplay = it.tvArchive, epgChannelId = it.epgChannelId, categoryId = it.categoryId,
                categoryName = null,
            )
        }
        val programs: Map<String, List<GuideProgram>> = range.groupBy { it.channelId }
            .mapValues { (_, l) -> l.map { p -> p.toGuideProgram() } }
        timed("buildGuideState (window of ${channels.size} channels)") {
            buildGuideState(
                channels = channels, programsByKey = programs, guideCounts = counts, xmltvMode = true,
                day = GuideDay.TODAY,
                nowUtcMillis = now, zone = zone,
            )
        }
        Unit
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
