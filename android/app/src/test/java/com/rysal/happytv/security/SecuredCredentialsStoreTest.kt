package com.rysal.happytv.security

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round-trips a credential set through the store the app actually uses —
 * [SecuredCredentialsStore.create], i.e. real `EncryptedSharedPreferences`.
 *
 * `@Config(application = Application::class)` pins the plain framework
 * Application so app-startup code (Graph wiring, cert pinning) stays out of
 * scope — this test is about credential storage.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SecuredCredentialsStoreTest {

    companion object {
        /**
         * Robolectric ships no `AndroidKeyStore` JCA provider, so
         * `EncryptedSharedPreferences` (MasterKey + Tink) cannot resolve it and
         * every store test would die with `NoSuchAlgorithmException`. The
         * test-only fake provider restores that one capability — everything
         * else (AES-GCM encryption, the keyset, the prefs file) is the real
         * AndroidX Security/Tink code path.
         */
        @BeforeClass
        @JvmStatic
        fun installFakeAndroidKeyStore() {
            FakeAndroidKeyStore.install()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store: SecuredCredentialsStore = SecuredCredentialsStore.create(context)

    @Before
    fun startEmpty() = runBlocking {
        store.wipe()
    }

    @After
    fun endEmpty() = runBlocking {
        store.wipe()
    }

    @Test
    fun roundTripsACredentialSet() = runBlocking<Unit> {
        assertFalse(store.isPresent())
        assertNull(store.current())

        val savedAfterUtcMillis = System.currentTimeMillis()
        store.save(
            host = "panel.example.com",
            port = 8080,
            useTls = false,
            username = "user42",
            password = "correct horse battery staple",
        )
        assertTrue(store.isPresent())

        val loaded = store.current()
        assertNotNull(loaded)
        loaded!!
        assertEquals("panel.example.com", loaded.host)
        assertEquals(8080, loaded.port)
        assertFalse(loaded.useTls)
        assertEquals("user42", loaded.username)
        assertEquals("correct horse battery staple", loaded.password)
        assertTrue(
            "savedAtUtcMillis ${loaded.savedAtUtcMillis} should be ~now",
            loaded.savedAtUtcMillis in (savedAfterUtcMillis - 1_000)..(System.currentTimeMillis() + 1_000),
        )

        // A second save REPLACES the set — no host/user history accumulates.
        store.save("other.example.com", 443, true, "user43", "s3cond-pass")
        val replaced = store.current()!!
        assertEquals("other.example.com", replaced.host)
        assertEquals(443, replaced.port)
        assertTrue(replaced.useTls)
        assertEquals("user43", replaced.username)
        assertEquals("s3cond-pass", replaced.password)

        store.wipe()
        assertFalse(store.isPresent())
        assertNull(store.current())
    }

    /**
     * A known bug in other players: a second, plaintext copy of the password sitting in
     * ordinary SharedPreferences. After a save, NOTHING under `shared_prefs/`
     * may contain the password (or the host/user) as readable text.
     */
    @Test
    fun neverWritesPlaintextToDisk() = runBlocking<Unit> {
        val password = "hunter2-plaintext-canary"
        val username = "canary-user-42"
        store.save("canary.example.com", 8080, false, username, password)

        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val files = sharedPrefsDir.listFiles().orEmpty()
        assertTrue(
            "expected ${SecuredCredentialsStore.PREFS_FILE_NAME}.xml on disk, " +
                "found ${files.map { it.name }}",
            files.any { it.name == "${SecuredCredentialsStore.PREFS_FILE_NAME}.xml" },
        )

        for (file in files) {
            val text = file.readText()
            assertFalse("${file.name} contains the plaintext password", text.contains(password))
            assertFalse("${file.name} contains the plaintext username", text.contains(username))
            assertFalse("${file.name} contains the plaintext host", text.contains("canary.example.com"))
        }
    }

    /**
     * A half-written set is absent, not half-usable — exercises the schema
     * logic against injected plain prefs (the Keystore path is covered above).
     */
    @Test
    fun treatsAnIncompleteSetAsAbsent() = runBlocking<Unit> {
        val partial: SharedPreferences = context.getSharedPreferences("partial_probe", Context.MODE_PRIVATE)
        partial.edit().clear().putString("host", "panel.example.com").commit()
        val partialStore = SecuredCredentialsStore(prefsFactory = { partial })
        assertFalse(partialStore.isPresent())
        assertNull(partialStore.current())

        // …and a full plain set round-trips, proving the logic is prefs-agnostic.
        partialStore.save("panel.example.com", 8080, true, "u", "p")
        assertEquals("panel.example.com", partialStore.current()?.host)
        assertEquals(8080, partialStore.current()?.port)
        assertTrue(partialStore.current()?.useTls == true)
        partialStore.wipe()
        assertNull(partialStore.current())
    }

    @Test
    fun epgOverride_roundTripsAndWipesWithTheAccount() = runBlocking<Unit> {
        assertNull(store.get())
        store.set("https://guide.example/x.xml?password=hunter2")
        assertEquals("https://guide.example/x.xml?password=hunter2", store.get())
        store.wipe()
        assertNull(store.get())
    }
}
