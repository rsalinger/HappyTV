package com.rysal.happytv.data.guide

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #200: below API 31 WorkManager runs expedited work as a foreground service
 * and asks the worker for [androidx.work.ForegroundInfo]. The CoroutineWorker
 * default threw "Not implemented" and crash-looped Android 11 and older.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RefreshGuideForegroundInfoTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun foregroundInfoViaWorker() = runBlocking {
        TestListenableWorkerBuilder<RefreshGuideWorker>(context).build().getForegroundInfo()
    }

    private fun assertQuiet() {
        val info = foregroundInfoViaWorker()
        assertEquals(RefreshGuideWorker.NOTIFICATION_ID, info.notificationId)
        val n = info.notification
        val title = n.extras.getCharSequence("android.title").toString()
        assertEquals("Updating the guide", title)
        assertFalse(title.contains("!"))
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(RefreshGuideWorker.NOTIFICATION_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(RefreshGuideWorker.NOTIFICATION_CHANNEL_ID, n.channelId)
    }

    @Test
    @Config(sdk = [30])
    fun api30_workerAnswersForegroundInfoWithAQuietChannel() = assertQuiet()

    @Test
    @Config(sdk = [34])
    fun api34_foregroundInfoIsTypedDataSync() {
        assertQuiet()
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            foregroundInfoViaWorker().foregroundServiceType,
        )
    }

    /** An already-persisted expedited request (older build) reaches the same override. */
    @Test
    @Config(sdk = [30])
    fun api30_persistedExpeditedRequestStillGetsForegroundInfo() {
        val request = OneTimeWorkRequestBuilder<RefreshGuideWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        assertTrue(request.workSpec.expedited)
        val worker = TestListenableWorkerBuilder<RefreshGuideWorker>(context)
            .setId(request.id)
            .build()
        val info = runBlocking { worker.getForegroundInfo() }
        assertEquals(RefreshGuideWorker.NOTIFICATION_ID, info.notificationId)
    }

    @Test
    @Config(sdk = [34])
    fun manifestDeclaresDataSyncForegroundServicePermissionAndService() {
        val pkg = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        )
        val perms = pkg.requestedPermissions.orEmpty().toList()
        assertTrue(perms.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(perms.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        val svc = pkg.services.orEmpty()
            .firstOrNull { it.name == "androidx.work.impl.foreground.SystemForegroundService" }
        assertNotNull("SystemForegroundService must be merged in", svc)
        assertTrue(svc!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }
}
