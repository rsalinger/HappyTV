package com.rysal.happytv.data

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Where bulk background work runs (#43): the catalog refresh and the XMLTV
 * import.
 *
 * Both are CPU- and IO-heavy for tens of seconds on a 4-core Chromecast, and on
 * `Dispatchers.IO` they compete with the UI thread at normal priority (up to 64
 * threads). This is a small fixed pool of minimum-priority daemon threads
 * (ART maps `Thread.MIN_PRIORITY` to the lowest nice level), so the scheduler
 * hands the cores to input and rendering first. Never the main thread.
 */
object BackgroundWork {
    private const val THREADS = 3

    val bulk: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(THREADS) { runnable ->
            Thread(runnable, "htv-bulk").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.asCoroutineDispatcher()
    }
}
