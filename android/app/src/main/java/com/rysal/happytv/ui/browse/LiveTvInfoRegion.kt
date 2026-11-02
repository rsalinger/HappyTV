package com.rysal.happytv.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.rysal.happytv.data.guide.GuideProgram
import com.rysal.happytv.ui.guide.GuideFocus
import com.rysal.happytv.ui.guide.GuideRow
import com.rysal.happytv.ui.guide.InfoBandUi
import com.rysal.happytv.ui.guide.infoBandFor
import kotlinx.coroutines.delay

/**
 * The info band and the preview slot: what the focused item is, and its art.
 *
 * This is its own composable so that **a focus move recomposes only this**. The
 * focused guide element / list channel arrive as [State] objects and are read
 * here, not in `LiveTvContent`: reading them there recomposed the whole screen
 * (header, segments, the guide body with its fresh lambdas) on every D-pad Down,
 * about 6 ms of the ~15 ms main-thread budget of a held-key guide scroll (#52).
 *
 * The description is **not** part of the batched now lookup (#32): one single-row
 * read for the focused program, debounced on focus changes, off the main thread.
 *
 * @param focusedCategory the rail row with focus, which the band describes instead
 *   while the rail has the D-pad.
 */
@Composable
internal fun LiveTvInfoRegion(
    guideMode: Boolean,
    guideRows: List<GuideRow>,
    channels: List<LiveChannelRow>,
    focusedGuide: State<GuideFocus?>,
    focusedChannelId: State<String?>,
    focusedCategory: LiveCategoryItem?,
    loadProgram: suspend (streamId: String, epgChannelId: String?, atUtcMillis: Long) -> GuideProgram?,
    /** False while a channel plays: the slot paints nothing so the player's surface shows (F-48). */
    showPreviewArt: Boolean,
) {
    val guideFocus = focusedGuide.value
    val listFocusId = focusedChannelId.value

    // The channel the preview slot shows: the focused channel in the list, the focused
    // row's channel in the guide.
    val guideFocusShown = guideFocus?.takeIf { f ->
        when (f) {
            is GuideFocus.ChannelCell -> f.row in guideRows.indices
            is GuideFocus.Program -> f.row in guideRows.indices
        }
    } ?: if (guideRows.isNotEmpty()) GuideFocus.ChannelCell(0) else null
    val infoChannel = if (guideMode) {
        val streamId = when (val f = guideFocusShown) {
            is GuideFocus.ChannelCell -> guideRows.getOrNull(f.row)?.channel?.streamId
            is GuideFocus.Program -> guideRows.getOrNull(f.row)?.channel?.streamId
            null -> null
        }
        channels.firstOrNull { it.stream.id == streamId } ?: channels.firstOrNull()
    } else {
        channels.firstOrNull { it.stream.id == listFocusId } ?: channels.firstOrNull()
    }

    // Info band (spec #7 section 3.1): what the focused item is, with its description
    // from a single-row read - debounced on focus changes and off the main thread.
    val infoNow = remember(guideFocusShown, infoChannel?.stream?.id, listFocusId, guideMode) {
        System.currentTimeMillis()
    }
    val infoTarget: InfoTarget? = if (guideMode) {
        guideFocusShown?.let { infoTargetForGuide(it, guideRows, infoNow) }
    } else {
        infoChannel?.let { infoTargetForList(it, infoNow) }
    }
    val loadedProgram by produceState<GuideProgram?>(initialValue = null, infoTarget?.query) {
        value = null
        val query = infoTarget?.query ?: return@produceState
        delay(INFO_DEBOUNCE_MILLIS)
        value = loadProgram(query.streamId, query.epgChannelId, query.atUtcMillis)
    }
    val infoBand: InfoBandUi? = infoTarget?.let { target ->
        loadedProgram?.let { program ->
            infoBandFor(target.band.channelLine, program, target.query?.hasReplay == true, infoNow)
        } ?: target.band
    }

    InfoBand(category = focusedCategory, info = infoBand, description = loadedProgram?.description)
    PreviewSlot(channel = infoChannel, showArt = showPreviewArt)
}
