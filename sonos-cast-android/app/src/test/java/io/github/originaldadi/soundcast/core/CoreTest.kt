package io.github.originaldadi.soundcast.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.concurrent.thread

class CoreTest {

    // ------------------------------------------------------------ discovery

    private val sonosReply = "HTTP/1.1 200 OK\r\n" +
        "CACHE-CONTROL: max-age = 1800\r\n" +
        "EXT:\r\n" +
        "LOCATION: http://192.168.1.20:1400/xml/device_description.xml\r\n" +
        "SERVER: Linux UPnP/1.0 Sonos/80.1-55014 (ZPS27)\r\n" +
        "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
        "USN: uuid:RINCON_000E58A0B1C201400::urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
        "X-RINCON-HOUSEHOLD: Sonos_abc\r\n\r\n"

    @Test fun parsesSonosLocation() {
        assertEquals("http://192.168.1.20:1400/xml/device_description.xml", SsdpDiscovery.parseLocation(sonosReply))
        assertEquals("192.168.1.20", SsdpDiscovery.hostOf("http://192.168.1.20:1400/xml/device_description.xml"))
    }

    @Test fun ignoresOtherDevices() {
        val tv = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.5:8080/desc.xml\r\nST: upnp:rootdevice\r\nSERVER: SomeTV/1.0\r\n\r\n"
        assertNull(SsdpDiscovery.parseLocation(tv))
    }

    @Test fun discoverCollectsReplies() {
        val responder = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val received = Collections.synchronizedList(mutableListOf<String>())
        thread(isDaemon = true) {
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            responder.receive(p)
            received += String(p.data, 0, p.length)
            val reply = sonosReply.toByteArray()
            responder.send(DatagramPacket(reply, reply.size, p.socketAddress))
        }
        val found = SsdpDiscovery.discover(1000, InetAddress.getLoopbackAddress(), responder.localPort)
        responder.close()
        assertEquals(setOf("http://192.168.1.20:1400/xml/device_description.xml"), found)
        assertTrue(received.first().startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(received.first().contains("ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n"))
    }

    // -------------------------------------------------------------- groups

    @Test fun parsesGroupsHidingBondedSpeakers() {
        val xml = """
            <ZoneGroupState><ZoneGroups>
              <ZoneGroup Coordinator="RINCON_A" ID="RINCON_A:1">
                <ZoneGroupMember UUID="RINCON_A" Location="http://10.0.0.11:1400/xml/device_description.xml" ZoneName="Living Room"/>
                <ZoneGroupMember UUID="RINCON_SUB" Location="http://10.0.0.12:1400/xml/device_description.xml" ZoneName="Living Room" Invisible="1"/>
                <ZoneGroupMember UUID="RINCON_B" Location="http://10.0.0.13:1400/xml/device_description.xml" ZoneName="Kitchen"/>
              </ZoneGroup>
              <ZoneGroup Coordinator="RINCON_C" ID="RINCON_C:2">
                <ZoneGroupMember UUID="RINCON_C" Location="http://10.0.0.14:1400/xml/device_description.xml" ZoneName="Bedroom">
                  <Satellite UUID="RINCON_D" Location="http://10.0.0.15:1400/xml/device_description.xml" ZoneName="Bedroom" Invisible="1"/>
                </ZoneGroupMember>
              </ZoneGroup>
              <ZoneGroup Coordinator="RINCON_E" ID="RINCON_E:3">
                <ZoneGroupMember UUID="RINCON_E" Location="http://10.0.0.16:1400/xml/device_description.xml" ZoneName="Sub" Invisible="1"/>
              </ZoneGroup>
            </ZoneGroups><VanishedDevices/></ZoneGroupState>
        """.trimIndent()
        val groups = SonosClient.parseZoneGroupState(xml)
        assertEquals(
            listOf(
                SonosGroup("Bedroom", "10.0.0.14", "RINCON_C", 1),
                SonosGroup("Living Room + Kitchen", "10.0.0.11", "RINCON_A", 2),
            ),
            groups,
        )
    }

    // ---------------------------------------------------------------- SOAP

    /** Minimal fake Sonos control endpoint (plain sockets: Android unit tests lack com.sun.net.httpserver). */
    private class FakeSpeaker : AutoCloseable {
        val calls = Collections.synchronizedList(mutableListOf<Triple<String, String, String>>()) // path, action, body
        @Volatile var volume = 37
        private val server = java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val s = try { server.accept() } catch (_: java.io.IOException) { break }
                    thread(isDaemon = true) { s.use { handle(it) } }
                }
            }
        }

        private fun handle(socket: Socket) {
            val input = DataInputStream(socket.getInputStream())
            val headLines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val c = input.readUnsignedByte().toChar()
                if (c == '\n') {
                    val l = line.toString().trimEnd('\r')
                    line.clear()
                    if (l.isEmpty()) break
                    headLines += l
                } else {
                    line.append(c)
                }
            }
            val path = headLines.first().split(' ')[1]
            val headers = headLines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { input.readFully(it) }.toString(Charsets.UTF_8)
            val action = headers["soapaction"].orEmpty()
            calls += Triple(path, action, body)
            val name = action.trim('"').substringAfter('#')
            val (code, resp) = when (name) {
                "GetGroupVolume" -> 200 to envelope("<u:GetGroupVolumeResponse xmlns:u=\"x\"><CurrentVolume>$volume</CurrentVolume></u:GetGroupVolumeResponse>")
                "GetZoneGroupState" -> 200 to envelope(
                    "<u:GetZoneGroupStateResponse xmlns:u=\"x\"><ZoneGroupState>" +
                        SonosClient.escapeXml(
                            "<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator=\"R1\"><ZoneGroupMember UUID=\"R1\" " +
                                "Location=\"http://10.1.1.1:1400/xml/device_description.xml\" ZoneName=\"Office &amp; Den\"/>" +
                                "</ZoneGroup></ZoneGroups></ZoneGroupState>",
                        ) +
                        "</ZoneGroupState></u:GetZoneGroupStateResponse>",
                )
                "Play" -> if (volume < 0) 500 to envelope("<s:Fault><detail><UPnPError><errorCode>701</errorCode></UPnPError></detail></s:Fault>") else 200 to envelope("")
                else -> 200 to envelope("")
            }
            val bytes = resp.toByteArray()
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $code X\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }

        private fun envelope(inner: String) =
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>$inner</s:Body></s:Envelope>"

        override fun close() = server.close()
    }

    @Test fun playStreamSetsUriThenPlays() = FakeSpeaker().use { fake ->
        val client = SonosClient(port = fake.port)
        client.playStream("127.0.0.1", "http://10.0.0.2:8090/stream.wav", "Music from Pixel & Co")
        assertEquals(2, fake.calls.size)
        val (path, action, body) = fake.calls[0]
        assertEquals("/MediaRenderer/AVTransport/Control", path)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", action)
        assertTrue(body.contains("<CurrentURI>http://10.0.0.2:8090/stream.wav</CurrentURI>"))
        // Metadata is XML escaped inside the SOAP body, and the title escaped once more inside it.
        assertTrue(body.contains("&lt;dc:title&gt;Music from Pixel &amp;amp; Co&lt;/dc:title&gt;"))
        assertTrue(body.contains("protocolInfo=&quot;http-get:*:audio/wav:*&quot;"))
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", fake.calls[1].second)
        assertTrue(fake.calls[1].third.contains("<Speed>1</Speed>"))
    }

    @Test fun readsAndSetsGroupVolume() = FakeSpeaker().use { fake ->
        val client = SonosClient(port = fake.port)
        assertEquals(37, client.groupVolume("127.0.0.1"))
        client.setGroupVolume("127.0.0.1", 150)
        val actions = fake.calls.map { it.second.trim('"').substringAfter('#') }
        assertEquals(listOf("GetGroupVolume", "SnapshotGroupVolume", "SetGroupVolume"), actions)
        assertTrue(fake.calls.last().third.contains("<DesiredVolume>100</DesiredVolume>"))
        assertEquals("/MediaRenderer/GroupRenderingControl/Control", fake.calls.last().first)
    }

    @Test fun fetchesGroupsFromSpeaker() = FakeSpeaker().use { fake ->
        val groups = SonosClient(port = fake.port).groups("127.0.0.1")
        assertEquals(listOf(SonosGroup("Office & Den", "10.1.1.1", "R1", 1)), groups)
        assertEquals("/ZoneGroupTopology/Control", fake.calls.single().first)
    }

    @Test fun reportsUpnpErrors() = FakeSpeaker().use { fake ->
        fake.volume = -1
        try {
            SonosClient(port = fake.port).playStream("127.0.0.1", "http://x/stream.wav", "t")
            fail("expected error")
        } catch (e: SonosException) {
            assertTrue(e.message!!, e.message!!.contains("Play failed (HTTP 500, UPnP error 701)"))
        }
    }

    @Test fun unreachableSpeakerGivesClearError() {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        try {
            SonosClient(port = closedPort, timeoutMillis = 1000).stop("127.0.0.1")
            fail("expected error")
        } catch (e: SonosException) {
            assertTrue(e.message!!.startsWith("Could not reach speaker at 127.0.0.1"))
        }
    }

    // ------------------------------------------------------------- streaming

    @Test fun wavHeaderIsValid() {
        val h = ByteBuffer.wrap(AudioStreamServer.wavHeader(44_100, 2)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.capacity())
        assertEquals("RIFF", String(ByteArray(4).also { h.get(0, it) }))
        assertEquals("WAVE", String(ByteArray(4).also { h.get(8, it) }))
        assertEquals(1, h.getShort(20).toInt())
        assertEquals(2, h.getShort(22).toInt())
        assertEquals(44_100, h.getInt(24))
        assertEquals(176_400, h.getInt(28))
        assertEquals(4, h.getShort(32).toInt())
        assertEquals(16, h.getShort(34).toInt())
        val data = h.getInt(40).toLong() and 0xFFFFFFFFL
        val riff = h.getInt(4).toLong() and 0xFFFFFFFFL
        assertEquals(0L, data % 4)
        assertEquals(data + 36, riff)
        assertTrue(riff <= 0xFFFFFFFFL)
    }

    @Test fun streamsPublishedAudio() {
        val server = AudioStreamServer()
        val port = server.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("GET /stream.wav HTTP/1.1\r\nHost: x\r\nRange: bytes=0-\r\n\r\n".toByteArray())
                val input = DataInputStream(s.getInputStream())
                val head = readHead(input)
                assertTrue(head, head.startsWith("HTTP/1.1 200 OK\r\n"))
                assertTrue(head.contains("Content-Type: audio/wav\r\n"))
                val wav = ByteArray(44).also { input.readFully(it) }
                assertArrayEquals(AudioStreamServer.wavHeader(44_100, 2), wav)

                // Wait for the client to register, then publish a recognisable chunk.
                val deadline = System.currentTimeMillis() + 2000
                while (server.clientCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(1, server.clientCount)
                val chunk = ByteArray(1000) { (it % 251 + 1).toByte() }
                server.publish(chunk)

                // Skip any silence the server wrote while we were waiting, then expect our bytes.
                var b: Int
                do { b = input.readUnsignedByte() } while (b == 0)
                val got = ByteArray(1000)
                got[0] = b.toByte()
                input.readFully(got, 1, 999)
                assertArrayEquals(chunk, got)
            }
        } finally {
            server.stop()
        }
    }

    @Test fun queueLimitFollowsBufferSetting() {
        val bps = 44_100 * 4
        val chunk50ms = bps / 20
        assertEquals(40, AudioStreamServer.queueLimit(chunk50ms, 2000, bps))
        assertEquals(4, AudioStreamServer.queueLimit(chunk50ms, 200, bps))
        assertEquals(1, AudioStreamServer.queueLimit(bps * 5, 200, bps)) // huge chunk: still keep one
        val server = AudioStreamServer()
        server.maxQueuedMillis = 10
        assertEquals(AudioStreamServer.MIN_QUEUE_MS, server.maxQueuedMillis)
        server.maxQueuedMillis = 99_000
        assertEquals(AudioStreamServer.MAX_QUEUE_MS, server.maxQueuedMillis)
    }

    @Test fun slowSpeakerOnlyLosesAudioBeyondBuffer() {
        val server = AudioStreamServer()
        server.maxQueuedMillis = 200 // 4 x 50 ms chunks
        val port = server.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.receiveBufferSize = 4096
                s.soTimeout = 3000
                s.getOutputStream().write("GET /stream.wav HTTP/1.1\r\n\r\n".toByteArray())
                val deadline = System.currentTimeMillis() + 2000
                while (server.clientCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                // Speaker stops reading; publish 20 s of audio, far more than the socket buffers hold.
                val chunk = ByteArray(8820) { 1 }
                repeat(400) { server.publish(chunk) }
                assertTrue("expected drops once the 200 ms buffer filled", server.droppedChunks.get() > 300)
            }
        } finally {
            server.stop()
        }
    }

    @Test fun newSpeakerGetsRecentAudioAtOnce() {
        val server = AudioStreamServer()
        server.maxQueuedMillis = 1500
        val port = server.start()
        try {
            // 2 s of 50 ms chunks captured before the speaker connects; chunk i is filled with i+1.
            repeat(40) { i -> server.publish(ByteArray(8820) { (i + 1).toByte() }) }
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("GET /stream.wav HTTP/1.1\r\n\r\n".toByteArray())
                val input = DataInputStream(s.getInputStream())
                readHead(input)
                input.readFully(ByteArray(44))
                // Only the last 1.5 s (30 chunks: 11..40) is replayed, immediately and in order.
                val started = System.currentTimeMillis()
                for (i in 10 until 40) {
                    val got = ByteArray(8820).also { input.readFully(it) }
                    assertTrue("chunk $i", got.all { it == (i + 1).toByte() })
                }
                assertTrue("replay should not wait for real time", System.currentTimeMillis() - started < 1000)
                assertEquals(1L, server.connections.get())
                // Live audio follows the replay without a gap or repeat.
                server.publish(ByteArray(8820) { 99 })
                assertTrue(ByteArray(8820).also { input.readFully(it) }.all { it == 99.toByte() })
            }
        } finally {
            server.stop()
        }
    }

    @Test fun bufferedMillisTracksReserve() {
        val server = AudioStreamServer()
        assertEquals(5000, server.maxQueuedMillis) // default favours no skips
        repeat(20) { server.publish(ByteArray(8820)) } // 1 s
        assertEquals(1000, server.bufferedMillis())
        repeat(200) { server.publish(ByteArray(8820)) } // 10 s more; only 5 s kept
        assertEquals(5000, server.bufferedMillis())
    }

    @Test fun adtsHeaderMatchesSpec() {
        val frame = ByteArray(7 + 371)
        AudioStreamServer.writeAdtsHeader(frame, frame.size, AudioStreamServer.adtsFrequencyIndex(48_000), 2)
        // Reference: FF F1 4C 80 2F 5F FC for AAC-LC, 48 kHz, stereo, 378-byte frame.
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x80.toByte(), 0x2F, 0x5F, 0xFC.toByte()),
            frame.copyOf(7),
        )
        assertEquals(4, AudioStreamServer.adtsFrequencyIndex(44_100))
    }

    @Test fun linkTypesBuildSpeakerUris() {
        val url = "http://10.0.0.2:8090/stream.aac"
        assertEquals(url, LinkType.STANDARD.speakerUri(url))
        assertEquals("x-rincon-mp3radio://10.0.0.2:8090/stream.aac", LinkType.RADIO.speakerUri(url))
        assertEquals("aac://http://10.0.0.2:8090/stream.aac", LinkType.AAC_RADIO.speakerUri(url))
    }

    @Test fun radioPlaybackSendsBroadcastMetadata() = FakeSpeaker().use { fake ->
        SonosClient(port = fake.port).playStream(
            "127.0.0.1", "http://10.0.0.2:8090/stream.aac", "t", mimeType = "audio/aac", linkType = LinkType.RADIO,
        )
        val body = fake.calls[0].third
        assertTrue(body.contains("<CurrentURI>x-rincon-mp3radio://10.0.0.2:8090/stream.aac</CurrentURI>"))
        assertTrue(body.contains("object.item.audioItem.audioBroadcast"))
        assertTrue(body.contains("http-get:*:audio/aac:*"))
        assertTrue(body.contains("SA_RINCON65031_"))
    }

    @Test fun servesAacWithoutHeaderOrPadding() {
        val server = AudioStreamServer(StreamFormat.aac(256_000))
        val port = server.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("GET /stream.aac HTTP/1.1\r\n\r\n".toByteArray())
                val input = DataInputStream(s.getInputStream())
                val head = readHead(input)
                assertTrue(head.contains("Content-Type: audio/aac\r\n"))
                Thread.sleep(800) // idle: AAC must not be padded with raw zeros
                assertEquals(0L, server.silenceChunks.get())
                val deadline = System.currentTimeMillis() + 2000
                while (server.clientCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                val frame = ByteArray(200) { 5 }
                server.publish(frame)
                assertArrayEquals(frame, ByteArray(200).also { input.readFully(it) }) // no WAV header first
            }
        } finally {
            server.stop()
        }
    }

    @Test fun captureJitterDoesNotInsertSilence() {
        val server = AudioStreamServer()
        val port = server.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("GET /stream.wav HTTP/1.1\r\n\r\n".toByteArray())
                val input = DataInputStream(s.getInputStream())
                readHead(input)
                input.readFully(ByteArray(44))
                val deadline = System.currentTimeMillis() + 2000
                while (server.clientCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                val a = ByteArray(800) { 7 }
                val b = ByteArray(800) { 9 }
                server.publish(a)
                Thread.sleep(300) // a capture hiccup shorter than the idle threshold
                server.publish(b)
                val got = ByteArray(1600).also { input.readFully(it) }
                assertArrayEquals(a + b, got)
                assertEquals(0L, server.silenceChunks.get())
                assertEquals(0L, server.droppedChunks.get())
            }
        } finally {
            server.stop()
        }
    }

    @Test fun keepsConnectionAliveWithSilence() {
        val server = AudioStreamServer()
        val port = server.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("GET /stream.wav HTTP/1.0\r\n\r\n".toByteArray())
                val input = DataInputStream(s.getInputStream())
                readHead(input)
                input.readFully(ByteArray(44))
                val silence = ByteArray(17_640).also { input.readFully(it) } // 100 ms
                assertTrue(silence.all { it == 0.toByte() })
            }
        } finally {
            server.stop()
        }
    }

    @Test fun headAndUnknownPaths() {
        val server = AudioStreamServer()
        val port = server.start()
        try {
            fun request(req: String): String = Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write(req.toByteArray())
                s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            }
            val head = request("HEAD /stream.wav HTTP/1.1\r\n\r\n")
            assertTrue(head.startsWith("HTTP/1.1 200 OK"))
            assertTrue(head.endsWith("\r\n\r\n")) // headers only, no body
            assertTrue(request("GET /other HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
        } finally {
            server.stop()
        }
    }

    @Test fun localAddressFacingLoopback() {
        assertEquals("127.0.0.1", Network.localAddressFacing("127.0.0.1"))
    }

    private fun readHead(input: DataInputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) sb.append(input.readUnsignedByte().toChar())
        return sb.toString()
    }
}
