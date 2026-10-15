package com.lumora.tv.data.recording

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Saves a live stream to a file until a given time. Plain HTTP streams (MPEG-TS) are copied byte
 * for byte and reconnected if they drop; HLS is recorded by polling the media playlist and
 * appending each new TS segment. Encrypted or fMP4 HLS can't be recorded this way.
 */
class Recorder(private val http: OkHttpClient) {

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

    /** Copies one connection's worth of data; returns when it ends or [endAt] passes. */
    private suspend fun copyUntil(url: String, sink: OutputStream, endAt: Long, counted: (Int) -> Unit) = coroutineScope {
        val call = http.newCall(Request.Builder().url(url).build())
        // Cancelling the call unblocks a read: at the end time, or when the recording is stopped.
        val watcher = launch {
            try {
                delay((endAt - System.currentTimeMillis()).coerceAtLeast(0))
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}.")
                    val input = response.body?.byteStream() ?: throw IOException("Empty response.")
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        counted(n)
                    }
                }
            }
        } catch (e: IOException) {
            if (System.currentTimeMillis() < endAt) throw e
        } finally {
            watcher.cancel()
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
