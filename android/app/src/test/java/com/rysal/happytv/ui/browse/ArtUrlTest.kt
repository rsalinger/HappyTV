package com.rysal.happytv.ui.browse

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rysal.happytv.security.ChannelLogoGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** #238: poster, backdrop and episode art addresses. Fake hosts and credentials only. */
@RunWith(AndroidJUnit4::class)
class ArtUrlTest {
    private val secrets = listOf("alice", "s3cret-pw")

    @Before
    fun setUp() {
        ChannelLogoGuard.update("alice", "s3cret-pw")
        ArtUrl.reset()
    }

    @After
    fun tearDown() {
        ChannelLogoGuard.update(null, null)
        ArtUrl.reset()
    }

    @Test
    fun `an https poster loads`() {
        val url = "https://image.provider.example/t/p/w342/a.jpg"
        assertEquals(url, ArtUrl.checked(url))
    }

    @Test
    fun `a plain http poster without sign-in details still loads`() {
        assertEquals("http://image.provider.example/a.jpg", ArtUrl.checked("http://image.provider.example/a.jpg"))
        assertEquals("http://image.provider.example/a.jpg", ChannelLogoUrl.safeArt("HTTP://image.provider.example/a.jpg"))
    }

    @Test
    fun `credentials in a path segment are refused`() {
        assertNull(ArtUrl.checked("http://image.provider.example/alice/a.jpg"))
        assertNull(ArtUrl.checked("https://image.provider.example/x/s3cret-pw/a.jpg"))
        assertNull(ChannelLogoUrl.safeArt("http://image.provider.example/x/s3cret%2Dpw/a.jpg", secrets))
    }

    @Test
    fun `credentials in a query value or key are refused`() {
        assertNull(ArtUrl.checked("https://image.provider.example/a.jpg?u=alice"))
        assertNull(ArtUrl.checked("http://image.provider.example/a.jpg?x=s3cret-pw"))
        assertNull(ArtUrl.checked("https://image.provider.example/a.jpg?password=anything"))
        assertNull(ArtUrl.checked("https://alice:pw@image.provider.example/a.jpg"))
    }

    @Test
    fun `a stream shaped address is refused`() {
        assertNull(ArtUrl.checked("http://image.provider.example/movie/u/p/1.mkv"))
        assertNull(ArtUrl.checked("https://image.provider.example/live/u/p/1.ts"))
        assertNull(ArtUrl.checked("https://image.provider.example/series/u/p/1.mp4"))
    }

    @Test
    fun `non web schemes are refused`() {
        for (u in listOf(
            "file:///sdcard/a.jpg",
            "content://media/1",
            "data:image/png;base64,AAAA",
            "ftp://x.example/a.jpg",
            "//x.example/a.jpg",
            "x.example/a.jpg",
        )) {
            assertNull(u, ArtUrl.checked(u))
        }
    }

    @Test
    fun `blank and missing addresses give the placeholder`() {
        assertNull(ArtUrl.checked(null))
        assertNull(ArtUrl.checked(""))
        assertNull(ArtUrl.checked("   "))
    }

    @Test
    fun `the answer follows the signed-in user`() {
        assertEquals("https://image.provider.example/bobby/a.jpg", ArtUrl.checked("https://image.provider.example/bobby/a.jpg"))
        ChannelLogoGuard.update("bobby", "another-pw")
        assertNull(ArtUrl.checked("https://image.provider.example/bobby/a.jpg"))
    }

    @Test
    fun `channel logos stay https only`() {
        assertNull(ChannelLogoUrl.safe("http://image.provider.example/a.png"))
    }
}
