package com.rysal.happytv.data.guide

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.HappyTvDatabase
import com.rysal.happytv.data.SettingsRepository
import com.rysal.happytv.data.entity.EpgProgram
import com.rysal.happytv.security.Credentials
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
 * F-52 — "cache the last-good guide": a refresh that goes wrong must leave
 * the guide the user was watching exactly as it was.
 *
 * The old path ran `deleteAll()` and then streamed the network body
 * straight into Room, so a connection dropped at 90% (or a panel serving
 * an HTML error page as 200) wiped every row. The staged pipeline only
 * touches `epg_programs` after a complete file is on disk, and the replace
 * itself is one Room transaction (`EpgProgramDao.replaceAllStreamed`), so
 * both failure shapes roll back to the previous guide.
 *
 * Robolectric + in-memory Room + MockWebServer as the panel.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GuideXmlTvLastGoodTest {

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
    private lateinit var context: Application

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HappyTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "guide-xmltv-lastgood-${dataStoreFiles++}.preferences_pb")
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
        xmlTvFetcher = HttpXmlTvFetcher(context),
        credentials = { creds },
    )

    private fun creds() = Credentials(
        host = "127.0.0.1",
        port = server.port,
        useTls = false,
        username = "demo",
        password = "secret",
        savedAtUtcMillis = 0L,
    )

    /** The guide the user is watching — every test proves it survives. */
    private suspend fun seedLastGood() {
        db.epgProgramDao().insertAll(
            listOf(
                EpgProgram(
                    channelId = "kept.fake",
                    title = "Kept one",
                    startUtcMillis = 1_000L,
                    endUtcMillis = 2_000L,
                ),
                EpgProgram(
                    channelId = "kept.fake",
                    title = "Kept two",
                    startUtcMillis = 2_000L,
                    endUtcMillis = 3_000L,
                ),
            ),
        )
    }

    private suspend fun storedTitles(): List<String> =
        db.epgProgramDao().observeBetweenRange(0, Long.MAX_VALUE).first().map { it.title }

    private fun stagedFile(): File = File(context.cacheDir, "guide_xmltv_stage/pending.xml")

    @Test
    fun `a dropped connection mid-body keeps the last-good guide`() = runBlocking {
        seedLastGood()
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <tv>
                      <programme start="${day}140000 +0000" stop="${day}150000 +0000" channel="new.fake">
                        <title>New row</title>
                      </programme>
                    """.trimIndent() + "\n".repeat(200),
                )
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        // The fetch fails (the worker retries)…
        val result = runCatching { repository(creds()).fetchAndStoreXmlTv() }
        assertTrue("expected the truncated body to fail the fetch", result.isFailure)

        // …but the stored guide is untouched, no half-staged file is left,
        // and the derived URL was never persisted (F-47's wedge guard).
        assertEquals(listOf("Kept one", "Kept two"), storedTitles())
        assertNull(settings.settings.first().epgUrl)
        assertFalse("staged file must be cleaned up", stagedFile().exists())
    }

    @Test
    fun `malformed xml rolls the whole replace back and keeps the last-good guide`() = runBlocking {
        seedLastGood()
        // 600 valid rows — past EpgProgramDao.INSERT_BATCH_SIZE (500), so a
        // batch is already flushed to Room — then a stray close tag makes the
        // parser throw mid-transaction. Only a real rollback keeps "Kept".
        val body = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("<tv>")
            for (i in 0 until 600) {
                append(
                    """<programme start="${day}140000 +0000" stop="${day}150000 +0000" """ +
                        """channel="new.fake"><title>New $i</title></programme>""",
                )
            }
            append("</tv></stray>")
        }
        server.enqueue(
            MockResponse().setBody(body).addHeader("Content-Type", "application/xml"),
        )

        val result = runCatching { repository(creds()).fetchAndStoreXmlTv() }
        assertTrue("expected malformed XML to fail the replace", result.isFailure)

        assertEquals(listOf("Kept one", "Kept two"), storedTitles())
        assertNull(settings.settings.first().epgUrl)
        assertFalse("staged file must be cleaned up", stagedFile().exists())
    }

    @Test
    fun `a healthy refresh replaces the guide and cleans up the staged file`() = runBlocking {
        seedLastGood()
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <tv>
                      <programme start="${day}140000 +0000" stop="${day}150000 +0000" channel="new.fake">
                        <title>Fresh row</title>
                      </programme>
                    </tv>
                    """.trimIndent(),
                )
                .addHeader("Content-Type", "application/xml"),
        )

        assertTrue(repository(creds()).fetchAndStoreXmlTv())
        assertEquals(listOf("Fresh row"), storedTitles())
        assertFalse("staged file must be cleaned up", stagedFile().exists())
    }

    @Test
    fun `the explicit From provider sentinel suppresses derivation entirely`() = runBlocking {
        // "" is Settings' "From provider" choice (F-52's three-state
        // contract): derivation must not fight it — no URL is derived, no
        // request is made, and the sentinel survives untouched.
        settings.setEpgUrl("")

        assertFalse(repository(creds()).fetchAndStoreXmlTv())

        assertEquals(0, server.requestCount)
        assertEquals("", settings.settings.first().epgUrl)
    }

    companion object {
        /** DataStore refuses a second instance per file — unique per test. */
        private var dataStoreFiles = 0
    }
}
