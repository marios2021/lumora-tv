package com.lumora.tv.data.epg

import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class XmltvProgramme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/**
 * Streams an XMLTV document with a pull parser, so guides of hundreds of megabytes never sit in memory.
 * The parser is injected: Android's in the app, kXML in JVM unit tests.
 */
class XmltvParser(private val newParser: () -> XmlPullParser) {

    interface Listener {
        fun onChannel(id: String, displayNames: List<String>)
        fun onProgramme(programme: XmltvProgramme)
    }

    fun parse(input: InputStream, listener: Listener) {
        val parser = newParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser, listener)
                    "programme" -> readProgramme(parser, listener)
                }
            }
            event = parser.next()
        }
    }

    private fun readChannel(parser: XmlPullParser, listener: Listener) {
        val id = parser.getAttributeValue(null, "id")
        val names = ArrayList<String>(2)
        forEachChild(parser, "channel") { tag ->
            if (tag == "display-name") parser.nextText().trim().takeIf { it.isNotEmpty() }?.let(names::add) else skip(parser)
        }
        if (!id.isNullOrBlank()) listener.onChannel(id.trim(), names)
    }

    private fun readProgramme(parser: XmlPullParser, listener: Listener) {
        val channel = parser.getAttributeValue(null, "channel")
        val start = parser.getAttributeValue(null, "start")?.let(::parseXmltvTime)
        val stop = parser.getAttributeValue(null, "stop")?.let(::parseXmltvTime)
        var title: String? = null
        var description: String? = null
        forEachChild(parser, "programme") { tag ->
            when (tag) {
                "title" -> parser.nextText().trim().let { if (title == null && it.isNotEmpty()) title = it }
                "desc" -> parser.nextText().trim().let { if (description == null && it.isNotEmpty()) description = it }
                else -> skip(parser)
            }
        }
        if (channel.isNullOrBlank() || start == null || stop == null || stop <= start) return
        listener.onProgramme(
            XmltvProgramme(
                channelId = channel.trim(),
                start = start,
                stop = stop,
                title = title ?: "No title",
                description = description?.take(MAX_DESCRIPTION),
            ),
        )
    }

    /** Calls [onStartTag] for each direct child element until the matching end tag. */
    private inline fun forEachChild(parser: XmlPullParser, endTag: String, onStartTag: (String) -> Unit) {
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> onStartTag(parser.name)
                XmlPullParser.END_TAG -> if (parser.name == endTag) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        private const val MAX_DESCRIPTION = 600

        /** Transparently un-gzips `.xml.gz` guides, which servers send without Content-Encoding. */
        fun maybeGunzip(input: InputStream): InputStream {
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(2)
            val b1 = buffered.read()
            val b2 = buffered.read()
            buffered.reset()
            return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024)) else buffered
        }
    }
}

/**
 * Parses XMLTV times like `20261015170000 +0100` (seconds and offset optional) to epoch millis.
 * Done by hand because java.time needs API 26 and this runs millions of times on big guides.
 */
fun parseXmltvTime(value: String): Long? {
    val s = value.trim()
    var digits = 0
    while (digits < s.length && s[digits].isDigit()) digits++
    if (digits < 12) return null
    fun num(from: Int, len: Int) = s.substring(from, from + len).toInt()
    val year = num(0, 4)
    val month = num(4, 2)
    val day = num(6, 2)
    val hour = num(8, 2)
    val minute = num(10, 2)
    val second = if (digits >= 14) num(12, 2) else 0
    if (month !in 1..12 || day !in 1..31) return null

    var offsetSeconds = 0
    val zone = s.substring(digits).trim()
    if (zone.length >= 5 && (zone[0] == '+' || zone[0] == '-')) {
        val hh = zone.substring(1, 3).toIntOrNull() ?: return null
        val mm = zone.substring(3, 5).toIntOrNull() ?: return null
        offsetSeconds = (hh * 3600 + mm * 60) * if (zone[0] == '-') -1 else 1
    }
    val epochSeconds = daysFromCivil(year, month, day) * 86_400L + hour * 3600L + minute * 60L + second - offsetSeconds
    return epochSeconds * 1000
}

/** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}
