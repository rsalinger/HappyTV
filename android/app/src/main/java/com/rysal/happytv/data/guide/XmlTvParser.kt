package com.rysal.happytv.data.guide

import com.rysal.happytv.data.entity.EpgProgram
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Streaming XMLTV `<programme>` parser (task body §1: "fetch the full
 * XMLTV file, parse with `XmlPullParser`, store in `EpgProgramDao`
 * (table name unchanged to avoid migration)").
 *
 * Only what the guide draws is extracted: the programme's `channel` key
 * (matched against `LiveStream.epgChannelId`), first `<title>`, first
 * `<desc>` and the start/stop stamps. `<category>` is deliberately not
 * stored — `epg_programs` has no genre column and this task must not
 * migrate the schema; the grid's genre comes from the channel's provider
 * category (design spec genre dots — `ch.genre` in the prototype).
 *
 * Time format: `yyyyMMddHHmmss ±zzzz` (e.g. `20261016143000 -0400`),
 * XMLTV's usual RFC-822 offset; a missing/invalid offset falls back to
 * UTC, an invalid stamp drops that programme instead of crashing a
 * 10 MB refresh.
 *
 * The parser is pure JVM (kxml2 supplies `XmlPullParser` in unit tests;
 * the Android runtime ships one), so the parse path is testable without
 * Robolectric — see `GuideXmlTvTest`.
 */
object XmlTvParser {

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val WHITESPACE = Regex("\\s+")
    private const val PROGRAMME = "programme"

    /** Parses the full XMLTV document in [input]; the caller closes the stream. */
    fun parse(input: InputStream): List<EpgProgram> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, null) // null = encoding from the XML declaration
        return parse(parser)
    }

    /**
     * Streaming parse — calls [onProgram] for each valid `<programme>`
     * as it is encountered, without accumulating the full list in memory.
     *
     * F-47 rework: an 86 MB XMLTV file would OOM the old `parse()` that
     * built one `ArrayList<EpgProgram>`. The caller batches writes to the
     * DB (see `GuideRepository.fetchAndStoreXmlTv`) so peak memory stays
     * bounded by the batch size, not the file size.
     *
     * [onProgram] is `suspend` so the caller can flush batches to Room
     * without buffering the entire document.
     */
    suspend fun parseEach(input: InputStream, onProgram: suspend (EpgProgram) -> Unit) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, null)
        parseEach(parser, onProgram)
    }

    /**
     * Streaming parse as a lazy [Sequence] — F-52's input to
     * `EpgProgramDao.replaceAllStreamed`, so the whole replace
     * (`deleteAll` + batched inserts) lives in one Room transaction that
     * rolls back to the last-good guide when the document turns out to be
     * malformed mid-way. The consumer pulls one programme at a time; the
     * list of programmes is never materialised. An [XmlPullParserException]
     * (or any IO error) surfaces on the consuming `for` loop's iteration,
     * exactly where the transaction can still undo the wipe.
     *
     * The caller owns [input] and must close it after the sequence has been
     * drained (or abandoned).
     */
    fun parseSequence(input: InputStream): Sequence<EpgProgram> = sequence {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, null) // null = encoding from the XML declaration
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == PROGRAMME) {
                programOf(parser)?.let { yield(it) }
                // programOf leaves the parser on </programme> (or END_DOCUMENT).
            }
            event = parser.next()
        }
    }

    /** Streaming variant from an already-configured parser (shared with tests). */
    suspend fun parseEach(parser: XmlPullParser, onProgram: suspend (EpgProgram) -> Unit) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == PROGRAMME) {
                programOf(parser)?.let { onProgram(it) }
                // programOf leaves the parser on </programme> (or END_DOCUMENT).
            }
            event = parser.next()
        }
    }

    /** Parses from an already-configured parser (shared with tests). */
    fun parse(parser: XmlPullParser): List<EpgProgram> {
        val programs = ArrayList<EpgProgram>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == PROGRAMME) {
                programOf(parser)?.let(programs::add)
            }
            event = parser.next()
        }
        return programs
    }

    /**
     * Reads one `<programme>` element, positioned at its START_TAG; returns
     * null for malformed entries (missing key/stamp, end before start,
     * element cut short by a truncated file). Leaves the parser on the
     * matching END_TAG.
     */
    private fun programOf(parser: XmlPullParser): EpgProgram? {
        val channel = parser.getAttributeValue(null, "channel")
        val start = parseStamp(parser.getAttributeValue(null, "start"))
        val stop = parseStamp(parser.getAttributeValue(null, "stop"))
        var title: String? = null
        var desc: String? = null

        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val child = parser.name
                    if (depth == 1 && (child == "title" || child == "desc")) {
                        // Consumes the child's markup and its END_TAG itself.
                        val text = readChildText(parser)
                        if (child == "title" && title == null) title = text.takeIf { it.isNotEmpty() }
                        if (child == "desc" && desc == null) desc = text.takeIf { it.isNotEmpty() }
                    } else {
                        depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return null // file cut mid-element: drop the row
                else -> {
                    // Indentation TEXT between children (pretty-printed feeds),
                    // comments, entity refs — none of them end the element.
                    // Dropping the row here discarded *every* real programme:
                    // the walk only looked clean because the minimal fixture
                    // had no whitespace between tags.
                }
            }
        }

        if (channel.isNullOrBlank() || title == null || start == null || stop == null) return null
        if (stop < start) return null
        return EpgProgram(
            channelId = channel,
            title = title,
            description = desc,
            startUtcMillis = start,
            endUtcMillis = stop,
        )
    }

    /** Text of a simple child element: from its START_TAG through its END_TAG. */
    private fun readChildText(parser: XmlPullParser): String {
        val event = parser.next()
        val text = if (event == XmlPullParser.TEXT) {
            val value = parser.text.orEmpty()
            parser.next() // step onto the child's END_TAG
            value
        } else {
            "" // self-closing <title/> — already sitting on its END_TAG
        }
        return text.trim()
    }

    /**
     * `"20261016143000 -0400"` → epoch millis. Splits on whitespace so the
     * offset accepts `Z`, `+0000`, `-0400` or `+01:00`; anything unparseable
     * returns null (the caller drops the row).
     */
    internal fun parseStamp(stamp: String?): Long? {
        if (stamp.isNullOrBlank()) return null
        val parts = stamp.trim().split(WHITESPACE)
        val local = try {
            LocalDateTime.parse(parts[0], STAMP)
        } catch (e: DateTimeParseException) {
            return null
        }
        val offset = if (parts.size > 1) {
            try {
                ZoneOffset.of(parts[1])
            } catch (e: Exception) {
                ZoneOffset.UTC
            }
        } else {
            ZoneOffset.UTC
        }
        return local.toInstant(offset).toEpochMilli()
    }
}
