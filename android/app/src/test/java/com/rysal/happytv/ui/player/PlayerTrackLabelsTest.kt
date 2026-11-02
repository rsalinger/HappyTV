package com.rysal.happytv.ui.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.TrackGroup
import com.rysal.happytv.ui.player.PlayerTrackLabels.Kind
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/** #170: the Audio and subtitles panel shows names ("English"), never "en" or "Track". */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerTrackLabelsTest {

    private val en = Locale.ENGLISH

    @Test
    fun languageCodesBecomeLanguageNames() {
        assertEquals("English", PlayerTrackLabels.name("en", null, Kind.Audio, 1, en))
        assertEquals("English", PlayerTrackLabels.name("eng", null, Kind.Audio, 1, en))
        assertEquals("Spanish", PlayerTrackLabels.name("es", "", Kind.Subtitles, 1, en))
        assertEquals("French", PlayerTrackLabels.name("FR", null, Kind.Audio, 2, en))
        assertEquals("Portuguese (Brazil)", PlayerTrackLabels.name("pt-BR", null, Kind.Audio, 1, en))
    }

    @Test
    fun theNameFollowsTheDeviceLanguage() {
        assertEquals("Spanish", PlayerTrackLabels.languageName("es", en))
        assertEquals("Español", PlayerTrackLabels.languageName("es", Locale("es")))
    }

    @Test
    fun aReadableLabelBeatsTheCode() {
        assertEquals("Director's commentary", PlayerTrackLabels.name("en", "Director's commentary", Kind.Audio, 2, en))
        assertEquals("SDH", PlayerTrackLabels.name("en", "SDH", Kind.Subtitles, 1, en))
    }

    @Test
    fun aLabelThatIsJustACodeBecomesTheName() {
        assertEquals("English", PlayerTrackLabels.name(null, "en", Kind.Subtitles, 1, en))
        assertEquals("English", PlayerTrackLabels.name("en", "eng", Kind.Audio, 1, en))
    }

    @Test
    fun missingOrUnknownInfoFallsBackToANumberedName() {
        assertEquals("Subtitles 1", PlayerTrackLabels.name(null, null, Kind.Subtitles, 1, en))
        assertEquals("Subtitles 3", PlayerTrackLabels.name("", "  ", Kind.Subtitles, 3, en))
        assertEquals("Audio 2", PlayerTrackLabels.name("und", null, Kind.Audio, 2, en))
        assertEquals("Audio 1", PlayerTrackLabels.name("zz", null, Kind.Audio, 1, en))
    }

    @Test
    fun noRawCodeOrTrackEverReachesThePanel() {
        assertNull(PlayerTrackLabels.languageName("und", en))
        assertNull(PlayerTrackLabels.languageName("Director's commentary", en))
        assertNull(PlayerTrackLabels.languageName(null, en))
    }

    @Test
    fun repeatedNamesAreMadeDistinct() {
        assertEquals(
            listOf("English", "English 2", "Spanish", "English 3"),
            PlayerTrackLabels.distinct(listOf("English", "English", "Spanish", "English")),
        )
        assertEquals(listOf("Audio 1", "Audio 2"), PlayerTrackLabels.distinct(listOf("Audio 1", "Audio 2")))
    }

    // The mapping from Media3 tracks to the panel's options.

    private fun group(type: Int, vararg formats: Format, selected: Int = -1): Tracks.Group {
        val selectedFlags = BooleanArray(formats.size) { it == selected }
        return Tracks.Group(TrackGroup(*formats), false, IntArray(formats.size) { C.FORMAT_HANDLED }, selectedFlags)
    }

    private fun audio(language: String?, label: String? = null) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(language).setLabel(label).build()

    private fun text(language: String?, label: String? = null) = Format.Builder()
        .setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage(language).setLabel(label).build()

    @Test
    fun theStreamsOwnTracksGetReadableNames() {
        val tracks = Tracks(
            listOf(
                group(C.TRACK_TYPE_AUDIO, audio("en"), audio("es"), selected = 0),
                group(C.TRACK_TYPE_TEXT, text(null), text("en"), selected = 1),
            ),
        ).toPlayerTracks()!!
        assertEquals(listOf("English", "Spanish"), tracks.audio.map { it.label })
        assertEquals(listOf("Off", "Subtitles 1", "English"), tracks.subtitles.map { it.label })
        assertEquals(2, tracks.selectedSubtitle)
    }

    @Test
    fun unnamedTracksAcrossGroupsAreNumberedInOrder() {
        val tracks = Tracks(
            listOf(
                group(C.TRACK_TYPE_AUDIO, audio(null), selected = 0),
                group(C.TRACK_TYPE_AUDIO, audio(null)),
                group(C.TRACK_TYPE_TEXT, text(null), text(null)),
            ),
        ).toPlayerTracks()!!
        assertEquals(listOf("Audio 1", "Audio 2"), tracks.audio.map { it.label })
        assertEquals(listOf("Off", "Subtitles 1", "Subtitles 2"), tracks.subtitles.map { it.label })
    }
}
