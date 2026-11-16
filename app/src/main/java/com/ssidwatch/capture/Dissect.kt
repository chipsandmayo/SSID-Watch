package com.ssidwatch.capture

import java.net.InetAddress

/** Turns packets into Wireshark-style "Protocol" and "Info" columns. */
object Dissect {

    fun address(ip: ByteArray, port: Int, withPort: Boolean): String {
        val host = if (ip.size == 4) {
            "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
        } else {
            try {
                InetAddress.getByAddress(ip).hostAddress ?: "?"
            } catch (e: Exception) {
                "?"
            }
        }
        if (!withPort) return host
        return if (ip.size == 4) "$host:$port" else "[$host]:$port"
    }

    fun flagNames(f: Int): String {
        val names = ArrayList<String>(4)
        if ((f and TcpFlag.SYN) != 0) names += "SYN"
        if ((f and TcpFlag.FIN) != 0) names += "FIN"
        if ((f and TcpFlag.RST) != 0) names += "RST"
        if ((f and TcpFlag.PSH) != 0) names += "PSH"
        if ((f and TcpFlag.ACK) != 0) names += "ACK"
        if ((f and TcpFlag.URG) != 0) names += "URG"
        return names.joinToString(", ")
    }

    fun describe(p: IpPacket): Pair<String, String> {
        val b = p.raw
        when (p.protocol) {
            6 -> {
                if (p.dataLen > 0) {
                    tls(b, p.dataOff, p.dataLen)?.let { return "TLS" to it }
                    http(b, p.dataOff, p.dataLen)?.let { return "HTTP" to it }
                    if ((p.srcPort == 53 || p.dstPort == 53) && p.dataLen > 2) {
                        dns(b, p.dataOff + 2, p.dataLen - 2)?.let { return "DNS" to it }
                    }
                }
                return "TCP" to "${p.srcPort} → ${p.dstPort} [${flagNames(p.flags)}] " +
                    "Seq=${p.seq} Ack=${p.ack} Win=${p.window} Len=${p.dataLen}"
            }
            17 -> {
                val sp = p.srcPort
                val dp = p.dstPort
                if (sp == 53 || dp == 53) dns(b, p.dataOff, p.dataLen)?.let { return "DNS" to it }
                if (sp == 5353 || dp == 5353) dns(b, p.dataOff, p.dataLen)?.let { return "MDNS" to it }
                if (sp == 443 || dp == 443) return "QUIC" to "$sp → $dp Len=${p.dataLen}" + quicNote(b, p.dataOff, p.dataLen)
                if (sp == 123 || dp == 123) return "NTP" to "$sp → $dp Len=${p.dataLen}"
                if (sp == 67 || dp == 67 || sp == 68 || dp == 68) return "DHCP" to "$sp → $dp Len=${p.dataLen}"
                if (sp == 1900 || dp == 1900) return "SSDP" to "$sp → $dp Len=${p.dataLen}"
                return "UDP" to "$sp → $dp Len=${p.dataLen}"
            }
            1 -> return "ICMP" to icmp(b, p.l4, p.total)
            58 -> return "ICMPv6" to "Type ${if (p.l4 < p.total) u8(b, p.l4) else -1}"
            -1 -> return "IP" to "Fragment / unparsed"
            else -> return "IP" to "Protocol ${p.protocol}"
        }
    }

    private fun icmp(b: ByteArray, o: Int, end: Int): String {
        if (o + 1 >= end) return "ICMP"
        return when (u8(b, o)) {
            0 -> "Echo (ping) reply"
            8 -> "Echo (ping) request"
            3 -> "Destination unreachable"
            11 -> "Time-to-live exceeded"
            else -> "Type ${u8(b, o)} Code ${u8(b, o + 1)}"
        }
    }

    private fun quicNote(b: ByteArray, off: Int, len: Int): String {
        if (len < 1) return ""
        return if ((u8(b, off) and 0x80) != 0) ", Long header (handshake)" else ", Short header (data)"
    }

    // ---- TLS ---------------------------------------------------------------------

    private fun tls(b: ByteArray, off: Int, len: Int): String? {
        if (len < 5) return null
        val type = u8(b, off)
        if (type !in 0x14..0x17 || u8(b, off + 1) != 3) return null
        val ver = when (u8(b, off + 2)) {
            1 -> "TLSv1.0"
            2 -> "TLSv1.1"
            3 -> "TLSv1.2"
            4 -> "TLSv1.3"
            else -> "TLS"
        }
        return when (type) {
            0x16 -> {
                if (len < 6) return "$ver Handshake"
                when (u8(b, off + 5)) {
                    1 -> {
                        val name = sni(b, off, len)
                        "Client Hello" + (name?.let { " (SNI=$it)" } ?: "")
                    }
                    2 -> "Server Hello"
                    11 -> "Certificate"
                    else -> "$ver Handshake"
                }
            }
            0x17 -> "Application Data"
            0x15 -> "Alert"
            0x14 -> "Change Cipher Spec"
            else -> null
        }
    }

    private fun sni(b: ByteArray, off: Int, len: Int): String? {
        val end = off + len
        var i = off + 5
        if (i + 4 > end || u8(b, i) != 1) return null
        i += 4 + 2 + 32
        if (i + 1 > end) return null
        i += 1 + u8(b, i)
        if (i + 2 > end) return null
        i += 2 + u16(b, i)
        if (i + 1 > end) return null
        i += 1 + u8(b, i)
        if (i + 2 > end) return null
        val extEnd = minOf(end, i + 2 + u16(b, i))
        i += 2
        while (i + 4 <= extEnd) {
            val t = u16(b, i)
            val l = u16(b, i + 2)
            i += 4
            if (t == 0 && i + 5 <= extEnd) {
                val nl = u16(b, i + 3)
                if (i + 5 + nl <= extEnd) return String(b, i + 5, nl, Charsets.US_ASCII)
                return null
            }
            i += l
        }
        return null
    }

    // ---- HTTP --------------------------------------------------------------------

    private val METHODS = listOf("GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ", "HTTP/1.")

    private fun http(b: ByteArray, off: Int, len: Int): String? {
        val n = minOf(len, 400)
        val s = String(b, off, n, Charsets.ISO_8859_1)
        if (METHODS.none { s.startsWith(it) }) return null
        val first = s.substringBefore("\r\n").take(120)
        val host = s.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        return if (host != null && !first.startsWith("HTTP/")) "$first (Host: $host)" else first
    }

    // ---- DNS ---------------------------------------------------------------------

    private fun typeName(t: Int) = when (t) {
        1 -> "A"
        2 -> "NS"
        5 -> "CNAME"
        6 -> "SOA"
        12 -> "PTR"
        15 -> "MX"
        16 -> "TXT"
        28 -> "AAAA"
        33 -> "SRV"
        64 -> "SVCB"
        65 -> "HTTPS"
        255 -> "ANY"
        else -> "TYPE$t"
    }

    /** Reads a (possibly compressed) DNS name. Returns the name and the position after it. */
    private fun readName(b: ByteArray, msg: Int, start: Int, end: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = start
        var after = -1
        var jumps = 0
        while (true) {
            if (i >= end) return null
            val l = u8(b, i)
            if (l == 0) {
                i++
                break
            }
            if ((l and 0xC0) == 0xC0) {
                if (i + 1 >= end) return null
                if (after < 0) after = i + 2
                i = msg + (((l and 0x3F) shl 8) or u8(b, i + 1))
                if (++jumps > 20) return null
                continue
            }
            if (i + 1 + l > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, i + 1, l, Charsets.US_ASCII))
            i += 1 + l
        }
        return sb.toString().ifEmpty { "<root>" } to (if (after >= 0) after else i)
    }

    private fun dns(b: ByteArray, off: Int, len: Int): String? {
        if (len < 12) return null
        val end = off + len
        val id = u16(b, off)
        val flags = u16(b, off + 2)
        val qd = u16(b, off + 4)
        val an = u16(b, off + 6)
        val resp = (flags and 0x8000) != 0
        val sb = StringBuilder(if (resp) "Standard query response " else "Standard query ")
        sb.append(String.format("0x%04x", id))
        var i = off + 12
        if (qd > 0) {
            val (qname, ni) = readName(b, off, i, end) ?: return sb.toString()
            i = ni
            if (i + 4 > end) return sb.toString()
            sb.append(' ').append(typeName(u16(b, i))).append(' ').append(qname)
            i += 4
        }
        if (resp) {
            val rcode = flags and 0x0F
            if (rcode == 3) sb.append(" No such name")
            else if (rcode != 0) sb.append(" error $rcode")
            var k = 0
            while (k < an && k < 4) {
                val (_, ni) = readName(b, off, i, end) ?: break
                i = ni
                if (i + 10 > end) break
                val type = u16(b, i)
                val rdlen = u16(b, i + 8)
                i += 10
                if (i + rdlen > end) break
                val value = when {
                    type == 1 && rdlen == 4 -> address(b.copyOfRange(i, i + 4), 0, false)
                    type == 28 && rdlen == 16 -> address(b.copyOfRange(i, i + 16), 0, false)
                    type == 5 -> readName(b, off, i, end)?.first ?: ""
                    else -> ""
                }
                sb.append(' ').append(typeName(type))
                if (value.isNotEmpty()) sb.append(' ').append(value)
                i += rdlen
                k++
            }
        }
        return sb.toString()
    }
}
