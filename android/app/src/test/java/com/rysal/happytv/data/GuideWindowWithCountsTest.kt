package com.rysal.happytv.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.guide.GuideRepository
import com.rysal.happytv.data.guide.HttpXmlTvFetcher
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #52: the guide's window read and its "has guide info" counts come from one observer, and the
 * counts query only runs for channels that have nothing in the window.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuideWindowWithCountsTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var repository: GuideRepository
    private val now = 10_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "guide-window-counts.preferences_pb")
        }
        repository = GuideRepository(
            epgDao = db.epgProgramDao(),
            liveDao = db.liveStreamDao(),
            categoryDao = db.categoryDao(),
            favoriteDao = db.favoriteDao(),
            recentDao = db.recentChannelDao(),
            settings = SettingsRepository(dataStore),
            session = { null },
            xmlTvFetcher = HttpXmlTvFetcher(context),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun program(channel: String, start: Long, end: Long) = EpgProgram(
        channelId = channel,
        title = "T",
        description = null,
        startUtcMillis = start,
        endUtcMillis = end,
    )

    @Test
    fun `a channel with programmes in the window counts as having guide info without a count query`() = runBlocking {
        db.epgProgramDao().insertAll(listOf(program("a", now - 10, now + 10), program("a", now + 10, now + 20)))
        val (programs, counts) = repository.observeWindowWithCounts(listOf("a"), now - 100, now + 100).first()
        assertEquals(2, programs.getValue("a").size)
        assertTrue((counts["a"] ?: 0) > 0)
    }

    @Test
    fun `a channel with programmes only outside the window is counted, one with none is not`() = runBlocking {
        db.epgProgramDao().insertAll(
            listOf(
                program("old", 0, 1),
                program("old", 2, 3),
                program("here", now - 10, now + 10),
            ),
        )
        val (programs, counts) =
            repository.observeWindowWithCounts(listOf("here", "old", "nobody"), now - 100, now + 100).first()
        assertEquals(setOf("here"), programs.keys)
        assertEquals(2, counts["old"])
        assertTrue((counts["here"] ?: 0) > 0)
        assertFalse(counts.containsKey("nobody"))
    }

    @Test
    fun `no keys yields empty maps`() = runBlocking {
        val (programs, counts) = repository.observeWindowWithCounts(emptyList(), now - 100, now + 100).first()
        assertTrue(programs.isEmpty())
        assertTrue(counts.isEmpty())
    }
}
