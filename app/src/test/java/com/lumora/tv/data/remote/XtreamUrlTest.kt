package com.lumora.tv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamUrlTest {

    @Test
    fun switchesXtreamLiveStreamsToTheChosenFormat() {
        val ts = "http://host:8080/live/user/pass/123.ts"
        assertEquals("http://host:8080/live/user/pass/123.m3u8", XtreamClient.playableUrl("xt:123", ts, "m3u8"))
        assertEquals(ts, XtreamClient.playableUrl("xt:123", "http://host:8080/live/user/pass/123.M3U8", "ts"))
    }

    @Test
    fun leavesM3uChannelsAlone() {
        val url = "http://cdn.example/stream.ts"
        assertEquals(url, XtreamClient.playableUrl(url, url, "m3u8"))
    }
}
