package com.lumora.tv.data.epg

/** A free XMLTV guide that can be added to a playlist in one tap, for channels its own guide lacks. */
data class GuideSource(val name: String, val description: String, val url: String)

object GuideSources {
    val all = listOf(
        GuideSource(
            name = "United Kingdom (epgshare01)",
            description = "About 490 UK channels: ITV, Channel 4, Channel 5, UKTV, Sky, BBC regions. 3 MB download.",
            url = "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
        ),
        GuideSource(
            name = "UK Freeview (Freeview-EPG)",
            description = "About 280 free-to-air channels, with regional versions. A large download (about 20 MB).",
            url = "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml",
        ),
    )
}
