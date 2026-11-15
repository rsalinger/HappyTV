package com.rysal.happytv.crash

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

/** #259: abnormal exits from the system's record on API 30+; nothing, and no crash, below it. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ExitReasonsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun add(reason: Int, time: Long, description: String? = "test") {
        val info = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setPid(123)
            .setReason(reason)
            .setTimestamp(time)
            .setDescription(description)
            .setImportance(100)
            .build()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).addApplicationExitInfo(info)
    }

    @Test
    @Config(sdk = [30])
    fun `on API 30 an ANR, a native crash and low memory are read, oldest first`() {
        add(reason = 6, time = 3_000, description = "ANR: input timed out")
        add(reason = 5, time = 1_000, description = "native crash")
        add(reason = 3, time = 2_000, description = "low memory")
        val records = ExitReasons.read(context, afterMillis = 0)
        assertEquals(listOf("Native crash", "Low memory", "ANR"), records.map { it.reasonName })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), records.map { it.timeMillis })
        assertEquals("ANR: input timed out", records.last().description)
        assertEquals(123, records.first().pid)
    }

    @Test
    @Config(sdk = [30])
    fun `normal exits and Java crashes are not recorded here`() {
        add(reason = 4, time = 1_000) // crash: the handler writes it
        add(reason = 10, time = 2_000) // user requested
        add(reason = 1, time = 3_000) // exit self
        assertTrue(ExitReasons.read(context, afterMillis = 0).isEmpty())
    }

    @Test
    @Config(sdk = [30])
    fun `only exits after the last one seen are returned`() {
        add(reason = 6, time = 1_000)
        add(reason = 6, time = 5_000)
        assertEquals(listOf(5_000L), ExitReasons.read(context, afterMillis = 1_000).map { it.timeMillis })
    }

    @Test
    @Config(sdk = [30])
    fun `an ANR without a trace stream is recorded and the report says the trace is not available`() {
        add(reason = 6, time = 1_000)
        val record = ExitReasons.read(context, afterMillis = 0).single()
        assertNull(record.trace)
        val text = CrashReportText.exit(2_000, ReportEnvironment("1.1.0", 2, "debug", "abc", "TV", "11", 30, 1), record)
        assertTrue(text.contains("Exit reason: ANR"))
        assertTrue(text.contains("ANR trace: not available"))
    }

    @Test
    @Config(sdk = [29])
    fun `below API 30 the read returns nothing and does not touch the missing API`() {
        assertTrue(ExitReasons.read(context, afterMillis = 0).isEmpty())
    }

    @Test
    @Config(sdk = [29])
    fun `on API 29 the launch work and reports still run without the exit part`() {
        val dir = java.nio.file.Files.createTempDirectory("exit-29").toFile()
        try {
            val store = CrashReportStore(java.io.File(dir, "r"))
            val env = ReportEnvironment("1.1.0", 2, "debug", "abc", "TV", "10", 29, 77)
            CrashHandler(store, env, null).uncaughtException(Thread.currentThread(), RuntimeException("x"))
            CrashRecorder(store, env, { listOf("line") }, { ExitReasons.read(context, it) }).processAtLaunch()
            val text = store.list().single().file.readText()
            assertTrue(text.contains(CrashReportText.NO_EXIT_REASONS))
            assertTrue(text.contains("Recent log lines (1,"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
