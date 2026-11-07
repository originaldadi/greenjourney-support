package io.github.originaldadi.soundcast.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** One speaker, or several grouped speakers, as the Sonos app shows them. */
data class SonosGroup(
    /** Display name, e.g. "Kitchen + Living Room". */
    val name: String,
    /** IP of the group coordinator: playback commands must go to this speaker. */
    val coordinatorHost: String,
    val coordinatorUuid: String,
    val memberCount: Int,
)

class SonosException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * How the stream address is handed to the speaker. Sonos treats each differently
 * (buffering, reconnects), and which works best varies, so it's a user setting.
 */
enum class LinkType {
    /** Plain http:// address, played like a track. */
    STANDARD,
    /** x-rincon-mp3radio:// address, played like an internet radio station. */
    RADIO,
    /** aac://http:// address, for AAC radio streams. */
    AAC_RADIO;

    fun speakerUri(httpUrl: String): String = when (this) {
        STANDARD -> httpUrl
        RADIO -> "x-rincon-mp3radio://" + httpUrl.removePrefix("http://")
        AAC_RADIO -> "aac://$httpUrl"
    }

    val isRadio: Boolean get() = this != STANDARD
}

/**
 * Talks to Sonos speakers over their local UPnP control interface (port 1400).
 */
class SonosClient(
    private val port: Int = 1400,
    private val timeoutMillis: Int = 5000,
) {
    // ---------------------------------------------------------------- groups

    /**
     * Asks one speaker for the household's group layout and returns every group.
     * Bonded speakers (subs, surrounds, stereo-pair partners) are hidden, just like
     * in the Sonos app.
     */
    fun groups(anyHost: String): List<SonosGroup> {
        val body = soap(
            anyHost,
            "/ZoneGroupTopology/Control",
            ZONE_GROUP_TOPOLOGY,
            "GetZoneGroupState",
            emptyList(),
        )
        val state = textOf(parse(body), "ZoneGroupState")
            ?: throw SonosException("Speaker did not return its group layout")
        return parseZoneGroupState(state)
    }

    /** Fallback when the topology call fails: treat each speaker as its own group. */
    fun standaloneGroup(location: String): SonosGroup {
        val doc = parse(httpGet(location))
        val host = SsdpDiscovery.hostOf(location) ?: throw SonosException("Bad location $location")
        val name = textOf(doc, "roomName") ?: textOf(doc, "friendlyName") ?: host
        val udn = textOf(doc, "UDN")?.removePrefix("uuid:") ?: host
        return SonosGroup(name, host, udn, 1)
    }

    // -------------------------------------------------------------- playback

    fun playStream(
        coordinatorHost: String,
        streamUrl: String,
        title: String,
        mimeType: String = "audio/wav",
        linkType: LinkType = LinkType.STANDARD,
    ) {
        val uri = linkType.speakerUri(streamUrl)
        soap(
            coordinatorHost,
            AV_TRANSPORT_PATH,
            AV_TRANSPORT,
            "SetAVTransportURI",
            listOf(
                "InstanceID" to "0",
                "CurrentURI" to uri,
                "CurrentURIMetaData" to didl(uri, title, mimeType, linkType.isRadio),
            ),
        )
        soap(coordinatorHost, AV_TRANSPORT_PATH, AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
    }

    fun stop(coordinatorHost: String) {
        soap(coordinatorHost, AV_TRANSPORT_PATH, AV_TRANSPORT, "Stop", listOf("InstanceID" to "0"))
    }

    // ---------------------------------------------------------------- volume

    /** Volume of the whole group, 0–100. */
    fun groupVolume(coordinatorHost: String): Int {
        val body = soap(
            coordinatorHost,
            GROUP_RENDERING_PATH,
            GROUP_RENDERING,
            "GetGroupVolume",
            listOf("InstanceID" to "0"),
        )
        return textOf(parse(body), "CurrentVolume")?.toIntOrNull()
            ?: throw SonosException("Speaker did not return a volume")
    }

    fun setGroupVolume(coordinatorHost: String, volume: Int) {
        // The snapshot keeps each speaker's relative level when the group volume moves.
        soap(coordinatorHost, GROUP_RENDERING_PATH, GROUP_RENDERING, "SnapshotGroupVolume", listOf("InstanceID" to "0"))
        soap(
            coordinatorHost,
            GROUP_RENDERING_PATH,
            GROUP_RENDERING,
            "SetGroupVolume",
            listOf("InstanceID" to "0", "DesiredVolume" to volume.coerceIn(0, 100).toString()),
        )
    }

    // ------------------------------------------------------------- transport

    fun soap(host: String, path: String, service: String, action: String, args: List<Pair<String, String>>): String {
        val envelope = soapEnvelope(service, action, args)
        val url = URL("http://$host:$port$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMillis
            conn.readTimeout = timeoutMillis
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val upnpCode = runCatching { textOf(parse(text), "errorCode") }.getOrNull()
                throw SonosException("$action failed (HTTP $code${upnpCode?.let { ", UPnP error $it" } ?: ""})")
            }
            return text
        } catch (e: SonosException) {
            throw e
        } catch (e: IOException) {
            throw SonosException("Could not reach speaker at $host: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMillis
            conn.readTimeout = timeoutMillis
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val GROUP_RENDERING = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
        const val ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
        const val AV_TRANSPORT_PATH = "/MediaRenderer/AVTransport/Control"
        const val GROUP_RENDERING_PATH = "/MediaRenderer/GroupRenderingControl/Control"

        fun soapEnvelope(service: String, action: String, args: List<Pair<String, String>>): String {
            val argXml = args.joinToString("") { (k, v) -> "<$k>${escapeXml(v)}</$k>" }
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"$service\">$argXml</u:$action></s:Body></s:Envelope>"
        }

        /** DIDL-Lite metadata describing the stream to the speaker. */
        fun didl(streamUrl: String, title: String, mimeType: String = "audio/wav", radio: Boolean = false): String =
            "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" " +
                "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"true\">" +
                "<dc:title>${escapeXml(title)}</dc:title>" +
                "<upnp:class>${if (radio) "object.item.audioItem.audioBroadcast" else "object.item.audioItem.musicTrack"}</upnp:class>" +
                (if (radio) "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">SA_RINCON65031_</desc>" else "") +
                "<res protocolInfo=\"http-get:*:${escapeXml(mimeType)}:*\">${escapeXml(streamUrl)}</res>" +
                "</item></DIDL-Lite>"

        fun escapeXml(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }

        /** Parses the (already unescaped) ZoneGroupState XML. */
        fun parseZoneGroupState(xml: String): List<SonosGroup> {
            val doc = parse(xml)
            val groups = mutableListOf<SonosGroup>()
            val groupNodes = doc.getElementsByTagName("ZoneGroup")
            for (i in 0 until groupNodes.length) {
                val group = groupNodes.item(i) as Element
                val coordinatorUuid = group.getAttribute("Coordinator")
                val members = group.getElementsByTagName("ZoneGroupMember")
                var coordinatorHost: String? = null
                val visibleNames = linkedSetOf<String>()
                for (j in 0 until members.length) {
                    val m = members.item(j) as Element
                    if (m.getAttribute("UUID") == coordinatorUuid) {
                        coordinatorHost = runCatching { URI(m.getAttribute("Location")).host }.getOrNull()
                    }
                    if (m.getAttribute("Invisible") != "1") {
                        m.getAttribute("ZoneName").takeIf { it.isNotBlank() }?.let { visibleNames += it }
                    }
                }
                // Groups whose coordinator is invisible (e.g. a lone Sub) can't be played to.
                if (coordinatorHost == null || visibleNames.isEmpty()) continue
                groups += SonosGroup(
                    name = visibleNames.joinToString(" + "),
                    coordinatorHost = coordinatorHost,
                    coordinatorUuid = coordinatorUuid,
                    memberCount = visibleNames.size,
                )
            }
            return groups.sortedBy { it.name.lowercase() }
        }

        private fun parse(xml: String): Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            // Speakers are on the LAN but still untrusted input: no external entities.
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            factory.isExpandEntityReferences = false
            return factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        }

        private fun textOf(doc: Document, tag: String): String? {
            // Match with or without a namespace prefix ("u:CurrentVolume" / "CurrentVolume").
            val all = doc.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val node = all.item(i)
                if (node.nodeName == tag || node.nodeName.substringAfter(':') == tag) return node.textContent
            }
            return null
        }
    }
}
