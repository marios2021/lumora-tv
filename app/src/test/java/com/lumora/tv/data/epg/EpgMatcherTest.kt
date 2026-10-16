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

    // "UK: ITV HD" was fed a Ukrainian guide channel also called "ITV".
    private fun itvMatcher(vararg channels: ChannelEpgInfo) = EpgMatcher(channels.toList()).apply {
        registerChannel("ITV.ua", listOf("ITV"))
        registerChannel("ITV.uk", listOf("ITV"))
    }

    @Test
    fun countryPrefixPicksTheRightGuideChannelAmongNamesakes() {
        val m = itvMatcher(ChannelEpgInfo(tvgId = null, name = "UK: ITV HD"))
        assertTrue(m.keysFor("ITV.ua").isEmpty())
        assertEquals(setOf("n:ukitv"), m.keysFor("ITV.uk"))
    }

    @Test
    fun tvgIdCountrySuffixPicksTheRightGuideChannelAmongNamesakes() {
        val m = itvMatcher(ChannelEpgInfo(tvgId = "ITV1.uk@HD", name = "ITV HD"))
        assertTrue(m.keysFor("ITV.ua").isEmpty())
        assertEquals(setOf("itv1.uk@hd"), m.keysFor("ITV.uk"))
    }

    @Test
    fun theOrderOfGuideChannelsDoesNotMatter() {
        val m = EpgMatcher(listOf(ChannelEpgInfo(tvgId = null, name = "UK: ITV HD")))
        m.registerChannel("ITV.uk", listOf("ITV"))
        m.registerChannel("ITV.ua", listOf("ITV"))
        assertEquals(setOf("n:ukitv"), m.keysFor("ITV.uk"))
        assertTrue(m.keysFor("ITV.ua").isEmpty())
    }

    @Test
    fun namesakesWithoutAWayToTellThemApartMatchNothing() {
        val m = itvMatcher(ChannelEpgInfo(tvgId = null, name = "ITV HD"))
        assertTrue(m.keysFor("ITV.ua").isEmpty())
        assertTrue(m.keysFor("ITV.uk").isEmpty())
    }

    @Test
    fun aNameNeverMatchesAcrossCountries() {
        val m = EpgMatcher(listOf(ChannelEpgInfo(tvgId = "ITV1.uk", name = "ITV")))
        m.registerChannel("ITV.ua", listOf("ITV"))
        m.registerChannel("itv-ua", listOf("UA: ITV"))
        assertTrue(m.keysFor("ITV.ua").isEmpty())
        assertTrue(m.keysFor("itv-ua").isEmpty())
    }

    @Test
    fun anIdMatchIsDroppedWhenTheCountriesDisagree() {
        val m = EpgMatcher(listOf(ChannelEpgInfo(tvgId = "ITV", name = "UK: ITV HD")))
        m.registerChannel("ITV", listOf("UA: ITV"))
        assertTrue(m.keysFor("ITV").isEmpty())
    }

    @Test
    fun aCountryPrefixStillMatchesAPlainGuideName() {
        val m = EpgMatcher(listOf(ChannelEpgInfo(tvgId = null, name = "UK: ITV HD")))
        m.registerChannel("itv", listOf("ITV"))
        assertEquals(setOf("n:ukitv"), m.keysFor("itv"))
    }

    @Test
    fun channelsMatchedByIdTakeNoNameMatches() {
        val m = EpgMatcher(listOf(ChannelEpgInfo(tvgId = "ITV.uk", name = "ITV HD")))
        m.registerChannel("ITV.uk", listOf("ITV"))
        m.registerChannel("itv-other", listOf("ITV"))
        assertEquals(setOf("itv.uk"), m.keysFor("ITV.uk"))
        assertTrue(m.keysFor("itv-other").isEmpty())
    }

    @Test
    fun readsCountries() {
        assertEquals("gb", channelCountry(null, "UK: ITV HD"))
        assertEquals("gb", channelCountry("ITV1.uk@HD", "ITV"))
        assertEquals("ua", channelCountry("ITV.ua", "UK: ITV"))
        assertEquals("pl", channelCountry(null, "[PL] TVN"))
        assertEquals("de", channelCountry(null, "DE | Das Erste"))
        assertEquals("fr", channelCountry(null, "TF1 (FR)"))
        assertEquals(null, channelCountry("BBCOne", "BBC One HD"))
        assertEquals(null, channelCountry(null, "E4"))
    }
}
