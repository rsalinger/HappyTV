package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.dao.EpgProgramDao
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #43 — a catalog refresh and a guide import must not hold the database.
 *
 * Catalog: the old refresh deleted and re-inserted every live row (about 21k)
 * in one transaction on every start, invalidating every `live_streams`
 * observer for the whole write. The sync now writes only what differs, so a
 * refresh that finds nothing new writes nothing and wakes nobody.
 *
 * Guide: F-65's promotion was one transaction (drop index, copy 370k rows,
 * rebuild index) that measured 19 to 53 s on the device. It now moves the
 * staged rows a batch of channels at a time; F-65's invariants (the live table
 * is never empty, Room's invalidation trigger stays attached) must still hold.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CatalogSyncAndPromotionTest {

    private lateinit var db: HappyTvDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HappyTvDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun channel(i: Int, name: String = "Channel $i") =
        LiveStream(id = "s$i", categoryId = "c${i % 5}", num = i, name = name)

    // ----------------------------------------------------------- catalog

    @Test
    fun `syncing an identical live list writes nothing and wakes no observer`() = runBlocking {
        val dao = db.liveStreamDao()
        val list = (1..250).map { channel(it) }
        val first = dao.syncAll(list)
        assertEquals(250, first.upserted)

        val emissions = AtomicInteger(0)
        val collector = launch(Dispatchers.IO) {
            dao.observeAll().collect { emissions.incrementAndGet() }
        }
        withTimeout(5_000) { while (emissions.get() < 1) delay(10) }

        val second = dao.syncAll(list.map { it.copy() })
        delay(400) // an invalidation would have re-emitted by now
        collector.cancel()

        assertEquals(0, second.upserted)
        assertEquals(0, second.removed)
        assertEquals(250, second.unchanged)
        assertFalse("nothing changed, nothing was written", second.touched)
        assertEquals("the observer must not re-run for an unchanged refresh", 1, emissions.get())
    }

    @Test
    fun `syncing writes only changed rows and drops rows the provider removed`() = runBlocking {
        val dao = db.liveStreamDao()
        dao.syncAll((1..100).map { channel(it) })

        val next = (1..100).filter { it != 7 && it != 8 }.map {
            if (it == 3) channel(3, name = "Renamed") else channel(it)
        } + channel(101)
        val write = dao.syncAll(next)

        assertEquals("one renamed, one new", 2, write.upserted)
        assertEquals("two removed upstream", 2, write.removed)
        val stored = dao.getAll().associateBy { it.id }
        assertEquals(99, stored.size)
        assertEquals("Renamed", stored.getValue("s3").name)
        assertTrue("s7" !in stored && "s8" !in stored)
        assertTrue("s101" in stored)
    }

    @Test
    fun `a large first load is written in chunks not one transaction`() = runBlocking {
        val dao = db.liveStreamDao()
        val write = dao.syncAll((1..2_500).map { channel(it) })
        assertEquals(2_500, write.upserted)
        assertEquals(2_500, dao.count())
    }

    @Test
    fun `categories are rewritten only when they differ and hidden flags survive`() = runBlocking {
        val dao = db.categoryDao()
        val list = listOf(
            Category(id = "1", type = StreamType.LIVE, name = "News"),
            Category(id = "2", type = StreamType.LIVE, name = "Sports"),
        )
        assertTrue(dao.syncAll(StreamType.LIVE, list))
        dao.toggleHidden("2", StreamType.LIVE)

        assertFalse(
            "same list, even with a user-hidden row, is not rewritten",
            dao.syncAll(StreamType.LIVE, list),
        )
        assertEquals(setOf("1"), dao.observeVisible(StreamType.LIVE).first().map { it.id }.toSet())

        assertTrue(dao.syncAll(StreamType.LIVE, list + Category(id = "3", type = StreamType.LIVE, name = "Movies")))
        assertEquals(setOf("1", "3"), dao.observeVisible(StreamType.LIVE).first().map { it.id }.toSet())
    }

    // ----------------------------------------------------------- guide

    private fun programs(channels: Int, perChannel: Int, tag: String): List<EpgProgram> =
        (0 until channels).flatMap { c ->
            (0 until perChannel).map { k ->
                EpgProgram(
                    channelId = "ch$c.fake",
                    title = "$tag $c-$k",
                    startUtcMillis = 1_000_000L + k * 3_600_000L,
                    endUtcMillis = 1_000_000L + (k + 1) * 3_600_000L,
                )
            }
        }

    @Test
    fun `a multi-batch promotion replaces the guide and drains the stage`() = runBlocking {
        val dao = db.epgProgramDao()
        dao.insertAll(programs(channels = 30, perChannel = 4, tag = "Old"))
        // Enough staged rows for several batches (batch target is PROMOTE_BATCH_ROWS).
        val perChannel = EpgProgramDao.PROMOTE_BATCH_ROWS / 50
        val fresh = programs(channels = 160, perChannel = perChannel, tag = "New")
        dao.stageAllStreamed(fresh.asSequence())

        val promoted = dao.promoteStaged()

        assertEquals(fresh.size, promoted)
        assertEquals(0, dao.stagedCount())
        val live = dao.getBetween("ch0.fake", 0L, Long.MAX_VALUE)
        assertTrue(live.all { it.title.startsWith("New") })
        assertEquals(perChannel, live.size)
        val total = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM epg_programs").use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(fresh.size, total)
    }

    @Test
    fun `promotion removes channels the new guide dropped but keeps the rest`() = runBlocking {
        val dao = db.epgProgramDao()
        dao.insertAll(programs(channels = 10, perChannel = 3, tag = "Old"))
        // New guide only lists channels 0..4.
        dao.stageAllStreamed(programs(channels = 5, perChannel = 2, tag = "New").asSequence())

        dao.promoteStaged()

        assertTrue(dao.getBetween("ch7.fake", 0L, Long.MAX_VALUE).isEmpty())
        assertEquals(2, dao.getBetween("ch2.fake", 0L, Long.MAX_VALUE).size)
    }

    @Test
    fun `the live guide is never empty during a promotion and its observers keep re-emitting`() = runBlocking {
        val dao = db.epgProgramDao()
        dao.insertAll(programs(channels = 120, perChannel = 5, tag = "Old"))
        dao.stageAllStreamed(programs(channels = 120, perChannel = 50, tag = "New").asSequence())

        val sawEmpty = AtomicInteger(0)
        val emissions = AtomicInteger(0)
        val watcher = launch(Dispatchers.IO) {
            dao.observeBetween("ch1.fake", 0L, Long.MAX_VALUE).collect {
                emissions.incrementAndGet()
                if (it.isEmpty()) sawEmpty.incrementAndGet()
            }
        }
        withTimeout(5_000) { while (emissions.get() < 1) delay(10) }

        val promotion = async(start = CoroutineStart.DEFAULT) { dao.promoteStaged() }
        promotion.await()
        withTimeout(5_000) { while (emissions.get() < 2) delay(10) }
        watcher.cancel()

        assertEquals("a reader must never see the guide empty", 0, sawEmpty.get())
        assertTrue(
            "Room's invalidation trigger must stay attached so observers re-emit (F-65)",
            emissions.get() >= 2,
        )
        val last = dao.getBetween("ch1.fake", 0L, Long.MAX_VALUE)
        assertEquals(50, last.size)
    }

    @Test
    fun `a cancelled promotion clears the stage and leaves whole channels behind`() = runBlocking {
        val dao = db.epgProgramDao()
        dao.insertAll(programs(channels = 5, perChannel = 3, tag = "Old"))
        dao.stageAllStreamed(programs(channels = 5, perChannel = 4, tag = "New").asSequence())

        try {
            kotlinx.coroutines.coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                    dao.promoteStaged()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }

        assertEquals("a failed promotion must not leave a stage behind", 0, dao.stagedCount())
        for (c in 0 until 5) {
            val rows = dao.getBetween("ch$c.fake", 0L, Long.MAX_VALUE)
            assertTrue("channel $c is never empty", rows.isNotEmpty())
            assertTrue("channel $c is all old or all new", rows.map { it.title.substringBefore(' ') }.toSet().size == 1)
        }
    }
}
