package io.github.originaldadi.soundcast.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Finds Sonos speakers on the local network with an SSDP M-SEARCH.
 *
 * On Android the caller must hold a WifiManager.MulticastLock while this runs,
 * otherwise many phones drop the multicast replies.
 */
object SsdpDiscovery {
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    const val SONOS_SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

    fun searchMessage(searchTarget: String = SONOS_SEARCH_TARGET): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: $searchTarget\r\n" +
            "\r\n"

    /** Returns the device-description URLs of every speaker that answered. */
    fun discover(
        timeoutMillis: Int = 3000,
        target: InetAddress = InetAddress.getByName(SSDP_ADDRESS),
        port: Int = SSDP_PORT,
    ): Set<String> {
        val locations = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            val payload = searchMessage().toByteArray(Charsets.US_ASCII)
            // UDP is lossy; send a few times.
            repeat(3) { socket.send(DatagramPacket(payload, payload.size, target, port)) }

            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(2048)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                socket.soTimeout = remaining.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parseLocation(text)?.let { locations += it }
            }
        }
        return locations
    }

    /** Pulls the LOCATION header out of an SSDP reply, if it is a Sonos speaker. */
    fun parseLocation(response: String): String? {
        val headers = response.split("\r\n", "\n")
            .drop(1)
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }
            .toMap()
        val location = headers["location"] ?: return null
        val st = headers["st"].orEmpty()
        val server = headers["server"].orEmpty()
        val isSonos = st.contains("ZonePlayer", ignoreCase = true) ||
            server.contains("Sonos", ignoreCase = true) ||
            headers.containsKey("x-rincon-household")
        return if (isSonos) location else null
    }

    fun hostOf(location: String): String? = runCatching { URI(location).host }.getOrNull()
}
