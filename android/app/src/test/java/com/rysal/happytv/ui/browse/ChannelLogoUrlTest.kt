package com.rysal.happytv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelLogoUrlTest {
    @Test
    fun `an https logo passes`() {
        assertEquals("https://img.example/logos/news.png", ChannelLogoUrl.safe("https://img.example/logos/news.png"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://img.example/a.png", ChannelLogoUrl.safe("  https://img.example/a.png \n"))
    }

    @Test
    fun `blank and missing urls fall back`() {
        assertNull(ChannelLogoUrl.safe(null))
        assertNull(ChannelLogoUrl.safe(""))
        assertNull(ChannelLogoUrl.safe("   "))
    }

    @Test
    fun `plain http never loads`() {
        assertNull(ChannelLogoUrl.safe("http://img.example/a.png"))
        assertNull(ChannelLogoUrl.safe("HTTP://img.example/a.png"))
    }

    @Test
    fun `other schemes and scheme-less hosts never load`() {
        assertNull(ChannelLogoUrl.safe("ftp://img.example/a.png"))
        assertNull(ChannelLogoUrl.safe("file:///sdcard/a.png"))
        assertNull(ChannelLogoUrl.safe("//img.example/a.png"))
        assertNull(ChannelLogoUrl.safe("img.example/a.png"))
        assertNull(ChannelLogoUrl.safe("not a url"))
    }

    @Test
    fun `user info in the url is refused`() {
        assertNull(ChannelLogoUrl.safe("https://someone:secret@img.example/a.png"))
        assertNull(ChannelLogoUrl.safe("https://someone@img.example/a.png"))
    }

    @Test
    fun `credential-named query keys are refused`() {
        assertNull(ChannelLogoUrl.safe("https://img.example/a.png?username=u&password=p"))
        assertNull(ChannelLogoUrl.safe("https://img.example/a.png?Token=abc"))
        assertNull(ChannelLogoUrl.safe("https://img.example/a.png?api_key=abc"))
        assertEquals("https://img.example/a.png?w=96", ChannelLogoUrl.safe("https://img.example/a.png?w=96"))
    }

    @Test
    fun `a stream-shaped path is not a logo`() {
        assertNull(ChannelLogoUrl.safe("https://img.example/live/user/pass/101.png"))
        assertNull(ChannelLogoUrl.safe("https://img.example/movie/u/p/5.mp4"))
        assertEquals("https://img.example/live/101.png", ChannelLogoUrl.safe("https://img.example/live/101.png"))
    }

    @Test
    fun `a url that contains the signed-in username or password is refused`() {
        val secrets = listOf("anna.home", "p4ss-w0rd")
        assertNull(ChannelLogoUrl.safe("https://img.example/anna.home/a.png", secrets))
        assertNull(ChannelLogoUrl.safe("https://img.example/a.png?x=P4SS-W0RD", secrets))
        assertNull(ChannelLogoUrl.safe("https://img.example/p4ss%2Dw0rd/a.png", secrets))
        assertEquals("https://img.example/a.png", ChannelLogoUrl.safe("https://img.example/a.png", secrets))
    }

    @Test
    fun `very short secrets are ignored so common words do not hide every logo`() {
        assertEquals("https://img.example/tv/a.png", ChannelLogoUrl.safe("https://img.example/tv/a.png", listOf("tv")))
    }

    @Test
    fun `an over-long url falls back`() {
        assertNull(ChannelLogoUrl.safe("https://img.example/" + "a".repeat(3000)))
    }
}
