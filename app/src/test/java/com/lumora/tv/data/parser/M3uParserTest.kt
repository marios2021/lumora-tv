package com.lumora.tv.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.reader().buffered())

    @Test
    fun parsesAttributesNameAndHeaderEpg() {
        val result = parse(
            """
            #EXTM3U url-tvg="http://epg.example/guide.xml.gz"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://logo/bbc1.png" group-title="UK",BBC One HD
            http://stream.example/bbc1.m3u8
            """.trimIndent(),
        )
        assertEquals("http://epg.example/guide.xml.gz", result.epgUrl)
        val c = result.channels.single()
        assertEquals("BBC One HD", c.name)
        assertEquals("UK", c.group)
        assertEquals("bbc1.uk", c.tvgId)
        assertEquals("http://logo/bbc1.png", c.logo)
        assertEquals("http://stream.example/bbc1.m3u8", c.url)
        assertEquals(c.url, c.key)
    }

    @Test
    fun commaInsideQuotedAttributeDoesNotSplitName() {
        val c = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News, Sport",Sky News
            http://s/1
            """.trimIndent(),
        ).channels.single()
        assertEquals("News, Sport", c.group)
        assertEquals("Sky News", c.name)
    }

    @Test
    fun usesExtGrpAndFallsBackToDefaultGroup() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,One
            #EXTGRP:Movies
            http://s/1
            #EXTINF:-1,Two
            http://s/2
            """.trimIndent(),
        ).channels
        assertEquals("Movies", channels[0].group)
        assertEquals(DEFAULT_GROUP, channels[1].group)
    }

    @Test
    fun handlesBomBlankLinesOptionsAndBareUrls() {
        val result = parse(
            "﻿#EXTM3U\n\n#EXTINF:-1 tvg-name=\"Named\",\n#EXTVLCOPT:http-user-agent=x\nhttp://s/a\nhttp://s/b.ts?token=1\n",
        )
        assertNull(result.epgUrl)
        assertEquals(listOf("Named", "b.ts"), result.channels.map { it.name })
    }
}
