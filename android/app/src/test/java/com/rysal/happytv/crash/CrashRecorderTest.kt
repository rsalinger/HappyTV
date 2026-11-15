package com.rysal.happytv.crash

import com.rysal.happytv.security.ChannelLogoGuard
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #259: the handler, its chaining, and the launch-time work. Hosts and credentials are made up. */
class CrashRecorderTest {

    private val env = ReportEnvironment("1.1.0", 2, "debug", "abc1234", "Acme TV", "14", 34, 4242)
    private val user = "alice_demo"
    private val pass = "s3cr3tPass"
    private lateinit var root: File
    private lateinit var store: CrashReportStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("crash-recorder").toFile()
        store = CrashReportStore(File(root, "crash-reports"))
        ChannelLogoGuard.update(user, pass)
    }

    @After
    fun tearDown() {
        ChannelLogoGuard.update(null, null)
        ReportRedactor.knownHost = null
        root.deleteRecursively()
    }

    private class Recording : Thread.UncaughtExceptionHandler {
        val seen = mutableListOf<Pair<Thread, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) {
            seen += t to e
        }
    }

    // --------------------------------------------------------------- handler

    @Test
    fun `the handler writes a report then hands the same thread and error to the previous handler`() {
        val previous = Recording()
        val handler = CrashHandler(store, env, previous, clock = { 1_700_000_000_000 })
        val thread = Thread.currentThread()
        val error = IllegalStateException("boom")

        handler.uncaughtException(thread, error)

        assertEquals(1, previous.seen.size)
        assertSame(thread, previous.seen[0].first)
        assertSame(error, previous.seen[0].second)
        val text = store.list().single().file.readText()
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains(CrashReportText.LOG_PENDING_MARKER))
    }

    @Test
    fun `a failing write does not throw and the previous handler still runs`() {
        val blocker = File(root, "blocker").apply { writeText("file") }
        val broken = CrashReportStore(File(blocker, "reports"))
        val previous = Recording()
        CrashHandler(broken, env, previous).uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertEquals(1, previous.seen.size)
    }

    @Test
    fun `an error while building the report does not stop the chain`() {
        val previous = Recording()
        val throwing = object : RuntimeException() {
            override val message: String get() = throw OutOfMemoryError("simulated")
        }
        CrashHandler(store, env, previous).uncaughtException(Thread.currentThread(), throwing)
        assertEquals(1, previous.seen.size)
    }

    @Test
    fun `no previous handler is fine`() {
        CrashHandler(store, env, null).uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertEquals(1, store.count())
    }

    @Test
    fun `a second crash while the first is handled is chained but not written twice`() {
        val previous = Recording()
        val handler = CrashHandler(store, env, previous)
        handler.uncaughtException(Thread.currentThread(), RuntimeException("first"))
        handler.uncaughtException(Thread.currentThread(), RuntimeException("second"))
        assertEquals(2, previous.seen.size)
        assertEquals(1, store.count())
    }

    @Test
    fun `install keeps the previous default handler in the chain`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val previous = Recording()
        Thread.setDefaultUncaughtExceptionHandler(previous)
        try {
            CrashRecorder(store, env, { null }, { emptyList() }).install()
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertTrue(installed is CrashHandler)
            installed!!.uncaughtException(Thread.currentThread(), RuntimeException("chained"))
            assertEquals(1, previous.seen.size)
            assertEquals(1, store.count())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `the report written by the handler carries no credential`() {
        val error = RuntimeException("GET http://other.example/live/$user/$pass/1.m3u8 failed")
        CrashHandler(store, env, null).uncaughtException(Thread.currentThread(), error)
        val text = store.list().single().file.readText()
        assertFalse(text.contains(user))
        assertFalse(text.contains(pass))
    }

    // -------------------------------------------------------- launch: logs

    @Test
    fun `the next launch adds the redacted log lines of the crashed process`() {
        CrashHandler(store, env, null, clock = { 1_700_000_000_000 })
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        var askedPid = -1
        val source = LogSource { pid ->
            askedPid = pid
            listOf(
                "11-12 20:00:00.000  4242  4242 I Player: url=http://other.example/live/$user/$pass/1.ts",
                "11-12 20:00:01.000  4242  4242 D Tag: channel Cartoon Network HD",
            )
        }
        CrashRecorder(store, env, source, { emptyList() }).addLogLinesToCrashReports()

        assertEquals(4242, askedPid)
        val text = store.list().single().file.readText()
        assertFalse(text.contains(CrashReportText.LOG_PENDING_MARKER))
        assertTrue(text.contains("Recent log lines (2,"))
        assertTrue(text.contains("Cartoon Network HD"))
        assertFalse(text.contains(user))
        assertFalse(text.contains(pass))
    }

    @Test
    fun `adding log lines redacts the whole report, not only the new section`() {
        // Handler wrote it before the sign-in was known.
        ChannelLogoGuard.update(null, null)
        CrashHandler(store, env, null).uncaughtException(Thread.currentThread(), RuntimeException("failed for zebra-Quartz-77"))
        assertTrue(store.list().single().file.readText().contains("zebra-Quartz-77"))

        ChannelLogoGuard.update("zebra-Quartz-77", null)
        CrashRecorder(store, env, { listOf("ok") }, { emptyList() }).addLogLinesToCrashReports()
        assertFalse(store.list().single().file.readText().contains("zebra-Quartz-77"))
    }

    @Test
    fun `when the log has rolled over the report says the lines are not available`() {
        CrashHandler(store, env, null).uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        CrashRecorder(store, env, { null }, { emptyList() }).addLogLinesToCrashReports()
        val text = store.list().single().file.readText()
        assertTrue(text.contains("Recent log lines: not available"))
        assertFalse(text.contains(CrashReportText.LOG_PENDING_MARKER))
    }

    @Test
    fun `a log source that throws leaves a finished report`() {
        CrashHandler(store, env, null).uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        CrashRecorder(store, env, { throw SecurityException("no") }, { emptyList() }).processAtLaunch()
        assertTrue(store.list().single().file.readText().contains("not available"))
    }

    @Test
    fun `a finished report is not read from the log again`() {
        CrashHandler(store, env, null).uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        var calls = 0
        val recorder = CrashRecorder(store, env, { calls++; listOf("line") }, { emptyList() })
        recorder.addLogLinesToCrashReports()
        recorder.addLogLinesToCrashReports()
        assertEquals(1, calls)
    }

    // ---------------------------------------------------- launch: exits

    private fun anr(time: Long) = ExitRecord(
        reasonName = "ANR", timeMillis = time, pid = 99, importance = 100,
        description = "user request after error: Input dispatching timed out (http://other.example/live/$user/$pass/1.ts)",
        trace = "\"main\" prio=5 tid=1 Blocked\n  at Foo.bar(Foo.java:1) http://other.example/get.php?username=$user&password=$pass",
    )

    @Test
    fun `an abnormal exit is recorded once, redacted, with its own time`() {
        var asked = -1L
        val recorder = CrashRecorder(
            store, env, { null },
            exitSource = { after ->
                asked = after
                listOf(anr(1_700_000_050_000)).filter { it.timeMillis > after }
            },
            clock = { 1_700_000_100_000 },
        )
        recorder.recordAbnormalExits()
        val report = store.list().single()
        assertEquals(ReportKind.EXIT, report.kind)
        assertEquals(1_700_000_050_000, report.timeMillis)
        val text = report.file.readText()
        assertTrue(text.contains("Exit reason: ANR"))
        assertTrue(text.contains("ANR trace"))
        assertFalse(text.contains(user))
        assertFalse(text.contains(pass))

        // A second launch asks from the newest exit seen, so it is not written again.
        recorder.recordAbnormalExits()
        assertEquals(1_700_000_050_000, asked)
        assertEquals(1, store.count())
    }

    @Test
    fun `the first launch looks back a week and no further`() {
        var asked = -1L
        CrashRecorder(store, env, { null }, { after -> asked = after; emptyList() }, clock = { 10_000_000_000 })
            .recordAbnormalExits()
        assertEquals(10_000_000_000 - 7L * 24 * 60 * 60 * 1000, asked)
        assertNull(store.lastReportMillis())
    }

    @Test
    fun `an exit source that throws does not stop launch work`() {
        CrashHandler(store, env, null).uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        CrashRecorder(store, env, { listOf("a") }, { throw IllegalStateException("system") }).processAtLaunch()
        assertTrue(store.list().single().file.readText().contains("Recent log lines (1,"))
        assertNotNull(store.lastReportMillis())
    }
}
