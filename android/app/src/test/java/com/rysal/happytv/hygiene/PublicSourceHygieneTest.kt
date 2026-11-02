package com.rysal.happytv.hygiene

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #180: app sources carry no local paths, device addresses or household device names, so the
 * code can live in a public repo. Reports file names only. The provider's own host cannot be
 * listed here without naming it; the one-off check for that runs against the private env file.
 */
class PublicSourceHygieneTest {
    private val forbidden = listOf(
        "local home path" to Regex("/home/" + "ryan"),
        "VPN mesh address" to Regex("""(?<![\d.])100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}(?![\d.])"""),
        "device LAN address" to Regex("""192\.168\.1\.\d+"""),
        "device nickname" to Regex("\\b(" + "sabri" + "na|bor" + "eal)\\b", RegexOption.IGNORE_CASE),
        "household device" to Regex("(household|lab) " + "TV", RegexOption.IGNORE_CASE),
        "keystore file path" to Regex("keystore" + "s/|\\.j" + "ks\\b", RegexOption.IGNORE_CASE),
        "owner name" to Regex("""\bRyan\b"""),
    )

    private fun appDir(): File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }

    @Test
    fun `app sources name no private paths or devices`() {
        val self = "PublicSourceHygieneTest.kt"
        val hits = appDir().walkTopDown()
            .onEnter { it.name != "build" && it.name != "schemas" }
            .filter { it.isFile && it.name != self && it.extension in setOf("kt", "kts", "xml", "md", "pro", "svg", "txt") }
            .flatMap { f ->
                val text = f.readText()
                forbidden.filter { (_, re) -> re.containsMatchIn(text) }.map { (label, _) -> "${f.name}: $label" }
            }
            .toList()
        assertEquals("private details in app sources", emptyList<String>(), hits)
    }
}
