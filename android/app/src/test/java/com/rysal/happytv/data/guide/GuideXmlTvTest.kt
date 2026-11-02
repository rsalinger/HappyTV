package com.rysal.happytv.data.guide

import com.rysal.happytv.data.entity.EpgProgram
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The XMLTV parse path of `RefreshGuideWorker` (task acceptance:
 * "RefreshGuideWorker's XMLTV parse path is unit-tested … use a captured
 * real XMLTV snippet from /tmp/xmltv-sample.xml if available, otherwise
 * inline a minimal one" — no sample existed, so this feed inlines the
 * structure: channel map, titles, descs, `category` (deliberately not
 * stored — no schema migration), RFC-822 offsets, and every malformed
 * shape the parser must survive).
 *
 * Plain JUnit: kxml2 supplies the `XmlPullParser` on the JVM (the
 * Android runtime ships one), so no Robolectric needed.
 */
class GuideXmlTvTest {

    private val feed: String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="provider.example">
          <channel id="northbay.news">
            <display-name lang="en">Northbay News</display-name>
          </channel>
          <programme start="20261016140000 +0000" stop="20261016150000 +0000" channel="northbay.news">
            <title lang="en">Headline report</title>
            <desc lang="en">The top stories of the hour, with live updates from the newsroom.</desc>
            <category lang="en">News</category>
          </programme>
          <programme start="20261016150000 +0000" stop="20261016153000 +0000" channel="northbay.news">
            <title lang="en">World desk</title>
          </programme>
          <programme start="20261016160000 -0400" stop="20261016163000 -0400" channel="capital.desk">
            <title lang="en">Politics hour</title>
            <desc lang="en">Interviews and analysis from the capital.</desc>
            <category lang="en">News</category>
            <icon src="http://provider.example/icons/politics.png"/>
          </programme>
          <programme start="20261016180000 +0000" channel="broken.channel">
            <title>Missing stop</title>
          </programme>
          <programme start="20261016190000 +0000" stop="20261016180000 +0000" channel="broken.channel">
            <title>Backwards</title>
          </programme>
          <programme start="not-a-date" stop="20261016200000 +0000" channel="broken.channel">
            <title>Bad stamp</title>
          </programme>
          <programme start="20261016210000 +0000" stop="20261016220000 +0000" channel="   ">
            <title>No channel</title>
          </programme>
          <programme start="20261016230000 +0000" stop="20261017000000 +0000" channel="lantekids">
            <title></title>
          </programme>
        </tv>
    """.trimIndent()

    private fun parse(xml: String): List<EpgProgram> =
        XmlTvParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun utc(hour: Int, minute: Int, day: Int = 16): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).toEpochSecond(ZoneOffset.UTC) * 1000

    @Test
    fun `parses valid programmes and drops every malformed one`() {
        val programs = parse(feed)
        // three valid rows; the missing-stop, backwards, bad-stamp,
        // blank-channel and empty-title rows are all dropped.
        assertEquals(3, programs.size)
        assertTrue(programs.none { it.title.startsWith("Missing") || it.title.startsWith("Backwards") || it.title.startsWith("Bad") || it.title.startsWith("No channel") })
    }

    @Test
    fun `programme fields map to the store row`() {
        val first = parse(feed).first()
        assertEquals("northbay.news", first.channelId) // XMLTV channel key (= epg_channel_id)
        assertEquals("Headline report", first.title)
        assertEquals(
            "The top stories of the hour, with live updates from the newsroom.",
            first.description,
        )
        assertEquals(utc(14, 0), first.startUtcMillis)
        assertEquals(utc(15, 0), first.endUtcMillis)
    }

    @Test
    fun `a programme without a desc stores null description - the dialog shows its placeholder`() {
        val worldDesk = parse(feed).first { it.title == "World desk" }
        assertNull(worldDesk.description)
    }

    @Test
    fun `offset stamps are honoured - 1600 minus 0400 is 2000 UTC`() {
        val politics = parse(feed).first { it.title == "Politics hour" }
        assertEquals(utc(20, 0), politics.startUtcMillis)
        assertEquals(utc(20, 30), politics.endUtcMillis)
        assertEquals("capital.desk", politics.channelId)
    }

    @Test
    fun `document order is preserved`() {
        assertEquals(
            listOf("Headline report", "World desk", "Politics hour"),
            parse(feed).map { it.title },
        )
    }

    @Test
    fun `stamps parse with Z, signed offsets and no offset`() {
        assertEquals(utc(14, 30), XmlTvParser.parseStamp("20261016143000 Z"))
        assertEquals(utc(14, 30), XmlTvParser.parseStamp("20261016143000 +0000"))
        assertEquals(utc(18, 30), XmlTvParser.parseStamp("20261016143000 -0400"))
        assertEquals(utc(14, 30), XmlTvParser.parseStamp("20261016143000")) // no offset → UTC
        assertNull(XmlTvParser.parseStamp(null))
        assertNull(XmlTvParser.parseStamp(""))
        assertNull(XmlTvParser.parseStamp("garbage"))
        assertNull(XmlTvParser.parseStamp("20261399999999 +0000")) // impossible date
    }

    @Test
    fun `entities and namespaced-looking text pass through verbatim`() {
        val xml = """
            <tv>
              <programme start="20261016140000 +0000" stop="20261016150000 +0000" channel="ch1">
                <title>R&amp;D: tomorrow's &lt;news&gt;</title>
                <desc>Tom &amp; Jerry, 100% live</desc>
              </programme>
            </tv>
        """.trimIndent()
        val program = parse(xml).single()
        assertEquals("R&D: tomorrow's <news>", program.title)
        assertEquals("Tom & Jerry, 100% live", program.description)
    }

    @Test
    fun `self-closing title drops the row instead of showing a blank block`() {
        val xml = """
            <tv>
              <programme start="20261016140000 +0000" stop="20261016150000 +0000" channel="ch1">
                <title/>
              </programme>
            </tv>
        """.trimIndent()
        assertEquals(0, parse(xml).size)
    }

    @Test
    fun `empty document parses to an empty list`() {
        val xml = """<tv></tv>"""
        assertEquals(0, parse(xml).size)
    }

    // --------------------------------------------- F-52: the streaming walk

    @Test
    fun `parseSequence streams exactly the rows parse collects`() {
        val streamed = XmlTvParser.parseSequence(
            ByteArrayInputStream(feed.toByteArray(Charsets.UTF_8)),
        ).toList()
        assertEquals(parse(feed), streamed)
    }

    @Test
    fun `parseSequence yields the valid prefix then surfaces malformed XML on the loop`() {
        // Syntactically wrong (a stray close tag) — deterministic
        // XmlPullParserException on both kxml2 and the Android runtime,
        // unlike a merely truncated document (which the parser drops rows for).
        val broken = """
            <tv>
              <programme start="20261016140000 +0000" stop="20261016150000 +0000" channel="ch1">
                <title>Good row</title>
              </programme>
            </tv></stray>
        """.trimIndent()
        val seen = ArrayList<String>()
        var threw = false
        try {
            XmlTvParser.parseSequence(ByteArrayInputStream(broken.toByteArray(Charsets.UTF_8)))
                .forEach { seen += it.title }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("expected the malformed tail to throw for the consumer", threw)
        // Laziness proof: the row before the break reached the consumer,
        // which is what makes a mid-parse rollback meaningful.
        assertEquals(listOf("Good row"), seen)
    }
}
