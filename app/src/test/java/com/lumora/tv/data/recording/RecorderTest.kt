package com.lumora.tv.data.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecorderTest {

    @Test
    fun picksTheHighestBandwidthVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            mid/index.m3u8
        """.trimIndent()
        assertEquals("high/index.m3u8", Recorder.bestVariant(master))
    }

    @Test
    fun noVariantsInAMediaPlaylist() {
        assertNull(Recorder.bestVariant("#EXTM3U\n#EXTINF:6,\nseg1.ts\n"))
    }

    @Test
    fun resolvesSegmentsAgainstThePlaylist() {
        val base = "http://cdn.example/live/ch1/index.m3u8?token=a"
        assertEquals("http://cdn.example/live/ch1/seg42.ts", Recorder.resolve(base, "seg42.ts"))
        assertEquals("http://cdn.example/other/seg.ts", Recorder.resolve(base, "/other/seg.ts"))
        assertEquals("https://elsewhere/seg.ts", Recorder.resolve(base, "https://elsewhere/seg.ts"))
    }
}
