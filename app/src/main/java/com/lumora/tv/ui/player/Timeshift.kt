package com.lumora.tv.ui.player

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer

/** How far back live TV can be rewound. */
const val MAX_REWIND_MS = 30_000L

/** One rewind / fast-forward step. */
const val REWIND_STEP_MS = 10_000L

/** Room for [MAX_REWIND_MS] of an ~8 Mbit/s stream; higher bitrates get a shorter rewind. */
private const val BUFFER_BYTES = 32 * 1024 * 1024

/** MPEG-TS packets are 188 bytes, each starting with this sync byte. */
private const val TS_PACKET = 188
private const val TS_SYNC: Byte = 0x47

/**
 * Live TV with rewind.
 *
 * HLS and DASH streams already offer a window of recent segments, so they are rewound by seeking
 * inside it. Plain HTTP streams (MPEG-TS, the IPTV norm) can't seek: they are read by a background
 * thread into a [TimeshiftBuffer] and played from there, and rewinding restarts playback from an
 * earlier point in that buffer, without reconnecting to the server.
 *
 * Positions are measured as "behind live": 0 while watching live, more after a rewind or a pause.
 */
@OptIn(UnstableApi::class)
class LivePlayback(private val player: ExoPlayer, private val http: OkHttpClient) {
    private var buffer: TimeshiftBuffer? = null
    private var writer: TimeshiftWriter? = null
    private var generation = 0
    private var url: String? = null

    /** Buffer playback: the byte playback started from. */
    private var startPos = 0L

    /** Delay behind the source while watching live: the zero point of [behindMs]. */
    private var liveLatency = 0L

    /** True until the viewer rewinds or pauses; [goLive] sets it again. */
    var atLive = true
        private set

    private val buffered get() = buffer != null && writer != null

    /** True when this stream plays through the timeshift buffer (plain HTTP), false for HLS / DASH. */
    val usesTimeshift: Boolean get() = buffered

    /** How fast the stream is arriving, in bytes per second over the last few seconds; null if unknown. */
    fun incomingBytesPerSecond(): Long? = if (buffered) buffer?.rate(generation) else null

    /** Starts [url] live from the network. */
    fun play(url: String) {
        stopWriter()
        this.url = url
        atLive = true
        liveLatency = 0
        val item = mediaItemFor(url)
        if (item.localConfiguration?.mimeType == null && url.startsWith("http", ignoreCase = true)) {
            val b = buffer ?: TimeshiftBuffer(BUFFER_BYTES).also { buffer = it }
            generation = b.reset()
            writer = TimeshiftWriter(http, url, b, generation).also { it.start() }
            playFromBuffer(0)
        } else {
            player.setMediaItem(item)
            player.prepare()
        }
        player.playWhenReady = true
    }

    /** Rejoins live on the last stream, e.g. when the app comes back to the foreground. */
    fun restart() {
        url?.let(::play)
    }

    fun stop() {
        stopWriter()
        player.stop()
    }

    /** True when this stream can be rewound at all (HLS / DASH live window, or a buffered MPEG-TS stream). */
    val canRewind: Boolean
        get() = if (buffered) buffer?.isTs == true else player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable

    /** How far behind live playback is, in ms. Call regularly: while live it also tracks the zero point. */
    fun behindMs(): Long {
        val raw = rawDelay() ?: return 0
        if (atLive) {
            if (player.isPlaying) liveLatency = raw
            return 0
        }
        return (raw - liveLatency).coerceAtLeast(0)
    }

    /**
     * Moves by [deltaMs]: negative rewinds, positive goes forward (and rejoins live when it gets there).
     * Returns false when it can't go any further.
     */
    fun seekBy(deltaMs: Long): Boolean {
        if (!canRewind) return false
        val behind = behindMs()
        val limit = maxOf(behind, minOf(MAX_REWIND_MS, availableBehind(behind)))
        val target = (behind - deltaMs).coerceIn(0, limit)
        if (target == behind) return false
        if (deltaMs > 0 && target < 1_000) {
            goLive()
            return true
        }
        seekToBehind(target)
        atLive = false
        return true
    }

    fun goLive() {
        if (atLive) return
        if (buffered) seekToBehind(0) else player.seekToDefaultPosition()
        atLive = true
        player.play()
    }

    /** A long pause can leave an HLS stream's window behind: rejoin live. Returns true if handled. */
    fun recover(e: PlaybackException): Boolean {
        if (e.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || buffered) return false
        atLive = true
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
        return true
    }

    fun togglePause() {
        if (player.playWhenReady) {
            player.pause()
            atLive = false
        } else {
            player.play()
        }
    }

    /** Delay between the source and what's on screen, or null if not known yet. */
    private fun rawDelay(): Long? {
        if (buffered) {
            val arrival = buffer?.arrivalAt(generation, startPos) ?: return null
            return SystemClock.elapsedRealtime() - (arrival + player.currentPosition)
        }
        if (!player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET) return null
        return player.duration - player.currentPosition
    }

    /** The furthest behind live that the buffer or live window still reaches. */
    private fun availableBehind(behind: Long): Long {
        if (buffered) {
            val oldest = buffer?.oldestArrival(generation) ?: return 0
            return SystemClock.elapsedRealtime() - liveLatency - oldest - 1_000
        }
        return behind + (player.currentPosition - 2_000).coerceAtLeast(0)
    }

    private fun seekToBehind(behind: Long) {
        if (buffered) {
            val b = buffer ?: return
            val wanted = SystemClock.elapsedRealtime() - liveLatency - behind
            playFromBuffer(b.positionAt(generation, wanted))
            player.play()
        } else {
            player.seekTo((player.duration - liveLatency - behind).coerceAtLeast(0))
        }
    }

    private fun playFromBuffer(position: Long) {
        val b = buffer ?: return
        val gen = generation
        startPos = position
        val source = ProgressiveMediaSource.Factory(DataSource.Factory { TimeshiftDataSource(b, gen, position) })
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
            .createMediaSource(MediaItem.fromUri(url.orEmpty()))
        player.setMediaSource(source)
        player.prepare()
    }

    private fun stopWriter() {
        writer?.close()
        writer = null
    }
}

/** A ring buffer holding the most recent bytes of a live stream, with the time each part arrived. */
class TimeshiftBuffer(private val capacity: Int) {
    private class Mark(val time: Long, val pos: Long)

    private val data: ByteBuffer = ByteBuffer.allocateDirect(capacity)
    private val lock = Object()
    private var generation = 0
    private var written = 0L
    private var error: IOException? = null
    private var ended = false
    private val marks = ArrayDeque<Mark>()

    /** Whether the stream is MPEG-TS, so playback can restart from any packet. Null until known. */
    @Volatile
    var isTs: Boolean? = null
        private set

    /** Empties the buffer for a new stream. Writers and readers of earlier streams stop. */
    fun reset(): Int = synchronized(lock) {
        generation++
        written = 0
        error = null
        ended = false
        marks.clear()
        isTs = null
        lock.notifyAll()
        generation
    }

    /** Returns false if this stream has been replaced and the writer should stop. */
    fun write(gen: Int, src: ByteArray, length: Int): Boolean {
        synchronized(lock) {
            if (gen != generation) return false
            val start = written
            var off = 0
            while (off < length) {
                val at = (written % capacity).toInt()
                val n = minOf(length - off, capacity - at)
                (data.duplicate().position(at) as ByteBuffer).put(src, off, n)
                written += n
                off += n
            }
            val now = SystemClock.elapsedRealtime()
            if (marks.isEmpty() || now - marks.last().time >= 200) marks.addLast(Mark(now, start))
            val oldest = oldest()
            while (marks.size > 1 && marks.first().pos < oldest) marks.removeFirst()
            if (isTs == null && written >= TS_PACKET * 8) isTs = findSync(0, TS_PACKET * 4) != null
            lock.notifyAll()
            return true
        }
    }

    fun fail(gen: Int, e: IOException) = synchronized(lock) {
        if (gen == generation) {
            error = e
            lock.notifyAll()
        }
    }

    fun finish(gen: Int) = synchronized(lock) {
        if (gen == generation) {
            ended = true
            lock.notifyAll()
        }
    }

    /**
     * Copies bytes from [cursor] on, waiting for the stream if the reader has caught up with it.
     * A reader that fell so far behind that its data was overwritten skips ahead.
     */
    fun read(gen: Int, cursor: LongArray, dst: ByteArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            while (true) {
                if (gen != generation) return C.RESULT_END_OF_INPUT
                if (cursor[0] < oldest()) cursor[0] = align(oldest() + capacity / 8)
                if (cursor[0] < written) break
                error?.let { throw it }
                if (ended) return C.RESULT_END_OF_INPUT
                try {
                    lock.wait(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
            val n = minOf(length.toLong(), written - cursor[0]).toInt()
            var done = 0
            while (done < n) {
                val at = ((cursor[0] + done) % capacity).toInt()
                val c = minOf(n - done, capacity - at)
                (data.duplicate().position(at) as ByteBuffer).get(dst, offset + done, c)
                done += c
            }
            cursor[0] += n
            return n
        }
    }

    /** Bytes per second received over about the last five seconds. */
    fun rate(gen: Int): Long? = synchronized(lock) {
        if (gen != generation) return@synchronized null
        val now = SystemClock.elapsedRealtime()
        val since = marks.firstOrNull { it.time >= now - 5_000 } ?: return@synchronized null
        val ms = now - since.time
        if (ms < 1_000) null else (written - since.pos) * 1000 / ms
    }

    /** When the byte at [pos] arrived (elapsedRealtime), or null before any data. */
    fun arrivalAt(gen: Int, pos: Long): Long? = synchronized(lock) {
        if (gen != generation) null else marks.lastOrNull { it.pos <= pos }?.time ?: marks.firstOrNull()?.time
    }

    fun oldestArrival(gen: Int): Long? = synchronized(lock) {
        if (gen != generation) null else marks.firstOrNull { it.pos >= oldest() }?.time
    }

    /** The first packet that arrived at or after [time], or the live edge. */
    fun positionAt(gen: Int, time: Long): Long = synchronized(lock) {
        if (gen != generation) {
            0
        } else {
            val oldest = oldest()
            align(marks.firstOrNull { it.time >= time && it.pos >= oldest }?.pos ?: marks.lastOrNull()?.pos ?: 0)
        }
    }

    private fun oldest() = (written - capacity).coerceAtLeast(0)

    private fun align(pos: Long): Long = findSync(pos, TS_PACKET) ?: pos

    /** The first position from [from] (within [range] bytes) where three packets line up. */
    private fun findSync(from: Long, range: Int): Long? {
        var i = maxOf(from, oldest())
        val end = minOf(from + range, written - TS_PACKET * 2)
        while (i < end) {
            if (byteAt(i) == TS_SYNC && byteAt(i + TS_PACKET) == TS_SYNC && byteAt(i + TS_PACKET * 2) == TS_SYNC) return i
            i++
        }
        return null
    }

    private fun byteAt(pos: Long): Byte = data.get((pos % capacity).toInt())
}

/** Reads the stream from the network into a [TimeshiftBuffer] until closed. */
@OptIn(UnstableApi::class)
private class TimeshiftWriter(
    http: OkHttpClient,
    private val url: String,
    private val buffer: TimeshiftBuffer,
    private val gen: Int,
) : Thread("Lumora-timeshift") {
    private val dataSpec = DataSpec(Uri.parse(url))
    private val call: Call? = runCatching { http.newCall(Request.Builder().url(url).build()) }.getOrNull()

    init {
        isDaemon = true
    }

    override fun run() {
        if (call == null) {
            buffer.fail(gen, IOException("Not a valid stream address"))
            return
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpDataSource.InvalidResponseCodeException(
                        response.code, response.message, null, response.headers.toMultimap(), dataSpec, ByteArray(0),
                    )
                }
                val input = response.body?.byteStream() ?: throw IOException("Empty response")
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) {
                        buffer.finish(gen)
                        break
                    }
                    if (n > 0 && !buffer.write(gen, chunk, n)) break
                }
            }
        } catch (e: HttpDataSource.HttpDataSourceException) {
            buffer.fail(gen, e)
        } catch (e: IOException) {
            if (!call.isCanceled()) {
                buffer.fail(gen, HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_READ))
            }
        }
    }

    fun close() {
        call?.cancel()
    }
}

/** Plays a [TimeshiftBuffer] from [startPos]: ExoPlayer sees an endless progressive stream. */
@OptIn(UnstableApi::class)
private class TimeshiftDataSource(
    private val buffer: TimeshiftBuffer,
    private val gen: Int,
    private val startPos: Long,
) : DataSource {
    private val cursor = LongArray(1)
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        cursor[0] = startPos + dataSpec.position
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return this.buffer.read(gen, cursor, buffer, offset, length)
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
    }
}

fun mediaItemFor(url: String): MediaItem {
    val builder = MediaItem.Builder().setUri(url)
    val lower = url.lowercase()
    when {
        ".m3u8" in lower -> builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        ".mpd" in lower -> builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
    }
    return builder.build()
}

/** "−0:20" style label for a delay behind live. */
fun behindLabel(ms: Long): String {
    val s = (ms + 500) / 1000
    return "−%d:%02d".format(s / 60, s % 60)
}
