package com.rysal.happytv.ui.search

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.dao.SearchChannelRow
import com.rysal.happytv.data.dao.SearchPosterRow
import com.rysal.happytv.data.entity.LiveStream
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
 * #241: Search sorts its matches in Kotlin instead of `ORDER BY num, name COLLATE NOCASE`. The last test runs the
 * old SQL on a real SQLite and demands the same order from [SearchOrder].
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SearchOrderTest {

    private lateinit var db: HappyTvDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        val drained = java.util.concurrent.CountDownLatch(1)
        runCatching { db.queryExecutor.execute { drained.countDown() } }
        drained.await(5, java.util.concurrent.TimeUnit.SECONDS)
        db.close()
    }

    private fun ch(id: String, name: String, num: Int?) = SearchChannelRow(id = id, name = name, num = num)

    private fun order(rows: List<SearchChannelRow>) = rows.sortedWith(SearchOrder.channels).map { it.id }

    @Test
    fun `a null num sorts before every number`() {
        val rows = listOf(ch("a", "Zed", 2), ch("b", "Zed", null), ch("c", "Zed", 1), ch("d", "Zed", 0))
        assertEquals(listOf("b", "d", "c", "a"), order(rows))
    }

    @Test
    fun `names that differ only by case tie and keep the table order`() {
        val rows = listOf(ch("a", "sports", 5), ch("b", "SPORTS", 5), ch("c", "Sports", 5))
        assertEquals(listOf("a", "b", "c"), order(rows))
        assertEquals(listOf("b", "c", "a"), order(listOf(rows[1], rows[2], rows[0])))
    }

    @Test
    fun `case is ignored when the names differ`() {
        val rows = listOf(ch("a", "beta", null), ch("b", "Alpha", null), ch("c", "ALPHA 2", null), ch("d", "Gamma", null))
        assertEquals(listOf("b", "c", "a", "d"), order(rows))
    }

    @Test
    fun `only ASCII letters fold, like COLLATE NOCASE`() {
        assertTrue(SearchOrder.compareNoCase("Ez", "ez") == 0)
        // 'É' is not folded, so it sorts after 'z' by code point and differs from 'é'.
        assertTrue(SearchOrder.compareNoCase("Éa", "za") > 0)
        assertTrue(SearchOrder.compareNoCase("Éa", "éa") < 0)
        // A prefix sorts first.
        assertTrue(SearchOrder.compareNoCase("News", "News 24") < 0)
    }

    @Test
    fun `posters order by num then name the same way`() {
        val rows = listOf(
            SearchPosterRow("1", "b", null, num = 3),
            SearchPosterRow("2", "A", null, num = null),
            SearchPosterRow("3", "a", null, num = 3),
            SearchPosterRow("4", "C", null, num = null),
        )
        assertEquals(listOf("2", "4", "3", "1"), rows.sortedWith(SearchOrder.posters).map { it.id })
    }

    @Test
    fun `the Kotlin order matches ORDER BY num, name COLLATE NOCASE on SQLite`() = runBlocking {
        val names = listOf(
            "sports", "SPORTS", "Sports 2", "sports", "Zulu", "alpha", "Alpha", "ÉCOLE", "école", "Ecole", "zebra",
            "News", "News 24", "news", "é", "z", "Ａ wide", "😀 smile", "ﬁ ligature", "_under",
            "1st", "10 pm", "2 pm", "", " lead", "Straße", "STRASSE",
        )
        val nums = listOf(null, 3, 1, null, 7, 1, 3)
        val streams = names.mapIndexed { i, n ->
            LiveStream(id = "id%03d".format(i), categoryId = "c", num = nums[i % nums.size], name = n)
        }
        db.liveStreamDao().insertAll(streams)

        val sqlOrder = mutableListOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT id FROM live_streams ORDER BY num, name COLLATE NOCASE, id").use { c ->
                while (c.moveToNext()) sqlOrder += c.getString(0)
            }
        val rows = db.liveStreamDao().observeSearchRows().first()
        assertEquals(streams.size, rows.size)
        // SQL's tie-break here is id; rows are fed in id order and the sort is stable.
        val kotlinOrder = rows.sortedBy { it.id }.sortedWith(SearchOrder.channels).map { it.id }
        assertEquals(sqlOrder, kotlinOrder)
    }
}
