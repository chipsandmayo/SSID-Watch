package com.ssidwatch.capture

internal const val SEQ_MASK = 0xFFFFFFFFL

internal fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
internal fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
internal fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

internal fun put16(b: ByteArray, i: Int, v: Int) {
    b[i] = (v ushr 8).toByte()
    b[i + 1] = v.toByte()
}

internal fun put32(b: ByteArray, i: Int, v: Long) {
    put16(b, i, ((v ushr 16) and 0xFFFF).toInt())
    put16(b, i + 2, (v and 0xFFFF).toInt())
}

/** Signed difference a - b in 32-bit sequence space. */
internal fun seqDiff(a: Long, b: Long): Int = (a - b).toInt()

object TcpFlag {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val URG = 0x20
}

/** A parsed IPv4/IPv6 packet with its TCP/UDP header fields. */
class IpPacket private constructor(val raw: ByteArray, val length: Int) {
    var version = 0
    var protocol = 0 // 6 TCP, 17 UDP, 1 ICMP, 58 ICMPv6, -1 fragment / unparseable transport
    var src: ByteArray = EMPTY
    var dst: ByteArray = EMPTY
    var total = 0
    var l4 = 0
    var srcPort = 0
    var dstPort = 0
    var seq = 0L
    var ack = 0L
    var flags = 0
    var window = 0
    var mss = 0
    var dataOff = 0
    var dataLen = 0

    fun has(f: Int): Boolean = (flags and f) != 0

    companion object {
        private val EMPTY = ByteArray(0)

        fun parse(b: ByteArray, len: Int): IpPacket? {
            if (len < 20) return null
            val p = IpPacket(b, len)
            p.version = u8(b, 0) ushr 4
            if (p.version == 4) {
                val ihl = (u8(b, 0) and 0x0F) * 4
                if (ihl < 20 || len < ihl) return null
                p.total = u16(b, 2).coerceIn(ihl, len)
                val frag = u16(b, 6)
                p.protocol = if ((frag and 0x1FFF) != 0 || (frag and 0x2000) != 0) -1 else u8(b, 9)
                p.src = b.copyOfRange(12, 16)
                p.dst = b.copyOfRange(16, 20)
                p.l4 = ihl
            } else if (p.version == 6) {
                if (len < 40) return null
                p.total = (40 + u16(b, 4)).coerceAtMost(len)
                var next = u8(b, 6)
                var off = 40
                while (next == 0 || next == 43 || next == 60) {
                    if (off + 8 > p.total) return null
                    val nn = u8(b, off)
                    off += (u8(b, off + 1) + 1) * 8
                    next = nn
                }
                p.protocol = if (next == 44) -1 else next
                p.src = b.copyOfRange(8, 24)
                p.dst = b.copyOfRange(24, 40)
                p.l4 = off
            } else {
                return null
            }

            val o = p.l4
            when (p.protocol) {
                6 -> {
                    if (o + 20 > p.total) {
                        p.protocol = -1
                        return p
                    }
                    p.srcPort = u16(b, o)
                    p.dstPort = u16(b, o + 2)
                    p.seq = u32(b, o + 4)
                    p.ack = u32(b, o + 8)
                    val hl = (u8(b, o + 12) ushr 4) * 4
                    p.flags = u8(b, o + 13)
                    p.window = u16(b, o + 14)
                    var i = o + 20
                    val optEnd = (o + hl).coerceAtMost(p.total)
                    while (i < optEnd) {
                        val kind = u8(b, i)
                        if (kind == 0) break
                        if (kind == 1) {
                            i++
                            continue
                        }
                        if (i + 1 >= optEnd) break
                        val l = u8(b, i + 1)
                        if (l < 2) break
                        if (kind == 2 && l == 4 && i + 3 < optEnd) p.mss = u16(b, i + 2)
                        i += l
                    }
                    p.dataOff = (o + hl).coerceAtMost(p.total)
                    p.dataLen = (p.total - p.dataOff).coerceAtLeast(0)
                }
                17 -> {
                    if (o + 8 > p.total) {
                        p.protocol = -1
                        return p
                    }
                    p.srcPort = u16(b, o)
                    p.dstPort = u16(b, o + 2)
                    p.dataOff = o + 8
                    p.dataLen = (p.total - p.dataOff).coerceAtLeast(0)
                }
            }
            return p
        }
    }
}

/** Builds IPv4/IPv6 TCP and UDP packets with correct checksums. */
object Packets {
    private var ipId = 1

    private fun sum(b: ByteArray, off: Int, len: Int, initial: Long = 0L): Long {
        var s = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }

    private fun fold(sum: Long): Int {
        var s = sum
        while ((s ushr 16) != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    private fun ipHeader(src: ByteArray, dst: ByteArray, proto: Int, tl: Int): Pair<ByteArray, Int> {
        return if (src.size == 4) {
            val p = ByteArray(20 + tl)
            p[0] = 0x45
            put16(p, 2, 20 + tl)
            put16(p, 4, ipId and 0xFFFF)
            ipId++
            p[6] = 0x40
            p[8] = 64
            p[9] = proto.toByte()
            System.arraycopy(src, 0, p, 12, 4)
            System.arraycopy(dst, 0, p, 16, 4)
            put16(p, 10, fold(sum(p, 0, 20)))
            p to 20
        } else {
            val p = ByteArray(40 + tl)
            p[0] = 0x60
            put16(p, 4, tl)
            p[6] = proto.toByte()
            p[7] = 64
            System.arraycopy(src, 0, p, 8, 16)
            System.arraycopy(dst, 0, p, 24, 16)
            p to 40
        }
    }

    private fun l4Checksum(p: ByteArray, o: Int, tl: Int, src: ByteArray, dst: ByteArray, proto: Int): Int {
        var s = sum(src, 0, src.size) + sum(dst, 0, dst.size) + proto + tl
        s = sum(p, o, tl, s)
        return fold(s)
    }

    fun tcp(
        src: ByteArray, dst: ByteArray, sport: Int, dport: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        data: ByteArray? = null, off: Int = 0, len: Int = 0, mss: Int = 0,
    ): ByteArray {
        val thl = if (mss > 0) 24 else 20
        val tl = thl + len
        val (p, o) = ipHeader(src, dst, 6, tl)
        put16(p, o, sport)
        put16(p, o + 2, dport)
        put32(p, o + 4, seq and SEQ_MASK)
        put32(p, o + 8, ack and SEQ_MASK)
        p[o + 12] = ((thl / 4) shl 4).toByte()
        p[o + 13] = flags.toByte()
        put16(p, o + 14, window.coerceIn(0, 65535))
        if (mss > 0) {
            p[o + 20] = 2
            p[o + 21] = 4
            put16(p, o + 22, mss)
        }
        if (len > 0 && data != null) System.arraycopy(data, off, p, o + thl, len)
        put16(p, o + 16, l4Checksum(p, o, tl, src, dst, 6))
        return p
    }

    fun udp(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, data: ByteArray, off: Int, len: Int): ByteArray {
        val tl = 8 + len
        val (p, o) = ipHeader(src, dst, 17, tl)
        put16(p, o, sport)
        put16(p, o + 2, dport)
        put16(p, o + 4, tl)
        System.arraycopy(data, off, p, o + 8, len)
        var cs = l4Checksum(p, o, tl, src, dst, 17)
        if (cs == 0) cs = 0xFFFF
        put16(p, o + 6, cs)
        return p
    }
}

/** Growable FIFO byte buffer. Data lives in array()[offset() until offset()+size]. */
class ByteQueue(initial: Int = 16 * 1024) {
    private var buf = ByteArray(initial)
    private var start = 0
    var size = 0
        private set

    fun array(): ByteArray = buf
    fun offset(): Int = start

    fun append(src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (start + size + len > buf.size) {
            if (size + len <= buf.size) {
                System.arraycopy(buf, start, buf, 0, size)
            } else {
                val nb = ByteArray(maxOf(buf.size * 2, size + len))
                System.arraycopy(buf, start, nb, 0, size)
                buf = nb
            }
            start = 0
        }
        System.arraycopy(src, off, buf, start + size, len)
        size += len
    }

    fun drop(n: Int) {
        val k = n.coerceIn(0, size)
        start += k
        size -= k
        if (size == 0) start = 0
    }
}
