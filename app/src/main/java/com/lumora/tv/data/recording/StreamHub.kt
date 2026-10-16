package com.lumora.tv.data.recording

import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The server answered a stream request with an error status. */
class HttpStatusException(val code: Int, val reason: String, val headers: Headers) :
    IOException("Server returned HTTP $code.")

/**
 * One connection per live stream, shared by everyone reading it: the player and any recordings of
 * the same channel. IPTV accounts often allow a single connection, and a second one gets the
 * provider's "connection limit reached" clip instead of the channel, which a recording would then
 * save. Readers attach to a stream URL; the first opens the connection, later ones join it from
 * the current point, and the last to leave closes it.
 */
class StreamHub {
    interface Reader {
        /** Called on the connection's thread. */
        fun onData(bytes: ByteArray, length: Int)

        /** The stream ended ([error] null) or failed. Not called for readers that already left. */
        fun onEnd(error: IOException?)
    }

    fun interface Attachment {
        /** Stops delivery to this reader: once it returns, the reader gets no more data. */
        fun close()
    }

    private val streams = HashMap<String, Stream>()

    /** Attaches [reader] to [url], connecting with [http] if nobody reads it yet. */
    fun attach(url: String, http: OkHttpClient, reader: Reader): Attachment {
        val stream = synchronized(this) {
            val s = streams[url] ?: Stream(url, http).also { streams[url] = it }
            synchronized(s) { s.readers += reader }
            s
        }
        stream.startOnce()
        return Attachment { detach(stream, reader) }
    }

    /** How many connections are open, for tests. */
    val openStreams: Int get() = synchronized(this) { streams.size }

    private fun detach(stream: Stream, reader: Reader) {
        synchronized(this) {
            synchronized(stream) {
                stream.readers -= reader
                if (stream.readers.isEmpty() && streams[stream.url] === stream) {
                    streams.remove(stream.url)
                    stream.cancel()
                }
            }
        }
    }

    private fun ended(stream: Stream, error: IOException?) {
        val readers = synchronized(this) {
            synchronized(stream) {
                if (streams[stream.url] === stream) streams.remove(stream.url)
                stream.readers.toList().also { stream.readers.clear() }
            }
        }
        for (r in readers) r.onEnd(error)
    }

    private inner class Stream(val url: String, http: OkHttpClient) : Thread("Lumora-stream") {
        val readers = ArrayList<Reader>(2)
        private val call: Call? = runCatching { http.newCall(Request.Builder().url(url).build()) }.getOrNull()
        private var started = false

        init {
            isDaemon = true
        }

        fun startOnce() = synchronized(this) {
            if (!started) {
                started = true
                start()
            }
        }

        fun cancel() {
            call?.cancel()
        }

        override fun run() {
            if (call == null) {
                ended(this, IOException("Not a valid stream address"))
                return
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code, response.message, response.headers)
                    val input = response.body?.byteStream() ?: throw IOException("Empty response")
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (n == 0) continue
                        synchronized(this) {
                            for (r in readers) r.onData(chunk, n)
                        }
                    }
                }
                ended(this, null)
            } catch (e: IOException) {
                // Cancelled because everyone left: there is nobody to tell.
                if (!call.isCanceled()) ended(this, e)
            }
        }
    }
}
