package com.rysal.happytv.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/** One abnormal exit, as the system recorded it. [trace] is the ANR trace when it was small. */
data class ExitRecord(
    val reasonName: String,
    val timeMillis: Long,
    val pid: Int,
    val importance: Int,
    val description: String?,
    val trace: String?,
)

/**
 * #259: abnormal exits the Java handler cannot see (ANR, native crash, low memory), read from
 * `ActivityManager.getHistoricalProcessExitReasons` at launch. API 30 and newer only; below that
 * [read] returns an empty list and the crash reports say so.
 */
object ExitReasons {

    /** Largest ANR trace kept, in bytes. A bigger one is left out, not cut. */
    const val MAX_TRACE_BYTES = 64 * 1024

    private const val HISTORY = 10

    /** The system's reason codes (API 30), by number, so this file loads on older Android too. */
    private const val REASON_LOW_MEMORY = 3
    private const val REASON_CRASH_NATIVE = 5
    private const val REASON_ANR = 6
    private const val REASON_EXCESSIVE_RESOURCE_USAGE = 9

    /** Java crashes (reason 4) are written by the handler, so they are not repeated here. */
    internal fun nameOf(reason: Int): String? = when (reason) {
        REASON_ANR -> "ANR"
        REASON_CRASH_NATIVE -> "Native crash"
        REASON_LOW_MEMORY -> "Low memory"
        REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
        else -> null
    }

    /** Abnormal exits newer than [afterMillis], oldest first. Empty below API 30. Never throws. */
    fun read(context: Context, afterMillis: Long, sdkInt: Int = Build.VERSION.SDK_INT): List<ExitRecord> {
        if (sdkInt < Build.VERSION_CODES.R) return emptyList()
        return try {
            readApi30(context, afterMillis)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readApi30(context: Context, afterMillis: Long): List<ExitRecord> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, HISTORY)
            .mapNotNull { info -> toRecord(info, afterMillis) }
            .sortedBy { it.timeMillis }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun toRecord(info: ApplicationExitInfo, afterMillis: Long): ExitRecord? {
        if (info.timestamp <= afterMillis) return null
        val name = nameOf(info.reason) ?: return null
        val trace = if (info.reason == REASON_ANR) readTrace(info) else null
        return ExitRecord(
            reasonName = name,
            timeMillis = info.timestamp,
            pid = info.pid,
            importance = info.importance,
            description = info.description,
            trace = trace,
        )
    }

    /** The ANR trace stream, or null when it is missing or larger than [MAX_TRACE_BYTES]. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use { stream ->
            val bytes = stream.readNBytesCompat(MAX_TRACE_BYTES + 1)
            if (bytes.size > MAX_TRACE_BYTES) null else String(bytes, Charsets.UTF_8)
        }
    } catch (e: Throwable) {
        null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
