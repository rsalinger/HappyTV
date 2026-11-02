package com.rysal.happytv.data.guide

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.LiveStream
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.data.network.CategoryDto
import com.rysal.happytv.data.network.EpgListingDto
import com.rysal.happytv.data.network.EpgListingsResponseDto
import com.rysal.happytv.data.network.LiveStreamDto
import com.rysal.happytv.data.network.LoginResponse
import com.rysal.happytv.data.network.ServerInfoDto
import com.rysal.happytv.data.network.SeriesStreamDto
import com.rysal.happytv.data.network.UserInfoDto
import com.rysal.happytv.data.network.VodStreamDto
import com.rysal.happytv.data.network.XtreamApi
import com.rysal.happytv.data.network.dtoBody
import com.rysal.happytv.data.network.XtreamRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-47's worker dead-start fix: on a brand-new sign-in the user has
 * neither favorites nor recently-watched channels yet, so the
 * legacy `(favorites + recent) ∩ catalog` target set was empty and
 * the Live TV rail stayed on "No guide info" forever. This test
 * pins the new contract: the worker always fills to
 * `PROVIDER_REFRESH_MAX_CHANNELS` channels per run — the user's
 * favorites + recents first, then the catalog head sorted by `num`
 * so the run leaves *some* now-line visible on the rail no matter
 * what state the user is in.
 *
 * Coverage:
 *
 *  - With favorites + recent BOTH empty, the worker refreshes the
 *    first `PROVIDER_REFRESH_MAX_CHANNELS` catalog rows by `num`
 *    order.
 *  - With favorites or recent NON-empty, those still come first
 *    (the user's picks win the budget), but the run STILL fills
 *    the remaining slots from the catalog head — so a user with
 *    one favorite gets 25 rows populated, not 1.
 *  - The target order is stable across runs (no race where order
 *    changes after a single refresh).
 *
 * Robolectric + in-memory Room, same harness as GuideProviderFetchTest.
 * The fake panel's `getShortEpg` returns one entry per channel so we
 * can verify the store was populated row-by-row.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuideProviderRefreshFallbackTest {

    private lateinit var db: HappyTvDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "guide-fallback-${dataStoreFiles++}.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Fake panel: one listing per channel so the worker has something to store. */
    private class CountingFakeApi : XtreamApi {
        val requestedStreams = mutableListOf<String>()
        override suspend fun login(endpoint: String, username: String, password: String) =
            LoginResponse(
                user_info = UserInfoDto(username = "u", auth = "1", status = "Active"),
                server_info = ServerInfoDto(url = "p", port = "80"),
            )
        override suspend fun getLiveCategories(endpoint: String, username: String, password: String, action: String) = emptyList<CategoryDto>()
        override suspend fun getLiveStreams(endpoint: String, username: String, password: String, categoryId: String?, action: String) = dtoBody(emptyList<LiveStreamDto>())
        override suspend fun getVodCategories(endpoint: String, username: String, password: String, action: String) = emptyList<CategoryDto>()
        override suspend fun getVodStreams(endpoint: String, username: String, password: String, categoryId: String?, action: String) = dtoBody(emptyList<VodStreamDto>())
        override suspend fun getSeriesCategories(endpoint: String, username: String, password: String, action: String) = emptyList<CategoryDto>()
        override suspend fun getSeries(endpoint: String, username: String, password: String, categoryId: String?, action: String) = dtoBody(emptyList<SeriesStreamDto>())
        override suspend fun getVodInfo(endpoint: String, username: String, password: String, vodId: String, action: String) = throw UnsupportedOperationException()
        override suspend fun getSeriesInfo(endpoint: String, username: String, password: String, seriesId: String, action: String) = throw UnsupportedOperationException()
        override suspend fun getShortEpg(
            endpoint: String,
            username: String,
            password: String,
            streamId: String,
            limit: Int,
            action: String,
        ): EpgListingsResponseDto {
            requestedStreams.add(streamId)
            val now = System.currentTimeMillis() / 1000L
            return EpgListingsResponseDto(
                epg_listings = listOf(
                    EpgListingDto(
                        id = "row-$streamId",
                        epg_id = streamId,
                        title = "Now on $streamId",
                        description = null,
                        start_timestamp = (now - 60).toString(),
                        stop_timestamp = (now + 1800).toString(),
                        has_archive = null,
                    ),
                ),
            )
        }
        override suspend fun getSimpleDataTable(endpoint: String, username: String, password: String, streamId: String, action: String) =
            EpgListingsResponseDto()
        override suspend fun getXmlTv(endpoint: String, username: String, password: String): ResponseBody =
            "<tv/>".toResponseBody()
    }

    private fun repository(api: XtreamApi) = GuideRepository(
        epgDao = db.epgProgramDao(),
        liveDao = db.liveStreamDao(),
        categoryDao = db.categoryDao(),
        favoriteDao = db.favoriteDao(),
        recentDao = db.recentChannelDao(),
        settings = settings,
        session = {
            XtreamRepository(host = "p", port = 80, useTls = false, username = "u", password = "p", api = api)
        },
        xmlTvFetcher = HttpXmlTvFetcher(ApplicationProvider.getApplicationContext()),
    )

    private fun channel(id: String, num: Int) = LiveStream(
        id = id,
        num = num,
        name = "Channel $id",
        streamIcon = null,
        categoryId = "1",
        tvArchive = false,
        tvArchiveDuration = null,
        epgChannelId = null,
    )

    @Test
    fun `empty favorites and recent fills from the catalog head sorted by num`() = runBlocking {
        val api = CountingFakeApi()
        // 30 channels numbered 1..30 — only PROVIDER_REFRESH_MAX_CHANNELS (25) get touched.
        db.liveStreamDao().replaceAll((1..30).map { channel(id = "$it", num = it) })

        val refreshed = repository(api).refreshProviderGuides()

        assertEquals(GuideRepository.PROVIDER_REFRESH_MAX_CHANNELS, refreshed)
        // Catalog head = 1..25, in num order, deterministically.
        assertEquals(
            (1..25).map { "$it" },
            api.requestedStreams.take(GuideRepository.PROVIDER_REFRESH_MAX_CHANNELS),
        )
        // The store now has rows for the first 25 channels.
        val stored = db.epgProgramDao().count()
        assertTrue("expected ~25 rows in the store, got $stored", stored >= 25)
    }

    @Test
    fun `non-empty favorites come first and the catalog head tops up the budget`() = runBlocking {
        val api = CountingFakeApi()
        db.liveStreamDao().replaceAll((1..30).map { channel(id = "$it", num = it) })
        db.favoriteDao().toggle(streamId = "27", streamType = StreamType.LIVE)

        val refreshed = repository(api).refreshProviderGuides()

        // With one favorite the run still tops up to the 25-channel
        // budget — the favorite goes first, the rest fill from the
        // catalog head. That is the F-47 fix: the user's picks are
        // respected AND the rail is never left empty.
        assertEquals(GuideRepository.PROVIDER_REFRESH_MAX_CHANNELS, refreshed)
        assertEquals("27", api.requestedStreams.first())
        // The remaining 24 requests are the catalog head in num order,
        // minus 27 (already refreshed) so the run doesn't double-fetch.
        val rest = api.requestedStreams.drop(1)
        assertEquals(24, rest.size)
        assertEquals("1", rest.first())
        assertTrue("27" !in rest)
    }

    @Test
    fun `catalog head order is stable across runs`() = runBlocking {
        // Same fixture, same fallback — second invocation must produce the
        // same first-channel request, so re-runs of the worker keep
        // warming the same rows (no thrash).
        val api = CountingFakeApi()
        db.liveStreamDao().replaceAll((1..30).map { channel(id = "$it", num = it) })
        val repo = repository(api)
        repo.refreshProviderGuides()
        val firstRun = api.requestedStreams.toList()
        api.requestedStreams.clear()
        repo.refreshProviderGuides()
        val secondRun = api.requestedStreams.toList()
        assertEquals(firstRun, secondRun)
    }

    companion object {
        /** DataStore refuses a second instance per file — unique per test. */
        private var dataStoreFiles = 0
    }
}
