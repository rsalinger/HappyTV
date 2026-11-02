package com.rysal.happytv.ui.login

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.MainActivity
import com.rysal.happytv.data.Graph
import com.rysal.happytv.security.FakeAndroidKeyStore
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Issue #30: a launcher start with stored credentials opens the dashboard and
 * never shows sign-in; without them (or with the explicit extra) it shows
 * sign-in. Uses the real [Graph.securedCredentials] over the fake Keystore, the
 * same harness as `RefreshGuideTriggerTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LoginActivityLaunchRoutingTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun installFakeAndroidKeyStore() {
            FakeAndroidKeyStore.install()
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        Graph.init(context)
        runBlocking { Graph.securedCredentials.wipe() }
    }

    @After
    fun tearDown() {
        runBlocking { Graph.securedCredentials.wipe() }
    }

    private fun storeCredentials() = runBlocking {
        Graph.securedCredentials.save(
            host = "provider.invalid",
            port = 443,
            useTls = true,
            username = "user",
            password = "not-a-real-password",
        )
    }

    /** Creates the activity and pumps the main looper until the routing decision lands. */
    private fun launch(intent: Intent): LoginActivity {
        val controller = Robolectric.buildActivity(LoginActivity::class.java, intent).setup()
        val activity = controller.get()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (activity.isFinishing || contentOf(activity).childCount > 0) break
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun contentOf(activity: LoginActivity) =
        activity.findViewById<ViewGroup>(android.R.id.content)

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClass(context, LoginActivity::class.java)

    @Test
    fun `stored credentials open the dashboard and finish without composing sign in`() {
        storeCredentials()

        val activity = launch(launcherIntent())

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("MainActivity was not started", started)
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertTrue("LoginActivity should finish", activity.isFinishing)
        assertEquals("no sign-in content may be composed", 0, contentOf(activity).childCount)
    }

    @Test
    fun `no stored credentials show sign in`() {
        val activity = launch(launcherIntent())

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
        assertTrue("sign-in content should be composed", contentOf(activity).childCount > 0)
    }

    @Test
    fun `explicit show-sign-in extra shows sign in even with stored credentials`() {
        storeCredentials()

        val activity = launch(LoginActivity.signInIntent(context))

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
        assertTrue("sign-in content should be composed", contentOf(activity).childCount > 0)
    }

    @Test
    fun `signInIntent targets LoginActivity with the extra set`() {
        val intent = LoginActivity.signInIntent(context)
        assertEquals(LoginActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(LoginActivity.EXTRA_SHOW_SIGN_IN, false))
    }
}
