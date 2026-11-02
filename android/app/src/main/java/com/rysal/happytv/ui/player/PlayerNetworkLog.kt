@file:OptIn(androidx.media3.common.util.UnstableApi::class)
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.rysal.happytv.ui.player

import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.rysal.happytv.BuildConfig

/**
 * Proof-of-request logging for playback (F-51: "the request reaches the
 * network — log it"). Every transfer Media3 starts is logged with its method
 * and URI, credentials redacted through [PlayerUrlBuilder.redactCredentials]
 * (netsec F-10), debug builds only (netsec F-09's house rule).
 *
 * This is the network half of the playback story: the player's own state logs
 * say *whether* the pipeline ran; these lines say *what it asked for* and
 * whether the request left the device at all. When "play does nothing",
 * `adb logcat -s PlayerNetwork` answers it in one glance: no line = the
 * request never fired (URL/state problem upstream); a line = the panel was
 * asked and the failure is on the response/decoder side.
 *
 * Wired in [PlayerFactory.mediaSource] on the shared DefaultDataSource
 * factory, so both HLS and raw-container playback report their transfers.
 */
internal class PlayerNetworkLog : TransferListener {

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!BuildConfig.DEBUG) return
        val method = DataSpec.getStringForHttpMethod(dataSpec.httpMethod)
        Log.i(TAG, "→ $method ${PlayerUrlBuilder.redactCredentials(dataSpec.uri.toString())}")
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        // Headers are in; the "← done" line below is the paired completion.
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        // Per-chunk noise; the start/end pair is all the evidence needs.
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "← done ${PlayerUrlBuilder.redactCredentials(dataSpec.uri.toString())}")
    }

    companion object {
        const val TAG = "PlayerNetwork"
    }
}
