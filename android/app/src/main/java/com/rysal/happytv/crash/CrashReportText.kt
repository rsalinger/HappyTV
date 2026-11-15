package com.rysal.happytv.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Facts about the build and the device, filled in once at startup. No account data. */
data class ReportEnvironment(
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val commit: String,
    val deviceModel: String,
    val androidRelease: String,
    val apiLevel: Int,
    val pid: Int,
)

/** Builds the text of a report. Everything returned has been through [ReportRedactor]. */
object CrashReportText {

    /** Line in a crash report that the next launch replaces with the app's recent log lines. */
    const val LOG_PENDING_MARKER = "[recent log lines: added at the next launch]"
    const val LOG_PID_PREFIX = "Process id: "
    const val LOG_HEADING = "Recent log lines"

    fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun header(title: String, millis: Long, env: ReportEnvironment): StringBuilder = StringBuilder().apply {
        appendLine("HappyTV $title")
        appendLine("Time: ${timestamp(millis)}")
        appendLine("App version: ${env.versionName} (${env.versionCode})")
        appendLine("Build type: ${env.buildType}")
        appendLine("Commit: ${env.commit}")
        appendLine("Device: ${env.deviceModel}")
        appendLine("Android: ${env.androidRelease} (API ${env.apiLevel})")
        if (env.apiLevel < 30) appendLine(NO_EXIT_REASONS)
        appendLine("$LOG_PID_PREFIX${env.pid}")
    }

    /**
     * The report for an uncaught exception. Small and quick: no logcat here (see [CrashReports]);
     * the log section is a marker that the next launch fills in.
     */
    fun crash(millis: Long, env: ReportEnvironment, thread: Thread, error: Throwable): String {
        val out = header("crash report", millis, env)
        out.appendLine("Thread: ${thread.name}")
        out.appendLine()
        out.appendLine("Stack trace")
        out.appendLine(ReportRedactor.redact(stackTrace(error)))
        out.appendLine()
        out.appendLine(LOG_PENDING_MARKER)
        return out.toString()
    }

    /** The report for an abnormal exit the system recorded (API 30+). */
    fun exit(millis: Long, env: ReportEnvironment, exit: ExitRecord): String {
        val out = header("exit report", millis, env)
        out.appendLine("Exit reason: ${exit.reasonName}")
        out.appendLine("Exit time: ${timestamp(exit.timeMillis)}")
        out.appendLine("Exited process id: ${exit.pid}")
        out.appendLine("Importance: ${exit.importance}")
        out.appendLine("Description: ${ReportRedactor.redact(exit.description ?: "(none)")}")
        out.appendLine()
        if (exit.trace != null) {
            out.appendLine("ANR trace")
            out.appendLine(ReportRedactor.redact(exit.trace))
        } else if (exit.reasonName == "ANR") {
            out.appendLine("ANR trace: not available")
        }
        return out.toString()
    }

    /** A note for the report written when the platform has no exit reasons (below Android 11). */
    const val NO_EXIT_REASONS = "Exit reasons: not available below Android 11 (API 30)"

    /** The throwable and its causes, as printStackTrace shows them. Redaction is the caller's. */
    fun stackTrace(error: Throwable): String = try {
        error.stackTraceToString()
    } catch (e: Throwable) {
        // A throwing toString() or getMessage() on a custom exception.
        "${error.javaClass.name} (details unavailable)"
    }

    /** The section appended once the log lines have been read. [lines] are redacted here. */
    fun logSection(lines: List<String>?): String {
        if (lines == null || lines.isEmpty()) {
            return "$LOG_HEADING: not available (the system log no longer had them)"
        }
        return buildString {
            appendLine("$LOG_HEADING (${lines.size}, read at the next launch from this app's own log)")
            lines.forEach { appendLine(ReportRedactor.redact(it)) }
        }.trimEnd('\n')
    }
}
