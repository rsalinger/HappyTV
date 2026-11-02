package com.rysal.happytv.ui.player

import android.content.Intent
import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.data.entity.StreamType
import com.rysal.happytv.ui.player.PlayerLaunch.Companion.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The intent contract between the launching screens (browse, guide) and
 * [PlayerActivity] — round-trips through [PlayerLaunch.put]/[PlayerLaunch.from].
 *
 * Robolectric because `Intent` is an Android class; the plain framework
 * [Application] sidesteps [com.rysal.happytv.HappyTvApp]'s signature pinning
 * (the Robolectric classpath carries no APK signatures).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PlayerLaunchTest {

    @Test
    fun `live launch round-trips every extra`() {
        val launch = PlayerLaunch.live(
            streamId = "42",
            extension = "ts",
            host = "panel.example",
            port = 8080,
            useTls = false,
            username = "user",
            password = "pass",
        )
        val parsed = PlayerLaunch.from(Intent().put(launch))

        assertEquals(launch, parsed)
        assertTrue(parsed!!.hasExplicitConnection)
    }

    @Test
    fun `replay launch carries the program window`() {
        val launch = PlayerLaunch.replay(
            streamId = "42",
            startUtcMillis = 1_713_542_400_000L,
            durationMinutes = 120,
        )
        val parsed = PlayerLaunch.from(Intent().put(launch))!!

        assertTrue(parsed.isReplay)
        assertEquals(1_713_542_400_000L, parsed.startUtcMillis)
        assertEquals(120, parsed.durationMinutes)
        assertEquals(StreamType.LIVE, parsed.streamType)
    }

    @Test
    fun `movie launch keeps the top-block extras`() {
        val launch = PlayerLaunch.movie(streamId = "9", year = "2023", mpaa = "PG-13")
        val parsed = PlayerLaunch.from(Intent().put(launch))!!

        assertEquals(StreamType.VOD, parsed.streamType)
        assertEquals("2023", parsed.year)
        assertEquals("PG-13", parsed.mpaa)
        assertFalse(parsed.hasExplicitConnection)
    }

    @Test
    fun `plain launches do not set replay`() {
        val parsed = PlayerLaunch.from(Intent().put(PlayerLaunch.live("42")))!!
        assertFalse(parsed.isReplay)
        assertEquals(0L, parsed.startUtcMillis)
    }

    @Test
    fun `missing stream id is rejected`() {
        val intent = Intent().putExtra(PlayerLaunch.EXTRA_STREAM_TYPE, "LIVE")
        assertNull(PlayerLaunch.from(intent))
    }

    @Test
    fun `unknown stream type is rejected`() {
        val intent = Intent()
            .putExtra(PlayerLaunch.EXTRA_STREAM_TYPE, "RADIO")
            .putExtra(PlayerLaunch.EXTRA_STREAM_ID, "1")
        assertNull(PlayerLaunch.from(intent))
    }

    @Test
    fun `stream type parses case-insensitively`() {
        val intent = Intent()
            .putExtra(PlayerLaunch.EXTRA_STREAM_TYPE, "live")
            .putExtra(PlayerLaunch.EXTRA_STREAM_ID, "1")
        assertEquals(StreamType.LIVE, PlayerLaunch.from(intent)!!.streamType)
    }

    @Test
    fun `a junk port collapses to no explicit connection`() {
        val launch = PlayerLaunch.live(
            streamId = "1",
            host = "panel.example",
            port = 99_999,
            useTls = true,
            username = "u",
            password = "p",
        )
        val parsed = PlayerLaunch.from(Intent().put(launch))!!
        assertEquals(99_999, parsed.port)
        assertFalse(parsed.hasExplicitConnection)
    }
}
