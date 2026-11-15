package com.rysal.happytv.crash

import android.os.Build

/** Where recent log lines come from. The real one is [LogcatReader]; tests use a fake. */
fun interface LogSource {
    /** The last log lines the app itself wrote while it ran as process [pid], oldest first; null when unavailable. */
    fun linesOf(pid: Int): List<String>?
}

/**
 * #259: the app's own logcat. An app may read its own lines without a permission: `logcat -d`
 * only returns this UID's entries. `--pid` (API 24+) narrows it to the process that crashed;
 * on API 23 the last lines of the app's own log are used as they are.
 *
 * Runs a short-lived `logcat` process with a time limit, so it is only called off the main thread
 * at launch, never from the crash handler. Returns null on any failure.
 */
class LogcatReader(
    private val maxLines: Int = MAX_LINES,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : LogSource {

    override fun linesOf(pid: Int): List<String>? {
        var process: Process? = null
        return try {
            val command = buildList {
                add("logcat"); add("-d"); add("-v"); add("threadtime"); add("-t"); add(maxLines.toString())
                if (sdkInt >= Build.VERSION_CODES.N && pid > 0) add("--pid=$pid")
            }
            val started = ProcessBuilder(command).redirectErrorStream(true).start()
            process = started
            val collected = ArrayList<String>()
            val reader = Thread {
                try {
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(collected) {
                                if (collected.size >= maxLines) collected.removeAt(0)
                                collected.add(line)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // The process was stopped at the time limit; keep what was read.
                }
            }
            reader.isDaemon = true
            reader.start()
            reader.join(timeoutMillis)
            synchronized(collected) { collected.toList() }.ifEmpty { null }
        } catch (e: Throwable) {
            null
        } finally {
            try {
                process?.destroy()
            } catch (e: Throwable) {
                // nothing to do
            }
        }
    }

    companion object {
        const val MAX_LINES = 400
        const val TIMEOUT_MILLIS = 3_000L
    }
}
