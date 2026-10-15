package com.lumora.tv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class XmltvParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="BBCNews.uk">
            <display-name lang="en">BBC News</display-name>
            <icon src="http://logo"/>
          </channel>
          <programme start="20261015170000 +0100" stop="20261015180000 +0100" channel="BBCNews.uk">
            <title lang="en">Evening News</title>
            <sub-title>Part 1</sub-title>
            <desc lang="en">Headlines &amp; weather.</desc>
            <category>News</category>
          </programme>
          <programme start="20261015180000 +0100" stop="20261015183000 +0100" channel="BBCNews.uk">
            <title>Sport Today</title>
          </programme>
          <programme start="bad" stop="20261015183000 +0100" channel="BBCNews.uk"><title>Broken</title></programme>
        </tv>
    """.trimIndent()

    private fun parse(bytes: ByteArray): Pair<List<Pair<String, List<String>>>, List<XmltvProgramme>> {
        val channels = ArrayList<Pair<String, List<String>>>()
        val programmes = ArrayList<XmltvProgramme>()
        XmltvParser { KXmlParser() }.parse(
            XmltvParser.maybeGunzip(bytes.inputStream()),
            object : XmltvParser.Listener {
                override fun onChannel(id: String, displayNames: List<String>) {
                    channels += id to displayNames
                }

                override fun onProgramme(programme: XmltvProgramme) {
                    programmes += programme
                }
            },
        )
        return channels to programmes
    }

    @Test
    fun readsChannelsAndProgrammesAndSkipsInvalidOnes() {
        val (channels, programmes) = parse(sample.toByteArray())
        assertEquals(listOf("BBCNews.uk" to listOf("BBC News")), channels)
        assertEquals(2, programmes.size)
        val first = programmes[0]
        assertEquals("Evening News", first.title)
        assertEquals("Headlines & weather.", first.description)
        assertEquals(parseXmltvTime("20261015160000 +0000"), first.start)
        assertEquals(60 * 60 * 1000L, first.stop - first.start)
        assertNull(programmes[1].description)
    }

    @Test
    fun readsGzippedGuides() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(sample.toByteArray()) } }
        assertEquals(2, parse(gz.toByteArray()).second.size)
    }

    @Test
    fun parsesTimesWithAndWithoutOffsets() {
        // 2026-10-15T16:00:00Z
        val utc = 1_792_080_000_000L
        assertEquals(utc, parseXmltvTime("20261015160000 +0000"))
        assertEquals(utc, parseXmltvTime("20261015160000"))
        assertEquals(utc, parseXmltvTime("20261015170000 +0100"))
        assertEquals(utc, parseXmltvTime("20261015113000 -0430"))
        assertEquals(utc, parseXmltvTime("202610151600"))
        assertNull(parseXmltvTime("2026"))
        assertNull(parseXmltvTime("20261315160000"))
    }
}
