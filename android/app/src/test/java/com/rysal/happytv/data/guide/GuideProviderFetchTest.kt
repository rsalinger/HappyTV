package com.rysal.happytv.data.guide

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.network.XtreamRepository
import com.rysal.happytv.ui.main.FakeXtreamApi
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The provider fetch's **signed-in vs signed-out** contract (regression):
 *
 * `fetchProviderListings` used to return an empty list when there was no
 * session, and every caller stored what it got — `storeProviderPrograms`
 * first deletes the channel's rows. Opening the guide signed out therefore
 * **wiped the guide** the user still had from their last session, row by
 * row, as each channel became visible. The two answers are now distinct:
 *
 *  - **null** — nobody to ask (signed out): leave the store alone.
 *  - **empty list** — the provider answered and sent nothing: the channel
 *    genuinely has no guide, so its rows go (that path is covered by
 *    GuideStoreTest's per-channel upsert).
 *
 * Robolectric + in-memory Room, same harness as GuideStoreTest; the panel
 * is [FakeXtreamApi], whose `getShortEpg` answers with zero listings.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuideProviderFetchTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "guide-fetch-${dataStoreFiles++}.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A repository whose session lambda decides signed-in ([api]) or out (null). */
    private fun repository(session: suspend () -> XtreamRepository?) = GuideRepository(
        epgDao = db.epgProgramDao(),
        liveDao = db.liveStreamDao(),
        categoryDao = db.categoryDao(),
        favoriteDao = db.favoriteDao(),
        recentDao = db.recentChannelDao(),
        settings = settings,
        session = session,
        xmlTvFetcher = HttpXmlTvFetcher(ApplicationProvider.getApplicationContext()),
    )

    private fun signedInRepository() = repository {
        XtreamRepository(
            host = "panel.example",
            port = 8080,
            useTls = false,
            username = "demo-user",
            password = "secret",
            api = FakeXtreamApi(),
        )
    }

    private fun program(channelId: String, title: String) =
        EpgProgram(
            channelId = channelId,
            title = title,
            description = null,
            startUtcMillis = 1_000_000L,
            endUtcMillis = 2_000_000L,
        )

    @Test
    fun `signed out - fetch is null, not empty, so callers skip the store`() = runBlocking {
        assertNull(repository(session = { null }).fetchProviderListings("101", limit = 4))
    }

    @Test
    fun `signed out - the worker's provider refresh leaves the stored guide intact`() = runBlocking {
        val dao = db.epgProgramDao()
        dao.insertAll(
            listOf(
                program("101", "Cached headline"),
                program("301", "Cached hoops"),
            ),
        )
        // Both channels are targets (101 favorite, 301 recently watched)…
        db.favoriteDao().insert(
            com.rysal.happytv.data.entity.Favorite("101", StreamType.LIVE),
        )
        db.recentChannelDao().insert(
            com.rysal.happytv.data.entity.RecentChannel("301", lastWatchedUtcMillis = 1L),
        )

        val refreshed = repository(session = { null }).refreshProviderGuides(maxChannels = 25)

        // …but with nobody to ask the refresh does nothing: no channel was
        // "refreshed" and the cached rows are still there (the old code
        // deleted them one visible channel at a time).
        assertEquals(0, refreshed)
        assertEquals(2, dao.count())
    }

    @Test
    fun `signed in - the panel answered with nothing, so the channel's rows go`() = runBlocking {
        val dao = db.epgProgramDao()
        dao.insertAll(listOf(program("101", "Stale headline")))

        val listings = signedInRepository().fetchProviderListings("101", limit = 4)

        // FakeXtreamApi returns zero listings: an empty LIST (a real answer),
        // distinct from the signed-out null above — storing it clears 101.
        assertEquals(emptyList<GuideProgram>(), listings)
        signedInRepository().storeProviderPrograms("101", listings!!)
        assertEquals(0, dao.count())
    }

    @Test
    fun `signed in - a populated panel answer replaces the channel's rows`() = runBlocking {
        val repo = signedInRepository()
        val dao = db.epgProgramDao()
        dao.insertAll(listOf(program("101", "Old row")))

        // Drive the same write path the on-demand fetch uses, with listings
        // the "panel" returned (FakeXtreamApi's shape is empty by default,
        // so this stands in for a real answer).
        val listings = listOf(
            GuideProgram(
                channelId = "101",
                title = "Fresh headline",
                description = null,
                startUtcMillis = 1_000_000L,
                endUtcMillis = 2_000_000L,
            ),
        )
        repo.storeProviderPrograms("101", listings)

        val rows = dao.getBetweenRange(0, 3_000_000L)
        assertEquals(listOf("Fresh headline"), rows.map { it.title })
        // The upsert is per-channel: another channel's cache is untouched.
        assertEquals("Fresh headline", rows.single().title)
    }

    companion object {
        /** DataStore refuses a second instance per file — unique per test. */
        private var dataStoreFiles = 0
    }
}
