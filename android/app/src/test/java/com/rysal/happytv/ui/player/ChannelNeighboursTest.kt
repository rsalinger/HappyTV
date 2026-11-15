package com.rysal.happytv.ui.player

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.Favorite
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #224: which channel Up and Down go to, against a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ChannelNeighboursTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var neighbours: ChannelNeighbours

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        neighbours = ChannelNeighbours(db.liveStreamDao(), db.favoriteDao(), db.hiddenChannelDao())
        runBlocking {
            db.categoryDao().insertAll(
                listOf(
                    Category("1", StreamType.LIVE, "News"),
                    Category("2", StreamType.LIVE, "Sports"),
                    Category("3", StreamType.LIVE, "Hidden", isHidden = true),
                ),
            )
            db.liveStreamDao().insertAll(
                listOf(
                    LiveStream("n1", "1", num = 101, name = "News One"),
                    LiveStream("n2", "1", num = 102, name = "News Two"),
                    LiveStream("n3", "1", num = 103, name = "News Three"),
                    LiveStream("s1", "2", num = 201, name = "Sports One"),
                    LiveStream("s2", "2", num = 202, name = "Sports Two"),
                    LiveStream("h1", "3", num = 301, name = "Hidden category"),
                    LiveStream("x1", "1", num = null, name = "Zeta no number"),
                    LiveStream("x2", "1", num = null, name = "Alpha no number"),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        val drained = java.util.concurrent.CountDownLatch(1)
        runCatching { db.queryExecutor.execute { drained.countDown() } }
        drained.await(5, java.util.concurrent.TimeUnit.SECONDS)
        db.close()
    }

    private fun step(source: ChannelListSource, from: String, direction: Int) =
        runBlocking { neighbours.neighbour(source, from, direction)?.id }

    private val news = ChannelListSource.Category("1")

    @Test
    fun `down is the next channel by number and up the one before`() {
        assertEquals("n2", step(news, "n1", +1))
        assertEquals("n3", step(news, "n2", +1))
        assertEquals("n1", step(news, "n2", -1))
    }

    @Test
    fun `channels without a number come last in name order, as Live TV lists them`() {
        assertEquals("x2", step(news, "n3", +1))
        assertEquals("x1", step(news, "x2", +1))
        assertEquals("x2", step(news, "x1", -1))
        assertEquals("n3", step(news, "x2", -1))
    }

    @Test
    fun `both ends wrap`() {
        assertEquals("n1", step(news, "x1", +1))
        assertEquals("x1", step(news, "n1", -1))
    }

    @Test
    fun `hidden channels are skipped, also at the ends`() = runBlocking {
        db.hiddenChannelDao().insert(HiddenChannel("n2", StreamType.LIVE))
        db.hiddenChannelDao().insert(HiddenChannel("x1", StreamType.LIVE))
        assertEquals("n3", step(news, "n1", +1))
        assertEquals("n1", step(news, "n3", -1))
        assertEquals("n1", step(news, "x2", +1))
    }

    @Test
    fun `a category with one channel has nowhere to go`() {
        assertNull(step(ChannelListSource.Category("3"), "h1", +1))
        assertNull(step(ChannelListSource.Category("3"), "h1", -1))
    }

    @Test
    fun `all channels walk the whole lineup by number and leave out hidden categories`() {
        assertEquals("s1", step(ChannelListSource.AllChannels, "n3", +1))
        assertEquals("x2", step(ChannelListSource.AllChannels, "s2", +1)) // h1 sits in a hidden category
        assertEquals("n1", step(ChannelListSource.AllChannels, "x1", +1))
        assertEquals("n3", step(ChannelListSource.AllChannels, "s1", -1))
        assertEquals("s2", step(ChannelListSource.AllChannels, "x2", -1))
    }

    @Test
    fun `no list uses the channel's own provider category`() {
        assertEquals("s2", step(ChannelListSource.OwnCategory, "s1", +1))
        assertEquals("s1", step(ChannelListSource.OwnCategory, "s2", +1)) // wraps inside Sports, not on to News
    }

    @Test
    fun `favorites follow the favorites order, not the numbers`() = runBlocking {
        val favorites = db.favoriteDao()
        favorites.insert(Favorite("s2", StreamType.LIVE, position = 1))
        favorites.insert(Favorite("n1", StreamType.LIVE, position = 2))
        favorites.insert(Favorite("n3", StreamType.LIVE, position = 3))
        assertEquals("n1", step(ChannelListSource.Favorites, "s2", +1))
        assertEquals("n3", step(ChannelListSource.Favorites, "n1", +1))
        assertEquals("s2", step(ChannelListSource.Favorites, "n3", +1)) // wraps
        assertEquals("n3", step(ChannelListSource.Favorites, "s2", -1))
    }

    @Test
    fun `favorites skip hidden and vanished channels`() = runBlocking {
        val favorites = db.favoriteDao()
        favorites.insert(Favorite("n1", StreamType.LIVE, position = 1))
        favorites.insert(Favorite("n2", StreamType.LIVE, position = 2))
        favorites.insert(Favorite("gone", StreamType.LIVE, position = 3))
        favorites.insert(Favorite("s1", StreamType.LIVE, position = 4))
        db.hiddenChannelDao().insert(HiddenChannel("n2", StreamType.LIVE))
        assertEquals("s1", step(ChannelListSource.Favorites, "n1", +1))
        assertEquals("n1", step(ChannelListSource.Favorites, "s1", +1))
    }

    @Test
    fun `playing a channel that is not a favorite falls back to its own category`() = runBlocking {
        db.favoriteDao().insert(Favorite("s2", StreamType.LIVE, position = 1))
        db.favoriteDao().insert(Favorite("n1", StreamType.LIVE, position = 2))
        assertEquals("n2", step(ChannelListSource.Favorites, "n3", -1)) // n3 is not a favorite: News order
    }

    @Test
    fun `a single favorite has nowhere to go`() = runBlocking {
        db.favoriteDao().insert(Favorite("n1", StreamType.LIVE, position = 1))
        assertNull(step(ChannelListSource.Favorites, "n1", +1))
    }

    @Test
    fun `an unknown channel gives nothing`() {
        assertNull(step(news, "nope", +1))
    }
}
