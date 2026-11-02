package com.rysal.happytv.ui.login

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Issue #20: only the adb shell (uid 2000) or root (uid 0) may inject
 * credentials. Two layers, both pinned here: the pure sender policy fed with
 * [android.content.BroadcastReceiver.getSentFromUid] on API 34+, and the
 * broadcast permission on the registration (checked by the system, so the
 * JVM can only assert it was requested).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AdbLoginInjectionSenderTest {

    @Test
    fun policy_acceptsShellAndRoot() {
        assertTrue(InjectionSenderPolicy.isTrusted(2000))
        assertTrue(InjectionSenderPolicy.isTrusted(0))
    }

    @Test
    fun policy_rejectsOrdinaryAppsAndSystemUids() {
        assertFalse(InjectionSenderPolicy.isTrusted(10123))
        assertFalse(InjectionSenderPolicy.isTrusted(10001))
        assertFalse(InjectionSenderPolicy.isTrusted(1000))
        assertFalse(InjectionSenderPolicy.isTrusted(1))
    }

    @Test
    fun policy_unknownSender_fallsBackToThePermissionLayer() {
        assertTrue(InjectionSenderPolicy.isTrusted(null))
        assertTrue(InjectionSenderPolicy.isTrusted(-1))
    }

    @Test
    fun register_requiresTheShellOnlyBroadcastPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AdbLoginInjection.register(app)

        val ours = shadowOf(app).registeredReceivers.filter {
            it.intentFilter.hasAction(AdbLoginInjection.ACTION_INJECT_CREDENTIALS)
        }
        assertTrue("receiver registered in debug builds", ours.isNotEmpty())
        ours.forEach {
            assertEquals("android.permission.DUMP", it.broadcastPermission)
        }
    }
}
