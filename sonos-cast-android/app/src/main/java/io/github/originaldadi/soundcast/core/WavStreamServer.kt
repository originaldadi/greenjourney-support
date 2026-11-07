package io.github.originaldadi.soundcast.core

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A tiny HTTP server that serves live 16-bit PCM audio as an endless WAV file at
 * [STREAM_PATH]. The speaker connects to it and pulls the audio.
 *
 * Call [publish] with raw little-endian PCM as it is captured. Every connected
 * client gets its own small queue; if a client falls behind, its oldest audio is
 * dropped so the delay never grows without bound.
 */
class WavStreamServer(
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    private val requestedPort: Int = 0,
) {
    private val bytesPerSecond = sampleRate * channels * 2
    private val clients = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: -1
    val clientCount: Int get() = clients.size

    fun start(): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(requestedPort))
        } catch (e: IOException) {
            if (requestedPort == 0) throw e
            ss.bind(InetSocketAddress(0)) // preferred port busy: take any free one
        }
        server = ss
        running = true
        thread(name = "wav-accept", isDaemon = true) {
            while (running) {
                val socket = try { ss.accept() } catch (_: IOException) { break }
                sockets += socket
                thread(name = "wav-client", isDaemon = true) { serve(socket) }
            }
        }
        return ss.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        clients.clear()
    }

    fun publish(pcm: ByteArray, length: Int = pcm.size) {
        if (clients.isEmpty() || length <= 0) return
        val chunk = pcm.copyOf(length)
        for (q in clients) {
            while (!q.offer(chunk)) q.poll()
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            // Every byte waiting in the phone's TCP send buffer is extra delay: at
            // 176 KB/s an auto-sized buffer can hold several seconds. Keep it small.
            socket.sendBufferSize = SEND_BUFFER_BYTES
            socket.soTimeout = 10_000
            val request = readRequestHead(socket.getInputStream()) ?: return
            socket.soTimeout = 0
            val requestLine = request.lineSequence().firstOrNull().orEmpty().split(' ')
            val method = requestLine.getOrNull(0).orEmpty()
            val path = requestLine.getOrNull(1).orEmpty().substringBefore('?')
            val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)

            if (path != STREAM_PATH || (method != "GET" && method != "HEAD")) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            out.write(responseHeaders().toByteArray(Charsets.US_ASCII))
            if (method == "HEAD") {
                out.flush()
                return
            }
            out.write(wavHeader(sampleRate, channels))
            out.flush()
            streamTo(out)
        } catch (_: IOException) {
            // Client went away.
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun streamTo(out: OutputStream) {
        // At most ~300 ms of audio waits here (50 ms chunks). The speaker keeps its
        // own multi-second buffer to ride out WiFi hiccups, so a deep queue here only
        // adds delay.
        val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_CHUNKS)
        clients += queue
        // When capture goes quiet, send silence in real time (100 ms per 100 ms of
        // waiting) so the speaker neither times out nor runs dry and re-buffers.
        val silence = ByteArray((bytesPerSecond / 10) and 0x7FFFFFFC)
        try {
            while (running) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: silence
                out.write(chunk)
                if (queue.isEmpty()) out.flush()
            }
        } finally {
            clients.remove(queue)
        }
    }

    companion object {
        const val STREAM_PATH = "/stream.wav"
        private const val MAX_QUEUED_CHUNKS = 6
        private const val SEND_BUFFER_BYTES = 32 * 1024 // ~0.2 s at CD quality

        fun responseHeaders(): String =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/wav\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Accept-Ranges: none\r\n" +
                "Connection: close\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                "\r\n"

        /**
         * A 44-byte WAV header for a stream of unknown length. The sizes are set
         * to the largest values the format allows (about 6.7 hours at CD quality).
         */
        fun wavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int = 16): ByteArray {
            val blockAlign = channels * bitsPerSample / 8
            val byteRate = sampleRate * blockAlign
            val dataSize = (0xFFFFFFFFL - 36L) / blockAlign * blockAlign
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((dataSize + 36L).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize.toInt())
            }.array()
        }

        /** Reads up to the blank line that ends the request headers. */
        private fun readRequestHead(input: InputStream): String? {
            val sb = StringBuilder()
            while (sb.length < 8192) {
                val b = input.read()
                if (b < 0) return null
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) return sb.toString()
            }
            return null
        }
    }
}
