package com.rysal.happytv.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.guide.XmlTvUrlDeriver
import com.rysal.happytv.security.Credentials
import com.rysal.happytv.security.InMemoryEpgOverrideStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #18: the pre-fix plain-DataStore XMLTV URL is replaced by a marker. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsEpgUrlMigrationTest {

    private val epgKey = stringPreferencesKey("epg_url")
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var overrides: InMemoryEpgOverrideStore
    private lateinit var settings: SettingsRepository
    private val creds = Credentials("panel.example", 443, true, "demo", "p@ss&w0rd/x", 0L)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "epg-migration-${System.nanoTime()}.preferences_pb")
        }
        overrides = InMemoryEpgOverrideStore()
        settings = SettingsRepository(dataStore, overrides)
    }

    private suspend fun rawStored(): String? = dataStore.data.first()[epgKey]

    private suspend fun seedLegacy(url: String) {
        dataStore.edit { it[epgKey] = url }
    }

    @Test
    fun storedDerivedUrl_becomesTheAutoMarker() = runBlocking {
        seedLegacy(XmlTvUrlDeriver.derive(creds)!!)

        assertTrue(settings.migrateLegacyEpgUrl(creds))

        assertEquals(SettingsRepository.EPG_AUTO, rawStored())
        assertNull(overrides.value)
        assertFalse(settings.settings.first().toString().contains("w0rd"))
    }

    @Test
    fun storedUrlContainingThePassword_becomesTheAutoMarker() = runBlocking {
        // A derived URL for the same password on an older host/port still leaks it.
        seedLegacy(
            "http://old.example:8080/xmltv.php?username=demo&password=" +
                java.net.URLEncoder.encode(creds.password, "UTF-8"),
        )

        settings.migrateLegacyEpgUrl(creds)

        assertEquals(SettingsRepository.EPG_AUTO, rawStored())
    }

    @Test
    fun aTypedOverride_movesToTheEncryptedStore() = runBlocking {
        seedLegacy("https://guide.example/epg.xml?token=abc")

        settings.migrateLegacyEpgUrl(creds)

        assertEquals(SettingsRepository.EPG_OVERRIDE, rawStored())
        assertEquals("https://guide.example/epg.xml?token=abc", overrides.value)
        assertEquals("https://guide.example/epg.xml?token=abc", settings.epgOverrideUrl())
    }

    @Test
    fun markersAndAbsentValuesAreLeftAlone_andItIsIdempotent() = runBlocking {
        assertFalse(settings.migrateLegacyEpgUrl(creds))
        assertNull(rawStored())

        settings.setEpgUrl("")
        assertFalse(settings.migrateLegacyEpgUrl(creds))
        assertEquals("", rawStored())

        seedLegacy(XmlTvUrlDeriver.derive(creds)!!)
        assertTrue(settings.migrateLegacyEpgUrl(creds))
        assertFalse(settings.migrateLegacyEpgUrl(creds))
        assertEquals(SettingsRepository.EPG_AUTO, rawStored())
    }

    @Test
    fun settingATypedUrl_neverReachesThePlainStore() = runBlocking {
        settings.setEpgUrl("http://x.example/xmltv.php?username=u&password=hunter2")

        assertEquals(SettingsRepository.EPG_OVERRIDE, rawStored())
        assertEquals("http://x.example/xmltv.php?username=u&password=hunter2", overrides.value)
        assertFalse(settings.settings.first().toString().contains("hunter2"))

        settings.setEpgUrl(null)
        assertNull(overrides.value)
        assertNull(rawStored())
    }

    @Test
    fun appSettingsToString_redactsALegacyRawUrl() {
        val text = AppSettings(epgUrl = "http://a.example/xmltv.php?username=u&password=hunter2").toString()
        assertFalse(text, text.contains("hunter2"))
    }
}
