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
import java.util.concurrent.atomic.AtomicLong
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

    /**
     * How much audio may wait for each speaker before the oldest is dropped. Lower
     * means less delay but more risk of skips. Can be changed while streaming.
     */
    @Volatile var maxQueuedMillis: Int = DEFAULT_QUEUE_MS
        set(value) { field = value.coerceIn(MIN_QUEUE_MS, MAX_QUEUE_MS) }

    /** Chunks thrown away because a speaker fell further behind than [maxQueuedMillis]. */
    val droppedChunks = AtomicLong()
    /** 100 ms silence blocks sent while nothing was playing. */
    val silenceChunks = AtomicLong()
    /** Stream connections opened by speakers. More than one per session means reconnects. */
    val connections = AtomicLong()

    // The most recent audio, replayed to a speaker the moment it connects so it can
    // fill its own buffer at once instead of in real time (which causes early skips).
    private val lock = Any()
    private val history = ArrayDeque<ByteArray>()
    private var historyBytes = 0

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
        synchronized(lock) {
            history.clear()
            historyBytes = 0
        }
    }

    fun publish(pcm: ByteArray, length: Int = pcm.size) {
        if (length <= 0) return
        val chunk = pcm.copyOf(length)
        val limit = queueLimit(length, maxQueuedMillis, bytesPerSecond)
        synchronized(lock) {
            history.addLast(chunk)
            historyBytes += length
            val keepBytes = prerollMillis().toLong() * bytesPerSecond / 1000
            while (history.size > 1 && historyBytes - history.first().size >= keepBytes) {
                historyBytes -= history.removeFirst().size
            }
            for (q in clients) {
                while (q.size >= limit || !q.offer(chunk)) {
                    if (q.poll() != null) droppedChunks.incrementAndGet()
                }
            }
        }
    }

    /** Replay at most this much on connect, and never more than the queue may hold. */
    private fun prerollMillis(): Int = minOf(PREROLL_MS, maxQueuedMillis)

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
        // Speakers read in bursts, so a short queue overflows between bursts and audio
        // is thrown away, heard as stutter. The real limit is [maxQueuedMillis],
        // enforced in publish(); the capacity is just a hard ceiling.
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        synchronized(lock) {
            // Atomic with publish(): no chunk is missed or sent twice.
            history.forEach { queue.offer(it) }
            clients += queue
        }
        connections.incrementAndGet()
        val silence = ByteArray((bytesPerSecond / 10) and 0x7FFFFFFC) // 100 ms
        var idleMillis = 0L
        try {
            while (running) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    idleMillis = 0
                    out.write(chunk)
                } else {
                    // Capture jitter of a few hundred ms is normal; padding it with
                    // silence would cut holes in the music. Only once capture has been
                    // quiet for a while (nothing playing), send silence in real time so
                    // the speaker doesn't time out.
                    idleMillis += 100
                    if (idleMillis < IDLE_BEFORE_SILENCE_MS) continue
                    silenceChunks.incrementAndGet()
                    out.write(silence)
                }
                if (queue.isEmpty()) out.flush()
            }
        } finally {
            clients.remove(queue)
        }
    }

    companion object {
        const val STREAM_PATH = "/stream.wav"
        const val PREROLL_MS = 1500
        const val MIN_QUEUE_MS = 200
        const val MAX_QUEUE_MS = 3000
        const val DEFAULT_QUEUE_MS = 2000
        private const val QUEUE_CAPACITY = 1024
        private const val SEND_BUFFER_BYTES = 64 * 1024 // ~0.4 s at CD quality
        private const val IDLE_BEFORE_SILENCE_MS = 500L

        /** How many chunks of [chunkBytes] fit in [maxMillis] of audio (at least 1). */
        fun queueLimit(chunkBytes: Int, maxMillis: Int, bytesPerSecond: Int): Int {
            val chunkMillis = (chunkBytes * 1000L / bytesPerSecond).coerceAtLeast(1)
            return (maxMillis / chunkMillis).toInt().coerceIn(1, QUEUE_CAPACITY)
        }

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
