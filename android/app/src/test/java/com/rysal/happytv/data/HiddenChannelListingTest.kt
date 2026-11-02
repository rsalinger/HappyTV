package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #123: the Hidden items channel list joins names in, keeps rows for channels that are gone, and sorts. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class HiddenChannelListingTest {

    private lateinit var db: HappyTvDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HappyTvDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun listsNameAndCategory_sortedByName_goneChannelsLast() = runBlocking {
        db.categoryDao().replaceAll(
            StreamType.LIVE,
            listOf(Category(id = "n", type = StreamType.LIVE, name = "News")),
        )
        // A movie category sharing the id must not leak into a live channel's caption.
        db.categoryDao().replaceAll(
            StreamType.VOD,
            listOf(Category(id = "n", type = StreamType.VOD, name = "Movie night")),
        )
        db.liveStreamDao().replaceAll(
            listOf(
                LiveStream(id = "1", categoryId = "n", name = "zulu"),
                LiveStream(id = "2", categoryId = "n", name = "Alpha"),
                LiveStream(id = "3", categoryId = "missing", name = "Mike"),
                LiveStream(id = "4", categoryId = "n", name = "Not hidden"),
            ),
        )
        listOf("1", "2", "3", "99").forEach { db.hiddenChannelDao().insert(HiddenChannel(it)) }

        val rows = db.hiddenChannelDao().observeListing().first()

        assertEquals(listOf("2", "3", "1", "99"), rows.map { it.streamId })
        assertEquals(listOf("Alpha", "Mike", "zulu", null), rows.map { it.name })
        assertEquals(listOf("News", null, "News", null), rows.map { it.categoryName })
        assertNull(rows.last().name)
    }

    @Test
    fun deletingTheRow_removesItFromTheList() = runBlocking {
        db.hiddenChannelDao().insert(HiddenChannel("99"))
        db.hiddenChannelDao().delete("99", StreamType.LIVE)

        assertEquals(emptyList<String>(), db.hiddenChannelDao().observeListing().first().map { it.streamId })
    }
}
