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

    // The provider tags "UK: ITV HD" with "srf1" (a Swiss channel) and both Channel 4 and Dave with "4seven.uk".
    private fun overrideMatcher() = EpgMatcher(
        listOf(
            ChannelEpgInfo(tvgId = "srf1", name = "UK: ITV HD", guideOverride = "ITV1.uk"),
            ChannelEpgInfo(tvgId = "srf1", name = "CH: SRF 1 HD"),
            ChannelEpgInfo(tvgId = "4seven.uk", name = "UK: CHANNEL 4 FHD", guideOverride = "Channel4.uk"),
            ChannelEpgInfo(tvgId = "4seven.uk", name = "UK: DAVE HD", guideOverride = ""),
        ),
    ).apply {
        registerChannel("srf1", listOf("SRF 1"))
        registerChannel("ITV1.uk", listOf("ITV1"))
        registerChannel("Channel4.uk", listOf("Channel 4"))
        registerChannel("4seven.uk", listOf("4seven"))
        registerChannel("Dave.uk", listOf("Dave"))
    }

    @Test
    fun aPickedGuideChannelFeedsTheOverriddenChannel() {
        val m = overrideMatcher()
        assertEquals("g:itv1.uk", epgKeyFor("srf1", "UK: ITV HD", "ITV1.uk"))
        assertEquals(setOf("g:itv1.uk"), m.keysFor("ITV1.uk"))
        assertEquals(setOf("g:channel4.uk"), m.keysFor("channel4.UK"))
    }

    @Test
    fun anOverriddenChannelLosesItsIdMatchButOthersSharingTheIdKeepIt() {
        val m = overrideMatcher()
        // "srf1" still feeds the real SRF 1 under the shared key; ITV reads "g:itv1.uk" instead.
        assertEquals(setOf("srf1"), m.keysFor("srf1"))
        // Every channel tagged "4seven.uk" is overridden, so 4seven feeds nobody.
        assertTrue(m.keysFor("4seven.uk").isEmpty())
    }

    @Test
    fun noneMeansNoGuideAtAll() {
        val m = overrideMatcher()
        // Dave matches nothing: neither its tvg-id nor its name ("UK: DAVE HD" ~ "Dave").
        assertEquals("g:", epgKeyFor("4seven.uk", "UK: DAVE HD", ""))
        assertTrue(m.keysFor("Dave.uk").isEmpty())
    }

    @Test
    fun overriddenChannelsTakeNoNameMatches() {
        val m = EpgMatcher(listOf(ChannelEpgInfo(tvgId = null, name = "ITV", guideOverride = "itv.ua")))
        m.registerChannel("ITV.uk", listOf("ITV"))
        m.registerChannel("ITV.ua", listOf("ITV"))
        assertTrue(m.keysFor("ITV.uk").isEmpty())
        assertEquals(setOf("g:itv.ua"), m.keysFor("ITV.ua"))
    }

    @Test
    fun aPickedGuideChannelCanAlsoMatchOtherChannelsAutomatically() {
        val m = EpgMatcher(
            listOf(
                ChannelEpgInfo(tvgId = "ITV1.uk", name = "ITV 1"),
                ChannelEpgInfo(tvgId = "srf1", name = "UK: ITV HD", guideOverride = "ITV1.uk"),
            ),
        )
        m.registerChannel("ITV1.uk", listOf("ITV1"))
        assertEquals(setOf("itv1.uk", "g:itv1.uk"), m.keysFor("ITV1.uk"))
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
