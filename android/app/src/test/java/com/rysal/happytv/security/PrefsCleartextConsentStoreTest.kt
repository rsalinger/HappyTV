package com.rysal.happytv.security

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #187: consent is per host:port, and pre-#187 bare-host entries stop matching. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PrefsCleartextConsentStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = PrefsCleartextConsentStore.create(context)

    @Test
    fun answerOnOnePort_doesNotCoverAnotherPort() = runBlocking {
        store.clear()
        store.remember("panel.example", 8089, CleartextDecision.UseHttps)

        assertEquals(CleartextDecision.UseHttps, store.decision("panel.example", 8089))
        assertEquals(CleartextDecision.UseHttps, store.decision("PANEL.example", 8089))
        assertNull(store.decision("panel.example", 8090))
        assertNull(store.decision("other.example", 8089))
    }

    @Test
    fun legacyBareHostEntry_noLongerMatches_andClearRemovesIt() = runBlocking {
        val prefs = context.getSharedPreferences(PrefsCleartextConsentStore.PREFS_FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("cleartext:panel.example", "http").commit()

        assertNull(store.decision("panel.example", 80))
        assertNull(store.decision("panel.example", 8080))

        store.clear()
        assertNull(prefs.getString("cleartext:panel.example", null))
    }
}
