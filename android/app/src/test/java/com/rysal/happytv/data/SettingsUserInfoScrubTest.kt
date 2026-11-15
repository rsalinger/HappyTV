package com.rysal.happytv.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.network.UserInfoDto
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #212: an old cached user_info blob with credentials is scrubbed once. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsUserInfoScrubTest {

    private val key = byteArrayPreferencesKey("user_info")
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        dataStore = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "scrub-${System.nanoTime()}.preferences_pb")
        }
        settings = SettingsRepository(dataStore)
    }

    private suspend fun seed(text: String) = dataStore.edit { it[key] = text.toByteArray() }
    private suspend fun stored(): String? = dataStore.data.first()[key]?.decodeToString()

    private val legacy = """{"username":"fake-user","password":"fake-pass-123","auth":"1",""" +
        """"message":"Welcome fake-user","status":"Active","exp_date":"1900000000",""" +
        """"allowed_output_formats":["m3u8","ts"]}"""

    @Test
    fun legacyBlob_losesCredentials_keepsOutputFormats_andReadersStillWork() = runBlocking {
        seed(legacy)

        assertTrue(settings.scrubCachedUserInfo())

        val text = stored()!!
        assertFalse(text.contains("fake-user"))
        assertFalse(text.contains("fake-pass-123"))
        // The player reads the blob the way PlayerDependencies does.
        val formats = Json.decodeFromString<UserInfoDto>(text).allowed_output_formats
        assertEquals(listOf("m3u8", "ts"), formats)
    }

    @Test
    fun scrub_isIdempotent_andNoOpWithoutABlob() = runBlocking {
        assertFalse(settings.scrubCachedUserInfo())
        seed(legacy)
        assertTrue(settings.scrubCachedUserInfo())
        assertFalse(settings.scrubCachedUserInfo())
    }

    @Test
    fun unreadableBlob_isDropped() = runBlocking {
        seed("not json fake-pass-123")
        assertTrue(settings.scrubCachedUserInfo())
        assertNull(stored())
    }
}
