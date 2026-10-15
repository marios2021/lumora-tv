package com.lumora.tv.data.epg

import com.lumora.tv.data.db.ChannelEntity
import com.lumora.tv.data.db.PlaylistEntity
import com.lumora.tv.data.db.PlaylistType
import com.lumora.tv.data.parser.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupTest {

    // 2026-10-15 20:00:00 UTC, an hour-long programme ending at 21:00 UTC.
    private val start = 1792094400_000L
    private val stop = start + 3_600_000L
    private val now = stop + 600_000L

    private fun channel(
        url: String = "http://host/live/1.m3u8",
        key: String = url,
        days: Int = 3,
        type: String? = null,
        source: String? = null,
    ) = ChannelEntity(
        playlistId = 1, channelKey = key, name = "Test", groupTitle = "G", logo = null,
        streamUrl = url, tvgId = null, sortOrder = 0, catchupDays = days, catchupType = type, catchupSource = source,
    )

    private val xtream = PlaylistEntity(
        id = 1, name = "X", type = PlaylistType.XTREAM, url = "http://panel.example:8080",
        username = "user", password = "pass", serverTimezone = "Europe/London",
    )

    @Test
    fun xtreamUsesServerTimeZoneAndMinutes() {
        val c = channel(url = "http://panel.example:8080/live/user/pass/1001.ts", key = "xt:1001", type = "xc")
        // 20:00 UTC is 21:00 in London (summer time).
        assertEquals(
            "http://panel.example:8080/timeshift/user/pass/60/2026-10-15:21-00/1001.ts",
            Catchup.url(xtream, c, start, stop, now),
        )
    }

    @Test
    fun xtreamWithoutTimeZoneUsesUtc() {
        val c = channel(key = "xt:7", type = "xc")
        val url = Catchup.url(xtream.copy(serverTimezone = null), c, start, stop + 30_000, now)
        assertEquals("http://panel.example:8080/timeshift/user/pass/61/2026-10-15:20-00/7.ts", url)
    }

    @Test
    fun defaultFillsTemplate() {
        val c = channel(type = "default", source = "http://arch/x.m3u8?start={utc}&end=\${end}&d={duration:60}&t={Y}{m}{d}{H}{M}")
        assertEquals(
            "http://arch/x.m3u8?start=1792094400&end=1792098000&d=60&t=202610152000",
            Catchup.url(xtream, c, start, stop, now),
        )
    }

    @Test
    fun appendAddsSourceToStreamUrl() {
        val c = channel(type = "append", source = "?utc={utc}&lutc={lutc}")
        assertEquals("http://host/live/1.m3u8?utc=1792094400&lutc=1792098600", Catchup.url(xtream, c, start, stop, now))
    }

    @Test
    fun shiftAddsUtcParameters() {
        val c = channel(url = "http://host/live/1.ts?token=a", type = "shift")
        assertEquals("http://host/live/1.ts?token=a&utc=1792094400&lutc=1792098600", Catchup.url(xtream, c, start, stop, now))
    }

    @Test
    fun flussonicRewritesPath() {
        val hls = channel(url = "http://fl/ch1/index.m3u8?token=t", type = "flussonic")
        assertEquals("http://fl/ch1/index-1792094400-3600.m3u8?token=t", Catchup.url(xtream, hls, start, stop, now))
        val ts = channel(url = "http://fl/ch1/mpegts", type = "fs")
        assertEquals("http://fl/ch1/timeshift_abs-1792094400.ts", Catchup.url(xtream, ts, start, stop, now))
    }

    @Test
    fun availabilityNeedsAnEndedProgrammeInsideTheArchive() {
        val c = channel(days = 1, type = "shift")
        assertTrue(Catchup.isAvailable(c, start, stop, now))
        assertFalse("still airing", Catchup.isAvailable(c, start, now + 1, now))
        assertFalse("older than the archive", Catchup.isAvailable(c, start, stop, start + 2 * 86_400_000L))
        assertFalse("no archive", Catchup.isAvailable(channel(days = 0), start, stop, now))
    }

    @Test
    fun unknownTypeHasNoUrl() {
        assertNull(Catchup.url(xtream, channel(type = "mystery"), start, stop, now))
    }

    @Test
    fun m3uCatchupAttributesAreRead() {
        val parsed = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup="default" catchup-days="5" catchup-source="http://a/{utc}",One
            http://s/1
            #EXTINF:-1 timeshift="2",Two
            http://s/2
            #EXTINF:-1,Three
            http://s/3
            """.trimIndent().reader().buffered(),
        ).channels
        assertEquals(5, parsed[0].catchupDays)
        assertEquals("default", parsed[0].catchupType)
        assertEquals("http://a/{utc}", parsed[0].catchupSource)
        assertEquals(2, parsed[1].catchupDays)
        assertNull(parsed[1].catchupType)
        assertEquals(0, parsed[2].catchupDays)
    }
}
