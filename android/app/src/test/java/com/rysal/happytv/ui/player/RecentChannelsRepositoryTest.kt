package com.rysal.happytv.ui.player

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.entity.Category
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.HiddenChannel
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #220: the production wiring of the recent row against a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RecentChannelsRepositoryTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var repo: GraphPlayerRepository
    private val now = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java).allowMainThreadQueries().build()
        repo = GraphPlayerRepository(
            credentialsStore = com.rysal.happytv.security.SecuredCredentialsStore(prefsFactory = { error("unused") }),
            liveStreamDao = db.liveStreamDao(),
            vodStreamDao = db.vodStreamDao(),
            seriesStreamDao = db.seriesStreamDao(),
            favoriteDao = db.favoriteDao(),
            epgProgramDao = db.epgProgramDao(),
            userInfoJson = { null },
            json = kotlinx.serialization.json.Json,
            seriesInfo = { _, _ -> null },
            recentChannelDao = db.recentChannelDao(),
            hiddenChannelDao = db.hiddenChannelDao(),
            categoryDao = db.categoryDao(),
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        val drained = java.util.concurrent.CountDownLatch(1)
        runCatching { db.queryExecutor.execute { drained.countDown() } }
        drained.await(5, java.util.concurrent.TimeUnit.SECONDS)
        db.close()
    }

    private fun seed() = runBlocking {
        db.categoryDao().insertAll(
            listOf(
                Category("1", StreamType.LIVE, "News"),
                Category("2", StreamType.LIVE, "Hidden", isHidden = true),
            ),
        )
        db.liveStreamDao().insertAll(
            listOf(
                LiveStream("a", "1", num = 101, name = "Alpha", epgChannelId = "alpha.tv"),
                LiveStream("b", "1", num = 102, name = "Bravo"),
                LiveStream("c", "1", num = 103, name = "Charlie"),
                LiveStream("d", "2", num = 104, name = "Delta"),
                LiveStream("e", "1", num = 105, name = "Echo"),
            ),
        )
        db.epgProgramDao().insertAll(
            listOf(EpgProgram(channelId = "alpha.tv", title = "Morning Report", startUtcMillis = now - 600_000, endUtcMillis = now + 600_000)),
        )
    }

    @Test
    fun `newest first, without the current, hidden, hidden-category and missing channels`() = runBlocking {
        seed()
        val recents = db.recentChannelDao()
        // Oldest to newest: e, gone, d, c, b, a, cur (b is hidden, d is in a hidden category).
        recents.recordWatch("e", 1)
        recents.recordWatch("gone", 2)
        recents.recordWatch("d", 3)
        recents.recordWatch("c", 4)
        recents.recordWatch("b", 5)
        recents.recordWatch("a", 6)
        recents.recordWatch("c", 7) // c is the one playing now
        db.hiddenChannelDao().insert(HiddenChannel("b", StreamType.LIVE))

        val cards = repo.recentChannels(currentStreamId = "c", nowUtcMillis = now)

        assertEquals(listOf("a", "e"), cards.map { it.streamId })
        assertEquals("Morning Report", cards[0].programTitle)
        assertEquals(0.5f, cards[0].progressFraction(now)!!, 0.001f)
        assertFalse(cards[1].hasGuide)
        assertNull(cards[1].progressFraction(now))
    }

    @Test
    fun `no history gives an empty row`() = runBlocking {
        seed()
        assertEquals(emptyList<RecentChannelCard>(), repo.recentChannels("a", now))
    }

    @Test
    fun `a pick is recorded so the channel left is first next time`() = runBlocking {
        seed()
        val recents = db.recentChannelDao()
        recents.recordWatch("a", 10) // watching a, then picks b from the row
        repo.recordWatch("b")
        // Now b is playing: a is the first card.
        assertEquals(listOf("a"), repo.recentChannels("b", now).map { it.streamId })
        assertEquals("b", recents.recent().first().first().streamId)
    }
}
