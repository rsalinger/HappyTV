package com.rysal.happytv.data.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.VodStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #78: "Recently added" off `index_vod_streams_added` on a catalog the size of the real one
 * (31 598 movies). The host timing is printed for the PR (the TV measurement is QA's); the
 * assertion is the plan: an index scan, no sort of the table.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RecentlyAddedIndexTimingTest {

    private lateinit var db: HappyTvDatabase

    @Before
    fun setUp() {
        db = HappyTvDatabase.build(ApplicationProvider.getApplicationContext<Application>(), inMemory = true)
    }

    @After
    fun tearDown() = db.close()

    private fun median(runs: Int, block: () -> Unit): Long {
        block() // warm the page cache and the statement cache
        return List(runs) {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000
        }.sorted()[runs / 2]
    }

    @Test
    fun `recently added reads 480 rows off the index of a 31k catalog`() = runBlocking {
        val total = 31_598
        db.vodStreamDao().insertAll(
            List(total) { i ->
                VodStream(
                    id = "m$i",
                    categoryId = "c${i % 40}",
                    num = i,
                    name = "Movie $i",
                    streamIcon = "https://img.example/$i.jpg",
                    added = 1_600_000_000L + (i * 7919L % total),
                )
            },
        )
        val sqlite = db.openHelper.readableDatabase

        val plan = sqlite.query(
            "EXPLAIN QUERY PLAN SELECT id, name, NULL AS year, streamIcon AS posterUrl FROM vod_streams " +
                "WHERE added IS NOT NULL ORDER BY added DESC, rowid DESC LIMIT 480",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }
        assertTrue("expected an index scan on added, plan was $plan", plan.any { "index_vod_streams_added" in it })
        assertTrue("expected no sort, plan was $plan", plan.none { "TEMP B-TREE" in it })

        var rows = 0
        val indexed = median(7) { rows = runBlocking { db.vodStreamDao().observeRecentlyAdded(480).first() }.size }
        assertEquals(480, rows)

        // The same query with the index ignored: the cost the index removes.
        val unindexed = median(7) {
            sqlite.query(
                "SELECT id, name, NULL AS year, streamIcon AS posterUrl FROM vod_streams NOT INDEXED " +
                    "WHERE added IS NOT NULL ORDER BY added DESC, rowid DESC LIMIT 480",
            ).use { c -> while (c.moveToNext()) Unit }
        }
        println("HtvPerfHost recently-added indexed=${indexed}ms unindexed=${unindexed}ms rows=$total (Robolectric host, not the TV)")
        assertTrue("the index should not be slower than a full sort ($indexed ms vs $unindexed ms)", indexed <= unindexed + 50)
    }
}
