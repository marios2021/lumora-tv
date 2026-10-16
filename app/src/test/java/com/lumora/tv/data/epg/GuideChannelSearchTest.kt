package com.lumora.tv.data.epg

import com.lumora.tv.data.db.GuideChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideChannelSearchTest {

    private fun guide(id: String, vararg names: String) = GuideChannelEntity(1, id, names.joinToString("\n"))

    private val search = GuideChannelSearch(
        listOf(
            guide("srf1", "SRF 1"),
            guide("ITV1.uk", "ITV1", "ITV 1 HD"),
            guide("ITV2.uk", "ITV2"),
            guide("Channel4.uk", "Channel 4"),
            guide("Channel4Plus1.uk", "Channel 4 +1"),
            guide("4seven.uk", "4seven"),
            guide("Dave.uk", "Dave"),
            guide("nameless.id"),
        ),
    )

    private fun ids(list: List<GuideChannelEntity>) = list.map { it.guideChannelId }

    @Test
    fun suggestsGuideChannelsNamedLikeThePlaylistChannel() {
        assertEquals(listOf("Channel4.uk", "Channel4Plus1.uk"), ids(search.suggestionsFor("UK: CHANNEL 4 FHD")))
        assertEquals(listOf("Dave.uk"), ids(search.suggestionsFor("UK: DAVE HD")))
        assertEquals(listOf("ITV1.uk", "ITV2.uk"), ids(search.suggestionsFor("UK: ITV HD")))
    }

    // Names as in the epgshare01 UK guide.
    @Test
    fun suggestsTheClosestNamesFirst() {
        val uk = GuideChannelSearch(
            listOf(
                guide("ITV.Quiz.uk", "ITV Quiz"),
                guide("CITV.uk", "CITV"),
                guide("ITV1+1.uk", "ITV1+1"),
                guide("ITV1.HD.uk", "ITV1 HD"),
                guide("U.and.Dave.ja.vu.uk", "U and Dave ja vu"),
                guide("U.and.Dave.HD.uk", "U and Dave HD"),
                guide("Channel.44.uk", "Channel 44"),
                guide("Channel.4.HD.uk", "Channel 4 HD"),
            ),
        )
        assertEquals(listOf("ITV1.HD.uk", "ITV1+1.uk", "ITV.Quiz.uk", "CITV.uk"), ids(uk.suggestionsFor("UK: ITV HD")))
        assertEquals(listOf("U.and.Dave.HD.uk", "U.and.Dave.ja.vu.uk"), ids(uk.suggestionsFor("UK: DAVE HD")))
        assertEquals(listOf("Channel.4.HD.uk", "Channel.44.uk"), ids(uk.suggestionsFor("UK: CHANNEL 4 FHD")))
    }

    @Test
    fun readsSeveralEpgUrls() {
        assertEquals(listOf("http://a/x.xml", "http://b/y.xml.gz"), epgUrls(" http://a/x.xml,http://b/y.xml.gz\n"))
        assertEquals(emptyList<String>(), epgUrls(null))
    }

    @Test
    fun searchesNamesAndIdsIgnoringCaseAndSpaces() {
        assertEquals(listOf("ITV1.uk"), ids(search.search("itv 1")))
        assertEquals(listOf("Channel4.uk", "Channel4Plus1.uk"), ids(search.search("channel4")))
        assertEquals(listOf("nameless.id"), ids(search.search("NAMELESS")))
        assertEquals(listOf("4seven.uk", "Channel4.uk", "Channel4Plus1.uk"), ids(search.search("4")).take(3))
    }

    @Test
    fun aBlankQueryListsEverythingByName() {
        assertEquals(8, search.search("  ").size)
        assertEquals("4seven.uk", search.search("").first().guideChannelId)
    }
}
