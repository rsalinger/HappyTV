package com.rysal.happytv.crash

import android.content.Context
import android.os.Build
import android.os.Process
import com.rysal.happytv.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #259: the uncaught-exception handler. It writes one report file, then hands over to whatever
 * handler was installed before (on Android, the one that shows the "has stopped" state and ends
 * the process), so the app dies exactly as it did without it.
 *
 * Inside the handler the work is kept to a minimum: text is built from the throwable already in
 * hand, redacted, and written with one file write. No logcat, no Context calls, no locks, nothing
 * that can block. Anything that fails is swallowed; the previous handler always runs. A second
 * uncaught exception while the first is being handled is not written again.
 */
class CrashHandler(
    private val store: CrashReportStore,
    private val environment: ReportEnvironment,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val clock: () -> Long = System::currentTimeMillis,
) : Thread.UncaughtExceptionHandler {

    private val handling = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (handling.compareAndSet(false, true)) {
                val now = clock()
                store.write(ReportKind.CRASH, now, CrashReportText.crash(now, environment, thread, error))
            }
        } catch (e: Throwable) {
            // Never throw into the crash path.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }
}

/**
 * #259: installs the handler and does the launch-time work (off the main thread, never blocks
 * launch): adds the recent log lines to the previous crash report, and records abnormal exits the
 * system kept (API 30+).
 *
 * Why the log lines are read at the next launch and not in the handler: the handler runs in a
 * process that may be out of memory or has a stuck thread, and starting a `logcat` process takes
 * time and can fail or hang there. At the next launch `logcat -d --pid=<crashed pid>` still returns
 * that process's lines from the system log buffer, which keeps the last few minutes. The cost: if
 * the buffer has rolled over before the next launch, the report says the lines are not available.
 */
class CrashRecorder(
    val store: CrashReportStore,
    private val environment: ReportEnvironment,
    private val logSource: LogSource = LogcatReader(),
    private val exitSource: (afterMillis: Long) -> List<ExitRecord>,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Applied to the whole report text when log lines are added, with the values known by then. */
    private val redact: (String) -> String = ReportRedactor::redact,
) {

    /** Sets [handler] as the process default, keeping the previous one for chaining. */
    fun install() {
        val handler = CrashHandler(store, environment, Thread.getDefaultUncaughtExceptionHandler(), clock)
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    /** Both launch steps, in order. Safe to call from any background thread; never throws. */
    fun processAtLaunch() {
        try {
            recordAbnormalExits()
        } catch (e: Throwable) {
            // best effort
        }
        try {
            addLogLinesToCrashReports()
        } catch (e: Throwable) {
            // best effort
        }
    }

    /** Writes an exit report for each abnormal exit newer than the last one seen. */
    fun recordAbnormalExits() {
        val marker = File(store.dir, LAST_EXIT_FILE)
        val seen = readMarker(marker) ?: (clock() - FIRST_RUN_LOOKBACK_MILLIS)
        val exits = exitSource(seen)
        if (exits.isEmpty()) return
        for (record in exits) {
            store.write(ReportKind.EXIT, record.timeMillis, CrashReportText.exit(clock(), environment, record))
        }
        try {
            store.dir.mkdirs()
            marker.writeText(exits.maxOf { it.timeMillis }.toString())
        } catch (e: Throwable) {
            // The same exit may be written again next launch; better than losing one.
        }
    }

    /** Replaces the pending-log marker in each crash report with the lines read now. */
    fun addLogLinesToCrashReports() {
        for (report in store.list()) {
            if (report.kind != ReportKind.CRASH) continue
            val text = try {
                report.file.readText()
            } catch (e: Throwable) {
                continue
            }
            if (CrashReportText.LOG_PENDING_MARKER !in text) continue
            val pid = text.lineSequence()
                .firstOrNull { it.startsWith(CrashReportText.LOG_PID_PREFIX) }
                ?.removePrefix(CrashReportText.LOG_PID_PREFIX)?.trim()?.toIntOrNull() ?: -1
            val lines = try {
                if (pid > 0) logSource.linesOf(pid) else null
            } catch (e: Throwable) {
                null
            }
            val section = CrashReportText.logSection(lines)
            store.rewrite(report.file, redact(text.replace(CrashReportText.LOG_PENDING_MARKER, section)))
        }
    }

    private fun readMarker(file: File): Long? = try {
        file.readText().trim().toLongOrNull()
    } catch (e: Throwable) {
        null
    }

    companion object {
        const val LAST_EXIT_FILE = ".last-exit"
        private const val FIRST_RUN_LOOKBACK_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** The recorder for this process, set by [create]. */
        @Volatile
        var current: CrashRecorder? = null
            private set

        fun create(context: Context): CrashRecorder {
            val app = context.applicationContext
            val store = CrashReportStore(File(app.filesDir, CrashReportStore.DIR_NAME), redact = ReportRedactor::redact)
            val environment = ReportEnvironment(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildType = BuildConfig.BUILD_TYPE,
                commit = BuildConfig.GIT_SHA,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                apiLevel = Build.VERSION.SDK_INT,
                pid = Process.myPid(),
            )
            return CrashRecorder(
                store = store,
                environment = environment,
                exitSource = { after -> ExitReasons.read(app, after) },
            ).also { current = it }
        }
    }
}
