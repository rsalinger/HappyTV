package com.rysal.happytv.crash

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #259: rotation to the newest five, the size cap, and writes that fail quietly. */
class CrashReportStoreTest {

    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var store: CrashReportStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("crash-store").toFile()
        dir = File(root, CrashReportStore.DIR_NAME)
        store = CrashReportStore(dir)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `only the newest five reports are kept`() {
        for (i in 1..8) store.write(ReportKind.CRASH, 1_700_000_000_000 + i * 1000L, "report $i")
        val kept = store.list()
        assertEquals(5, kept.size)
        assertEquals(listOf("report 8", "report 7", "report 6", "report 5", "report 4"), kept.map { it.file.readText() })
        assertEquals(5, dir.listFiles()!!.size)
    }

    @Test
    fun `crash and exit reports share the limit and sort by time`() {
        store.write(ReportKind.EXIT, 1_700_000_005_000, "exit")
        store.write(ReportKind.CRASH, 1_700_000_001_000, "crash")
        assertEquals(listOf(ReportKind.EXIT, ReportKind.CRASH), store.list().map { it.kind })
        assertEquals(1_700_000_005_000, store.lastReportMillis())
    }

    @Test
    fun `two reports in the same millisecond both survive`() {
        assertNotNull(store.write(ReportKind.CRASH, 1_700_000_000_000, "a"))
        assertNotNull(store.write(ReportKind.CRASH, 1_700_000_000_000, "b"))
        assertEquals(2, store.count())
    }

    @Test
    fun `a report over the cap is cut and says so`() {
        val small = CrashReportStore(dir, maxBytes = 1000)
        val file = small.write(ReportKind.CRASH, 1_700_000_000_000, "x".repeat(5000))!!
        assertTrue(file.length() <= 1000)
        assertTrue(file.readText().endsWith(CrashReportStore.TRUNCATED_NOTE))
    }

    @Test
    fun `the default cap is 256 KB and the cut never splits a character`() {
        assertEquals(256 * 1024, CrashReportStore.MAX_BYTES)
        val file = store.write(ReportKind.CRASH, 1_700_000_000_000, "é".repeat(200_000))!!
        assertTrue(file.length() <= CrashReportStore.MAX_BYTES)
        // Decodes cleanly: no replacement character from a split pair.
        assertFalse(file.readText().contains('�'))
    }

    @Test
    fun `a failing write returns null and does not throw`() {
        // The report folder's parent is a plain file, so mkdirs and the write both fail.
        val blocker = File(root, "blocker").apply { writeText("not a folder") }
        val broken = CrashReportStore(File(blocker, "reports"))
        assertNull(broken.write(ReportKind.CRASH, 1_700_000_000_000, "x"))
        assertTrue(broken.list().isEmpty())
        assertEquals(0, broken.count())
        assertNull(broken.lastReportMillis())
        assertFalse(broken.rewrite(File(blocker, "reports/none.txt"), "x"))
    }

    @Test
    fun `list ignores files that are not reports`() {
        dir.mkdirs()
        File(dir, "notes.txt").writeText("x")
        File(dir, CrashRecorder.LAST_EXIT_FILE).writeText("123")
        File(dir, "crash-garbage.txt").writeText("x")
        store.write(ReportKind.CRASH, 1_700_000_000_000, "real")
        assertEquals(1, store.list().size)
    }

    @Test
    fun `the report time round trips through the file name`() {
        val file = store.write(ReportKind.CRASH, 1_700_000_123_456, "x")!!
        assertEquals("crash-20231114-221523-456.txt", file.name)
        assertEquals(1_700_000_123_456, CrashReportStore.parse(file)!!.timeMillis)
    }

    @Test
    fun `copy writes every kept report into the destination and creates it`() {
        for (i in 1..3) store.write(ReportKind.CRASH, 1_700_000_000_000 + i * 1000L, "report $i")
        val destination = File(root, "Download/HappyTV")
        assertEquals(3, store.copyAllTo(destination))
        assertEquals(3, destination.listFiles()!!.size)
        assertEquals("report 3", File(destination, store.list().first().file.name).readText())
    }

    @Test
    fun `a report written before the secrets were known is masked in the saved copy`() {
        // Written with no known values; the made-up password sits in free text no pattern catches.
        val plain = CrashReportStore(dir)
        plain.write(ReportKind.CRASH, 1_700_000_000_000, "Stack trace\nIOException: bad login for zebra-Quartz-77 on iptv.fake.example")
        val saving = CrashReportStore(
            dir,
            redact = { ReportRedactor.redact(it, listOf("zebra-Quartz-77"), "iptv.fake.example") },
        )
        val out = File(root, "out")
        assertEquals(1, saving.copyAllTo(out))
        val copied = out.listFiles()!!.single().readText()
        assertFalse(copied.contains("zebra-Quartz-77"))
        assertFalse(copied.contains("iptv.fake.example"))
        assertTrue(copied.contains("<server>"))
    }

    @Test
    fun `redacting an already redacted report changes nothing`() {
        val known = listOf("zebra-Quartz-77")
        val once = ReportRedactor.redact(
            "GET http://iptv.fake.example/live/u1/p1/5.ts?password=x pw zebra-Quartz-77 {\"password\":\"q\"} user:pw@h.example Authorization: Basic abc",
            known,
            "iptv.fake.example",
        )
        assertEquals(once, ReportRedactor.redact(once, known, "iptv.fake.example"))
        assertTrue(once.contains("<server>") || once.contains("***"))
    }

    @Test
    fun `copy with nothing to save copies nothing`() {
        assertEquals(0, store.copyAllTo(File(root, "out")))
        assertFalse(File(root, "out").exists())
    }

    @Test
    fun `copy to a destination that cannot be created throws so the caller can say so`() {
        store.write(ReportKind.CRASH, 1_700_000_000_000, "x")
        val blocker = File(root, "blocker").apply { writeText("file") }
        val failed = try {
            store.copyAllTo(File(blocker, "HappyTV"))
            false
        } catch (e: java.io.IOException) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun `the report folder is not named by either backup rule file`() {
        val rules = listOf("backup_rules.xml", "data_extraction_rules.xml")
            .map { File("src/main/res/xml", it).readText() }
        rules.forEach {
            assertFalse(it.contains(CrashReportStore.DIR_NAME))
            assertTrue("include-only rules", it.contains("<include"))
        }
    }
}
