package com.rysal.happytv.crash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.rysal.happytv.BuildConfig

/**
 * Debug-build-only way to make the app crash on purpose, so QA can see a crash report appear.
 *
 * ```
 * adb shell am broadcast -a com.rysal.happytv.FORCE_CRASH -p com.rysal.happytv \
 *     [--es message "text for the exception"]
 * ```
 *
 * Registered in code and only when [BuildConfig.DEBUG] is true, like `AdbLoginInjection`, so the
 * action does not exist in benchmark or release builds. The sender must hold
 * `android.permission.DUMP` (the shell and root have it, other apps cannot get it).
 *
 * On the benchmark and release builds QA uses `adb shell am crash com.rysal.happytv` instead: the
 * system throws an exception inside the app's main thread, which reaches the same handler.
 */
object DebugForcedCrash {
    const val ACTION_FORCE_CRASH = "com.rysal.happytv.FORCE_CRASH"
    const val EXTRA_MESSAGE = "message"
    private const val TAG = "DebugForcedCrash"
    private const val PERMISSION = "android.permission.DUMP"

    val enabled: Boolean get() = BuildConfig.DEBUG

    fun register(appContext: Context) {
        if (!enabled) return
        ContextCompat.registerReceiver(
            appContext,
            Receiver(),
            IntentFilter(ACTION_FORCE_CRASH),
            PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "forced-crash receiver registered (debug build)")
    }

    private class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_FORCE_CRASH) return
            val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Forced crash for QA"
            throw IllegalStateException(message, RuntimeException("cause of the forced crash"))
        }
    }
}
