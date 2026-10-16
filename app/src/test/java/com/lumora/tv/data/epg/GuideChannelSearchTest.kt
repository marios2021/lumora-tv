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
