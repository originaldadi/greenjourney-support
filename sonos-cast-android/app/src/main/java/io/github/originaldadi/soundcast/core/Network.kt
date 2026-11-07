package io.github.originaldadi.soundcast.core

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

object Network {
    /**
     * The phone's own address on the network that leads to [remoteHost].
     * "Connecting" a UDP socket sends nothing; it just makes the OS pick the
     * right interface, which is more reliable than guessing between WiFi,
     * mobile data and VPN interfaces.
     */
    fun localAddressFacing(remoteHost: String, remotePort: Int = 1400): String {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(remoteHost), remotePort))
            val addr = socket.localAddress
            require(!addr.isAnyLocalAddress) { "No network route to $remoteHost" }
            return addr.hostAddress ?: error("No local address")
        }
    }
}
