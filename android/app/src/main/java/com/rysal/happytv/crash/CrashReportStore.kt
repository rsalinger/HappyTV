package com.rysal.happytv.crash

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What made a report. */
enum class ReportKind(val prefix: String) {
    /** An uncaught Java or Kotlin exception, written by the handler. */
    CRASH("crash"),

    /** An abnormal exit the system recorded (ANR, native crash, low memory), read at launch. */
    EXIT("exit"),
}

/** One report file on disk. [timeMillis] comes from the file name. */
data class StoredReport(val file: File, val kind: ReportKind, val timeMillis: Long)

/**
 * #259: the report folder, `files/crash-reports/` in app-private storage.
 *
 * It lives next to the household backup file but is not named by `backup_rules.xml` or
 * `data_extraction_rules.xml` (both include only `household_backup.json`), so Android backup and
 * device transfer never carry it, and the household backup file is built from the database and
 * settings only. Keeps the newest [maxReports]; each file is cut at [maxBytes].
 *
 * Every public method that the crash path or launch uses is safe to call from any thread and
 * never throws; a failed write returns false (or null) instead.
 */
class CrashReportStore(
    val dir: File,
    private val maxReports: Int = MAX_REPORTS,
    private val maxBytes: Int = MAX_BYTES,
    /** Applied to a report's text when it leaves the folder (copy). Production passes [ReportRedactor.redact] so the values known by then are used. */
    private val redact: (String) -> String = { it },
) {

    /**
     * Writes [text] as a new report of [kind] at [timeMillis], then deletes the oldest beyond
     * the limit. Returns the file, or null when the write failed. Never throws.
     */
    fun write(kind: ReportKind, timeMillis: Long, text: String): File? = try {
        dir.mkdirs()
        val file = uniqueFile(kind, timeMillis)
        file.writeBytes(capped(text))
        prune()
        file
    } catch (e: Throwable) {
        null
    }

    /** Replaces the content of an existing report (used when the log lines are added at launch). Never throws. */
    fun rewrite(file: File, text: String): Boolean = try {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(capped(text))
        if (!temp.renameTo(file)) {
            file.writeBytes(capped(text))
            temp.delete()
        }
        true
    } catch (e: Throwable) {
        false
    }

    /** All reports, newest first. Never throws. */
    fun list(): List<StoredReport> = try {
        dir.listFiles()
            .orEmpty()
            .mapNotNull { parse(it) }
            .sortedWith(compareByDescending<StoredReport> { it.timeMillis }.thenByDescending { it.file.name })
    } catch (e: Throwable) {
        emptyList()
    }

    /** The time of the newest report, or null when there are none. Reads only file names. */
    fun lastReportMillis(): Long? = list().firstOrNull()?.timeMillis

    fun count(): Int = list().size

    /** Copies every kept report into [folder] (created when missing); returns how many were copied. Throws [IOException] on failure. */
    fun copyAllTo(folder: File): Int {
        val reports = list()
        if (reports.isEmpty()) return 0
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) {
            throw IOException("cannot create the destination folder")
        }
        for (report in reports) {
            File(folder, report.file.name).writeBytes(capped(redact(report.file.readText())))
        }
        return reports.size
    }

    private fun prune() {
        list().drop(maxReports).forEach { it.file.delete() }
        // Half-written temp files from an interrupted rewrite.
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun uniqueFile(kind: ReportKind, timeMillis: Long): File {
        var millis = timeMillis
        while (true) {
            val candidate = File(dir, nameFor(kind, millis))
            if (!candidate.exists()) return candidate
            millis += 1
        }
    }

    /** Cuts [text] to [maxBytes] of UTF-8 at a character boundary and says so. */
    internal fun capped(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        val note = TRUNCATED_NOTE.toByteArray(Charsets.UTF_8)
        var end = (maxBytes - note.size).coerceAtLeast(0)
        // Do not stop in the middle of a multi-byte character.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end) + note
    }

    companion object {
        const val DIR_NAME = "crash-reports"
        const val MAX_REPORTS = 5
        const val MAX_BYTES = 256 * 1024
        const val TRUNCATED_NOTE = "\n[report cut at the size limit]\n"

        private const val STAMP = "yyyyMMdd-HHmmss-SSS"

        private fun format() = SimpleDateFormat(STAMP, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

        /** `crash-20261112-153045-123.txt`: UTC time in the name, so ordering and the last-crash time need no file read. */
        fun nameFor(kind: ReportKind, timeMillis: Long): String =
            "${kind.prefix}-${format().format(Date(timeMillis))}.txt"

        fun parse(file: File): StoredReport? {
            val name = file.name
            if (!file.isFile || !name.endsWith(".txt")) return null
            val kind = ReportKind.entries.firstOrNull { name.startsWith(it.prefix + "-") } ?: return null
            val stamp = name.removePrefix(kind.prefix + "-").removeSuffix(".txt")
            val time = try {
                format().parse(stamp)?.time
            } catch (e: java.text.ParseException) {
                null
            } ?: return null
            return StoredReport(file, kind, time)
        }
    }
}
