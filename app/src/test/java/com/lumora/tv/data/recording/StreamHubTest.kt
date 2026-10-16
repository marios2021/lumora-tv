package com.lumora.tv.data.recording

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StreamHubTest {

    /** An endless "live stream" that counts connections, like a provider counting an account's connections. */
    private class LiveServer : AutoCloseable {
        val socket = ServerSocket(0)
        val connections = AtomicInteger()
        val open = AtomicInteger()
        val url get() = "http://127.0.0.1:${socket.localPort}/live/1.ts"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) = client.use {
            open.incrementAndGet()
            try {
                val input = it.getInputStream().bufferedReader()
                val requestLine = input.readLine().orEmpty()
                while (input.readLine()?.isNotEmpty() == true) Unit
                val out = it.getOutputStream()
                if ("/missing" in requestLine) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return@use
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                val packet = ByteArray(188) { 0x47 }
                while (true) {
                    out.write(packet)
                    out.flush()
                    Thread.sleep(5)
                }
            } catch (_: IOException) {
            } finally {
                open.decrementAndGet()
            }
        }

        override fun close() = socket.close()
    }

    private class Counter : StreamHub.Reader {
        val bytes = AtomicLong()
        val end = AtomicReference<Any?>(null)
        override fun onData(bytes: ByteArray, length: Int) {
            this.bytes.addAndGet(length.toLong())
        }

        override fun onEnd(error: IOException?) {
            end.set(error ?: "ended")
        }
    }

    private val server = LiveServer()
    private val http = OkHttpClient()
    private val hub = StreamHub()

    @After
    fun tearDown() = server.close()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun readersOfTheSameStreamShareOneConnection() {
        val player = Counter()
        val recording = Counter()
        val a = hub.attach(server.url, http, player)
        waitFor("player data") { player.bytes.get() > 0 }
        val b = hub.attach(server.url, http, recording)
        waitFor("recording data") { recording.bytes.get() > 1_000 }
        assertEquals(1, server.connections.get())
        assertEquals(1, hub.openStreams)

        // The player leaves: the recording carries on over the same connection.
        a.close()
        val before = recording.bytes.get()
        waitFor("more recording data") { recording.bytes.get() > before + 1_000 }
        assertEquals(1, server.connections.get())

        // The last reader leaves: the connection closes.
        b.close()
        assertEquals(0, hub.openStreams)
        waitFor("server to see the disconnect") { server.open.get() == 0 }
    }

    @Test
    fun aReaderThatLeftGetsNoMoreData() {
        val reader = Counter()
        val a = hub.attach(server.url, http, reader)
        waitFor("data") { reader.bytes.get() > 0 }
        a.close()
        val after = reader.bytes.get()
        Thread.sleep(100)
        assertEquals(after, reader.bytes.get())
    }

    @Test
    fun errorsReachEveryReader() {
        val url = server.url.replace("/live/1.ts", "/missing")
        val one = Counter()
        val two = Counter()
        hub.attach(url, http, one)
        hub.attach(url, http, two)
        waitFor("errors") { one.end.get() != null && two.end.get() != null }
        assertEquals(404, (one.end.get() as HttpStatusException).code)
        assertEquals(404, (two.end.get() as HttpStatusException).code)
        assertEquals(0, hub.openStreams)
    }

    @Test
    fun recordingTheWatchedChannelOpensNoSecondConnection() = runBlocking {
        val player = Counter()
        val watching = hub.attach(server.url, http, player)
        waitFor("player data") { player.bytes.get() > 0 }

        val file = ByteArrayOutputStream()
        val saved = Recorder(http, hub).record(server.url, file, System.currentTimeMillis() + 300) { }
        assertTrue(saved > 0)
        assertEquals(saved, file.size().toLong())
        assertEquals(1, server.connections.get())

        // The recording left; the player still has its connection.
        assertEquals(1, hub.openStreams)
        watching.close()
    }
}
