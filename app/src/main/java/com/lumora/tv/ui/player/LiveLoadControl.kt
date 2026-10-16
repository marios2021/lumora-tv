package com.lumora.tv.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.lumora.tv.data.repo.BufferProfile

/** The longest a stall makes playback wait before resuming. */
private const val MAX_RESUME_MS = 30_000L

/**
 * Buffering for live TV that learns from stalls.
 *
 * A plain live stream (MPEG-TS) arrives in real time, so the player never gets further ahead than
 * the few seconds it waited before starting: any network hiccup longer than that is a stall. After
 * each stall this waits twice as long before resuming (e.g. 5, 10, 20, 30 s), which leaves that
 * much headroom for the next hiccup. HLS keeps its own headroom (several segments behind live), so
 * it uses the normal rules.
 */
@OptIn(UnstableApi::class)
class LiveLoadControl private constructor(
    private val baseResumeMs: Long,
    min: Int,
    max: Int,
    start: Int,
    resume: Int,
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    min,
    max,
    start,
    resume,
    C.LENGTH_UNSET,
    false,
    BACK_BUFFER_MS,
    true,
) {
    /** Stalls on the current stream. */
    @Volatile
    var stalls = 0
        private set

    /** How much buffer a resume after a stall waits for now. */
    @Volatile
    var resumeTargetMs = baseResumeMs
        private set

    /** Only HLS rewinds within the player's buffer; for MPEG-TS the back buffer would just use memory. */
    @Volatile
    private var keepBackBuffer = true

    private var inStall = false

    /** A new channel: forget the last one's stalls. */
    fun newStream(isHls: Boolean) {
        keepBackBuffer = isHls
        stalls = 0
        resumeTargetMs = baseResumeMs
        inStall = false
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        // HLS / DASH live (with a target offset) and normal starts: the usual thresholds.
        if (!parameters.rebuffering || parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            inStall = false
            return super.shouldStartPlayback(parameters)
        }
        if (!inStall) {
            inStall = true
            stalls++
            resumeTargetMs = (baseResumeMs shl (stalls - 1).coerceAtMost(4)).coerceAtMost(MAX_RESUME_MS)
        }
        val ready = parameters.bufferedDurationUs >= resumeTargetMs * 1000
        if (ready) inStall = false
        return ready
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        if (keepBackBuffer) super.getBackBufferDurationUs(playerId) else 0

    companion object {
        private val BACK_BUFFER_MS = (MAX_REWIND_MS + 5_000).toInt()

        fun create(profile: BufferProfile): LiveLoadControl {
            // (min buffer, max buffer, buffer before starting, buffer before resuming after a stall), in ms.
            val (min, max, start, resume) = when (profile) {
                BufferProfile.FAST_START -> listOf(15_000, 50_000, 1_000, 2_000)
                BufferProfile.BALANCED -> listOf(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                BufferProfile.SMOOTH -> listOf(30_000, 90_000, 4_000, 8_000)
            }
            return LiveLoadControl(resume.toLong(), min, max, start, resume)
        }
    }
}
