package com.lumora.tv.data.epg

import com.lumora.tv.data.db.ChannelEpgInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatcherTest {

    private val matcher = EpgMatcher(
        listOf(
            ChannelEpgInfo(tvgId = "BBCNews.uk@UKHD", name = "BBC News UK HD (1080p)"),
            ChannelEpgInfo(tvgId = "CNN.us", name = "CNN International"),
            ChannelEpgInfo(tvgId = null, name = "Sky Sports Main Event HD"),
            ChannelEpgInfo(tvgId = "", name = "Film4 [Geo-blocked]"),
        ),
    )

    @Test
    fun matchesExactIdsCaseInsensitively() {
        assertEquals(setOf("cnn.us"), matcher.keysFor("cnn.US"))
    }

    @Test
    fun matchesIdsWithoutTheFeedSuffix() {
        assertEquals(setOf("bbcnews.uk@ukhd"), matcher.keysFor("BBCNews.uk"))
        assertEquals(setOf("bbcnews.uk@ukhd"), matcher.keysFor("BBCNews.uk@SD"))
    }

    @Test
    fun fallsBackToDisplayNames() {
        matcher.registerChannel("sky-main", listOf("Sky Sports Main Event"))
        assertEquals(setOf("n:skysportsmainevent"), matcher.keysFor("sky-main"))
        matcher.registerChannel("f4", listOf("Film 4 HD", "Film4"))
        assertEquals(setOf("n:film4"), matcher.keysFor("f4"))
    }

    @Test
    fun unknownChannelsMatchNothing() {
        matcher.registerChannel("other", listOf("Other TV"))
        assertTrue(matcher.keysFor("other").isEmpty())
        assertTrue(matcher.keysFor("never-registered").isEmpty())
    }

    @Test
    fun normalizesNames() {
        assertEquals("bbcone", normalizeChannelName("BBC One HD (1080p) [Geo-blocked]"))
        assertEquals("film4", normalizeChannelName("Film 4"))
        assertEquals("epgkey", epgKeyFor(" EPGkey ", "ignored"))
        assertEquals("n:bbcone", epgKeyFor(null, "BBC One FHD"))
    }
}
