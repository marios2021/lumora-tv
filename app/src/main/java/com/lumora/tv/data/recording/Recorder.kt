package com.lumora.tv.data.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Saves a live stream to a file until a given time. Plain HTTP streams (MPEG-TS) are copied byte
 * for byte and reconnected if they drop; they go through [hub], so recording the channel being
 * watched shares the player's connection. HLS is recorded by polling the media playlist and
 * appending each new TS segment. Encrypted or fMP4 HLS can't be recorded this way.
 */
class Recorder(private val http: OkHttpClient, private val hub: StreamHub = StreamHub()) {

    /** Records [url] into [sink] until [endAt] (epoch ms) or cancellation. [onBytes] gets the running total. */
    suspend fun record(url: String, sink: OutputStream, endAt: Long, onBytes: (Long) -> Unit): Long {
        var total = 0L
        val counting: (Int) -> Unit = { n ->
            total += n
            onBytes(total)
        }
        if (isHls(url)) recordHls(url, sink, endAt, counting) else recordProgressive(url, sink, endAt, counting)
        return total
    }

    private fun isHls(url: String) = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    private suspend fun recordProgressive(url: String, sink: OutputStream, endAt: Long, counted: (Int) -> Unit) {
        var failures = 0
        while (System.currentTimeMillis() < endAt) {
            coroutineContext.ensureActive()
            try {
                copyUntil(url, sink, endAt, counted)
                failures = 0
            } catch (e: IOException) {
                if (System.currentTimeMillis() >= endAt) return
                if (++failures >= 5) throw e
            }
            // Live streams drop now and then: reconnect and carry on.
            if (System.currentTimeMillis() < endAt) delay(2_000)
        }
    }

    /**
     * Copies the stream until it ends or [endAt] passes. Shares the connection with the player
     * when it is watching the same stream, so the account's connection limit isn't hit.
     */
    private suspend fun copyUntil(url: String, sink: OutputStream, endAt: Long, counted: (Int) -> Unit) {
        val end = CompletableDeferred<IOException?>()
        // Data arrives on the connection's thread; sink errors end this attachment.
        val reader = object : StreamHub.Reader {
            override fun onData(bytes: ByteArray, length: Int) {
                if (end.isCompleted) return
                try {
                    sink.write(bytes, 0, length)
                    counted(length)
                } catch (e: IOException) {
                    end.complete(e)
                }
            }

            override fun onEnd(error: IOException?) {
                end.complete(error)
            }
        }
        val attachment = hub.attach(url, http, reader)
        try {
            val error = withTimeoutOrNull((endAt - System.currentTimeMillis()).coerceAtLeast(1)) { end.await() }
            if (error != null && System.currentTimeMillis() < endAt) throw error
        } finally {
            // Stops delivery before the caller touches the sink again (or closes it).
            withContext(NonCancellable + Dispatchers.IO) { attachment.close() }
        }
    }

    private suspend fun recordHls(url: String, sink: OutputStream, endAt: Long, counted: (Int) -> Unit) {
        var mediaUrl = url
        val master = fetchText(mediaUrl)
        if ("#EXT-X-STREAM-INF" in master) {
            mediaUrl = resolve(mediaUrl, bestVariant(master) ?: throw IOException("No playable variant in the HLS playlist."))
        }
        val seen = HashSet<String>()
        while (System.currentTimeMillis() < endAt) {
            coroutineContext.ensureActive()
            val playlist = runCatching { fetchText(mediaUrl) }.getOrNull()
            if (playlist == null) {
                delay(2_000)
                continue
            }
            if ("#EXT-X-MAP" in playlist) throw IOException("This stream uses fMP4 segments, which can't be recorded.")
            val key = Regex("""#EXT-X-KEY:.*METHOD=([A-Z0-9-]+)""").find(playlist)?.groupValues?.get(1)
            if (key != null && key != "NONE") throw IOException("This stream is encrypted and can't be recorded.")
            val target = Regex("""#EXT-X-TARGETDURATION:(\d+)""").find(playlist)?.groupValues?.get(1)?.toLongOrNull() ?: 6
            for (segment in playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                val absolute = resolve(mediaUrl, segment)
                if (!seen.add(absolute)) continue
                if (System.currentTimeMillis() >= endAt) return
                runCatching { copySegment(absolute, sink, counted) }
            }
            if ("#EXT-X-ENDLIST" in playlist) return
            delay((target * 1000 / 2).coerceIn(1_000, 10_000))
        }
    }

    private suspend fun copySegment(url: String, sink: OutputStream, counted: (Int) -> Unit) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val input = response.body?.byteStream() ?: return@use
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                counted(n)
            }
        }
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}.")
            response.body?.string() ?: ""
        }
    }

    companion object {
        /** The variant with the highest BANDWIDTH in a master playlist. */
        fun bestVariant(master: String): String? {
            val lines = master.lines().map { it.trim() }
            var best: String? = null
            var bestBandwidth = -1L
            for ((i, line) in lines.withIndex()) {
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                if (bandwidth > bestBandwidth) {
                    best = uri
                    bestBandwidth = bandwidth
                }
            }
            return best
        }

        fun resolve(base: String, ref: String): String =
            base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref
    }
}
