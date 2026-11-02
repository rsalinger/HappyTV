package com.rysal.happytv.data.guide

import com.rysal.happytv.security.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The F-47 / F-52 URL derivation: given the saved Xtream credentials,
 * build `{scheme}://{host}[:{port}]/xmltv.php?username=…&password=…`
 * exactly as the Xtream Codes §2.1 endpoint spec wants.
 *
 * Plain JUnit — no Android, no Robolectric. The deriver is the
 * smallest unit we can test without I/O, so it gets the most direct
 * coverage: every shape the deriver rejects (`null`, blank host,
 * blank user, blank password) and every shape it produces (https,
 * http, default-port elision, explicit port, percent-encoding of
 * reserved characters in user/pass).
 */
class XmlTvUrlDeriverTest {

    private fun cred(
        host: String = "provider.example",
        port: Int = 80,
        useTls: Boolean = false,
        username: String = "demo",
        password: String = "secret",
    ) = Credentials(
        host = host,
        port = port,
        useTls = useTls,
        username = username,
        password = password,
        savedAtUtcMillis = 0L,
    )

    @Test
    fun `http default port elides the explicit 80`() {
        assertEquals(
            "http://provider.example/xmltv.php?username=demo&password=secret",
            XmlTvUrlDeriver.derive(cred()),
        )
    }

    @Test
    fun `https default port elides the explicit 443`() {
        assertEquals(
            "https://provider.example/xmltv.php?username=demo&password=secret",
            XmlTvUrlDeriver.derive(cred(port = 443, useTls = true)),
        )
    }

    @Test
    fun `non-default port is preserved verbatim`() {
        assertEquals(
            "http://provider.example:8080/xmltv.php?username=demo&password=secret",
            XmlTvUrlDeriver.derive(cred(port = 8080)),
        )
    }

    @Test
    fun `https with explicit 443 is also elided`() {
        // 443 is https's default — strip it so the panel URL stays clean.
        assertEquals(
            "https://provider.example/xmltv.php?username=demo&password=secret",
            XmlTvUrlDeriver.derive(cred(port = 443, useTls = true)),
        )
    }

    @Test
    fun `username and password are percent-encoded — every reserved char`() {
        // Plus, ampersand, slash, equals, percent, hash, question mark, space,
        // and a unicode codepoint — the typical password-fixture buffet.
        val raw = "u+ser&p/w=%3F# =\"é\""
        val url = XmlTvUrlDeriver.derive(cred(username = "u s&e/r", password = raw))!!
        // `&` separates the two params, so the password's ampersand must be
        // encoded — otherwise the panel parser would split mid-password.
        assertEquals(true, url.contains("password=" + java.net.URLEncoder.encode(raw, "UTF-8")))
        assertEquals(true, url.contains("username=" + java.net.URLEncoder.encode("u s&e/r", "UTF-8")))
    }

    @Test
    fun `null credentials returns null (signed-out state)`() {
        assertNull(XmlTvUrlDeriver.derive(null))
    }

    @Test
    fun `blank host returns null — never build an unwrappable URL`() {
        assertNull(XmlTvUrlDeriver.derive(cred(host = "")))
        assertNull(XmlTvUrlDeriver.derive(cred(host = "   ")))
    }

    @Test
    fun `blank username or password returns null — the panel would 401`() {
        assertNull(XmlTvUrlDeriver.derive(cred(username = "")))
        assertNull(XmlTvUrlDeriver.derive(cred(password = "")))
        assertNull(XmlTvUrlDeriver.derive(cred(username = "   ")))
    }

    @Test
    fun `credentials with extra surrounding spaces on the host collapse`() {
        // Parser trims `host` but never trims `username` (a password may
        // legitimately carry surrounding whitespace). The host trim is
        // what keeps the URL parsed-back-to-host matching.
        assertEquals(
            "http://provider.example/xmltv.php?username=demo&password=secret",
            XmlTvUrlDeriver.derive(cred(host = "  provider.example  ")),
        )
    }
}
