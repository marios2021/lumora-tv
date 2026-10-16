package com.lumora.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import com.lumora.tv.data.repo.BufferProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLoadControlTest {

    private fun params(bufferedMs: Long, rebuffering: Boolean, liveOffsetUs: Long = C.TIME_UNSET) = LoadControl.Parameters(
        PlayerId.UNSET,
        Timeline.EMPTY,
        MediaSource.MediaPeriodId(Any()),
        0,
        bufferedMs * 1000,
        1f,
        true,
        rebuffering,
        liveOffsetUs,
    )

    /** Feeds a stall from empty until playback resumes; returns the buffer it resumed with. */
    private fun stall(control: LiveLoadControl): Long {
        var buffered = 0L
        while (!control.shouldStartPlayback(params(buffered, rebuffering = true))) buffered += 1_000
        return buffered
    }

    @Test
    fun eachStallWaitsLongerUpToThirtySeconds() {
        val control = LiveLoadControl.create(BufferProfile.BALANCED)
        control.newStream(isHls = false)
        assertEquals(5_000, stall(control))
        assertEquals(10_000, stall(control))
        assertEquals(20_000, stall(control))
        assertEquals(30_000, stall(control))
        assertEquals(30_000, stall(control))
        assertEquals(5, control.stalls)
    }

    @Test
    fun aNewChannelStartsOver() {
        val control = LiveLoadControl.create(BufferProfile.BALANCED)
        control.newStream(isHls = false)
        stall(control)
        stall(control)
        control.newStream(isHls = false)
        assertEquals(0, control.stalls)
        assertEquals(5_000, stall(control))
    }

    @Test
    fun hlsLiveKeepsTheNormalRules() {
        val control = LiveLoadControl.create(BufferProfile.BALANCED)
        control.newStream(isHls = true)
        // With a live offset (HLS), a resume needs at most half the offset, as in DefaultLoadControl.
        assertTrue(control.shouldStartPlayback(params(5_000, rebuffering = true, liveOffsetUs = 10_000_000)))
        assertEquals(0, control.stalls)
    }

    @Test
    fun firstStartIsNotAStall() {
        val control = LiveLoadControl.create(BufferProfile.BALANCED)
        control.newStream(isHls = false)
        control.shouldStartPlayback(params(1_000, rebuffering = false))
        control.shouldStartPlayback(params(2_500, rebuffering = false))
        assertEquals(0, control.stalls)
    }
}
