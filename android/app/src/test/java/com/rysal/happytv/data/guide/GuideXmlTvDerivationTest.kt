package com.rysal.happytv.data.guide

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.security.Credentials
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-47 / F-52 — the worker must derive the panel's `xmltv.php` URL
 * from the saved credentials when `settings.epgUrl` is blank, and
 * must **persist the derived URL back to `settings.epgUrl`** so the
 * user never has to type one. The first end-to-end pin is here:
 *
 *  1. Blank `settings.epgUrl` + a saved `Credentials(host=…)`.
 *  2. `fetchAndStoreXmlTv()` writes the derived URL into
 *     `settings.epgUrl` BEFORE any HTTP fetch — so the Settings UI
 *     and the `isXmltvEnabled` flow both see the same value.
 *  3. The derived URL is then GET'd on the network. The
 *     `MockWebServer` answers with a real-shaped XMLTV file.
 *  4. The store has the parsed programmes keyed by `epg_channel_id`.
 *
 * The second-run pin (also here) proves the derivation is **not**
 * recomputed once the URL is cached — the credentials supplier
 * becomes a no-op.
 *
 * Robolectric + in-memory Room + a MockWebServer as the panel (the
 * test is platform-only — no real I/O, no Keystore).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuideXmlTvDerivationTest {

    /**
     * Fixture programmes are dated today (UTC), never a fixed day: the import
     * trims anything older than the keep-past-days window, so a hard-coded date
     * silently expires (#41).
     */
    private val day: String = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)

    private lateinit var server: MockWebServer
    private lateinit var db: HappyTvDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "guide-xmltv-deriv-${dataStoreFiles++}.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun repository(creds: Credentials?) = GuideRepository(
        epgDao = db.epgProgramDao(),
        liveDao = db.liveStreamDao(),
        categoryDao = db.categoryDao(),
        favoriteDao = db.favoriteDao(),
        recentDao = db.recentChannelDao(),
        settings = settings,
        session = { null },
        xmlTvFetcher = HttpXmlTvFetcher(ApplicationProvider.getApplicationContext()),
        credentials = { creds },
    )

    @Test
    fun `blank settings url plus saved credentials fetches via the derived url`() = runBlocking {
        val creds = Credentials(
            host = "127.0.0.1",
            port = server.port,
            useTls = false,
            username = "demo",
            password = "p@ss&word/with+stuff",
            savedAtUtcMillis = 0L,
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/xml")
                .setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <tv generator-info-name="mock-panel">
                      <channel id="northbaynews.fake">
                        <display-name>Northbay News</display-name>
                      </channel>
                      <programme start="${day}140000 +0000" stop="${day}150000 +0000" channel="northbaynews.fake">
                        <title>Headline report</title>
                      </programme>
                    </tv>
                    """.trimIndent(),
                ),
        )

        // Before the call the user has not typed anything.
        assertNull(settings.settings.first().epgUrl)
        val ok = repository(creds).fetchAndStoreXmlTv()
        assertTrue("expected the worker to populate the store", ok)

        // #18: only the auto marker is persisted. The URL (with the
        // password) is rebuilt from the credentials on every fetch.
        val persisted = settings.settings.first().epgUrl
        assertEquals(SettingsRepository.EPG_AUTO, persisted)
        assertNull(settings.epgOverrideUrl())
        assertFalse(settings.settings.first().toString().contains("word"))

        // The worker hit exactly the URL it derived — MockWebServer recorded it.
        val recorded = server.takeRequest()
        assertEquals("/xmltv.php", recorded.path?.substringBefore("?"))
        assertTrue(
            "expected username=demo in ${recorded.path}",
            recorded.path.orEmpty().contains("username=demo"),
        )

        // The store now has the parsed programmes — keyed by epg_channel_id.
        val rows = db.epgProgramDao().observeBetweenRange(0, Long.MAX_VALUE).first()
        assertEquals(1, rows.size)
        assertEquals("northbaynews.fake", rows.first().channelId)
        assertEquals("Headline report", rows.first().title)
    }

    @Test
    fun `auto mode keeps deriving from the credentials on later passes`() = runBlocking {
        val creds = Credentials("127.0.0.1", server.port, false, "demo", "secret", 0L)
        settings.setEpgUrl(SettingsRepository.EPG_AUTO)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<?xml version="1.0"?><tv><channel id="x"><display-name>X</display-name></channel>
                <programme start="20261016140000 +0000" stop="20261016150000 +0000" channel="x"><title>T</title></programme></tv>""",
            ),
        )
        assertTrue(repository(creds).fetchAndStoreXmlTv())
        assertTrue(server.takeRequest().path.orEmpty().contains("password=secret"))
        assertEquals(SettingsRepository.EPG_AUTO, settings.settings.first().epgUrl)
    }

    @Test
    fun `null credentials and blank url returns false without HTTP`() = runBlocking {
        // No credentials supplier (use the default = null), no URL. The
        // worker cannot make up a URL out of thin air — return false.
        assertEquals(false, repository(null).fetchAndStoreXmlTv())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `explicit settings url wins over the derived one — non-standard panel`() = runBlocking {
        val creds = Credentials(
            host = "127.0.0.1",
            port = server.port,
            useTls = false,
            username = "demo",
            password = "secret",
            savedAtUtcMillis = 0L,
        )
        // A non-Xtream panel serves XMLTV under a custom path — the user
        // types it once. The credentials-based derivation must NOT clobber
        // that, and the explicit URL must reach the wire.
        settings.setEpgUrl(
            "http://127.0.0.1:${server.port}/custom-guide.xml?token=abc",
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/xml")
                .setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <tv><channel id="x"><display-name>X</display-name></channel>
                    <programme start="${day}140000 +0000" stop="${day}150000 +0000" channel="x"><title>Custom guide</title></programme>
                    </tv>
                    """.trimIndent(),
                ),
        )

        val ok = repository(creds).fetchAndStoreXmlTv()
        assertTrue(ok)
        val recorded = server.takeRequest()
        assertEquals("/custom-guide.xml", recorded.path?.substringBefore("?"))
        // #18: the link lives encrypted; the plain store holds the marker.
        assertEquals(
            "http://127.0.0.1:${server.port}/custom-guide.xml?token=abc",
            settings.epgOverrideUrl(),
        )
        assertEquals(SettingsRepository.EPG_OVERRIDE, settings.settings.first().epgUrl)
    }

    @Test
    fun `failed fetch does NOT persist the derived URL — keeps provider path available`() = runBlocking {
        // Regression pin for the F-47 wedge: if we set `settings.epgUrl`
        // optimistically and the fetch then fails, `isXmltvGuideEnabled`
        // returns true forever (xmltvMode) with zero rows stored — the
        // exact "No guide info" symptom F-47 is about. Fix: persist only
        // after `epgDao.replaceAll` succeeds.
        val creds = Credentials(
            host = "127.0.0.1",
            port = server.port,
            useTls = false,
            username = "demo",
            password = "secret",
            savedAtUtcMillis = 0L,
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("boom"),
        )

        // The worker's HTTP fetch throws IOException — catch and continue.
        runCatching { repository(creds).fetchAndStoreXmlTv() }

        // Nothing persisted — the next run can still pick the provider
        // path (or retry the derivation) instead of being wedged in
        // "XMLTV mode" with an empty store.
        assertNull(settings.settings.first().epgUrl)
    }

    companion object {
        /** DataStore refuses a second instance per file — unique per test. */
        private var dataStoreFiles = 0
    }
}
