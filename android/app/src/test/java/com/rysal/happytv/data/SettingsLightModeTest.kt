package com.rysal.happytv.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.backup.HouseholdSettings
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

/** #126: the light-mode setting. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsLightModeTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: SettingsRepository

    private fun newStore(tag: String): DataStore<Preferences> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return PreferenceDataStoreFactory.create {
            File(context.cacheDir, "light-mode-$tag-${System.nanoTime()}.preferences_pb")
        }
    }

    @Before
    fun setUp() {
        dataStore = newStore("a")
        settings = SettingsRepository(dataStore)
    }

    @Test
    fun `dark is the default`() = runBlocking {
        assertFalse(settings.settings.first().lightMode)
    }

    @Test
    fun `the choice survives a new repository on the same store`() = runBlocking {
        settings.setLightMode(true)
        assertTrue(SettingsRepository(dataStore).settings.first().lightMode)
        settings.setLightMode(false)
        assertFalse(SettingsRepository(dataStore).settings.first().lightMode)
    }

    @Test
    fun `reset to defaults leaves the choice alone`() = runBlocking {
        settings.setLightMode(true)
        settings.resetUserSettings()
        assertTrue(settings.settings.first().lightMode)
    }

    @Test
    fun `the household backup carries it and a restore fills an unset value only`() = runBlocking {
        assertNull(settings.householdSettingsSnapshot().lightMode)
        settings.setLightMode(true)
        assertEquals(true, settings.householdSettingsSnapshot().lightMode)

        val fresh = SettingsRepository(newStore("r"))
        fresh.importHouseholdSettings(HouseholdSettings(lightMode = true))
        assertTrue(fresh.settings.first().lightMode)
        fresh.importHouseholdSettings(HouseholdSettings(lightMode = false))
        assertTrue(fresh.settings.first().lightMode)
    }
}
