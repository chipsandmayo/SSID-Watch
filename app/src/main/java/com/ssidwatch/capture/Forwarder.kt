package com.ssidwatch.capture

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Passes captured packets on to the real network and builds the replies.
 *
 * Android gives a VPN app raw IP packets but no way to send them, so each TCP connection
 * and UDP flow is re-created with an ordinary (protected) socket, and the answers are
 * wrapped back into IP packets for the phone. Everything runs on this one thread.
 */
class Forwarder(
    private val vpn: VpnService,
    private val tunOut: FileOutputStream,
    private val cm: ConnectivityManager?,
) : Thread("ssidwatch-capture") {

    private val selector: Selector = Selector.open()
    private val inbox = ConcurrentLinkedQueue<IpPacket>()
    @Volatile private var running = true
    private val tcp = HashMap<String, TcpConn>()
    private val udp = HashMap<String, UdpFlow>()
    private val readBuf = ByteArray(65_536)
    private val rnd = Random()
    private var lastTimers = 0L

    private class TcpConn(
        val key: String,
        val appIp: ByteArray,
        val appPort: Int,
        val remIp: ByteArray,
        val remPort: Int,
        val ch: SocketChannel,
        val uid: Int,
    ) {
        var state = CONNECTING
        var appNext = 0L   // next sequence number expected from the app
        var isn = 0L
        var sndNxt = 0L    // next sequence number we send to the app
        var sndUna = 0L    // oldest sequence number the app hasn't acknowledged
        var appWnd = 65_535
        var mss = 1_360
        val toApp = ByteQueue()     // received from server; from sndUna onwards
        val toRemote = ByteQueue()  // received from app, not yet written to server
        var remoteEof = false
        var finSent = false
        var appFin = false
        var outShut = false
        var lastAdvWnd = 65_535
        val created = SystemClock.elapsedRealtime()
        var lastActive = created
        var lastProgress = created
        var synAckAt = 0L
        var synAckTries = 0
        var key2: SelectionKey? = null
    }

    private class UdpFlow(
        val appIp: ByteArray,
        val appPort: Int,
        val remIp: ByteArray,
        val remPort: Int,
        val ch: DatagramChannel,
        val uid: Int,
    ) {
        var lastActive = SystemClock.elapsedRealtime()
        var key2: SelectionKey? = null
    }

    fun submit(p: IpPacket) {
        inbox.add(p)
        selector.wakeup()
    }

    fun shutdown() {
        running = false
        selector.wakeup()
    }

    override fun run() {
        try {
            while (running) {
                selector.select(250)
                while (true) {
                    val p = inbox.poll() ?: break
                    try {
                        handle(p)
                    } catch (e: Exception) {
                        // ignore a bad packet
                    }
                }
                val it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    val k = it.next()
                    it.remove()
                    if (!k.isValid) continue
                    try {
                        when (val a = k.attachment()) {
                            is TcpConn -> onTcpKey(a, k)
                            is UdpFlow -> onUdpReadable(a)
                        }
                    } catch (e: Exception) {
                        // keep going
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastTimers >= 250) {
                    lastTimers = now
                    timers(now)
                }
            }
        } catch (e: Exception) {
            CaptureLog.lastError = e.message
        } finally {
            tcp.values.toList().forEach { closeQuietly(it) }
            udp.values.toList().forEach { closeUdp(it) }
            runCatching { selector.close() }
        }
    }

    // ---- shared ------------------------------------------------------------------

    private fun flowKey(aIp: ByteArray, aPort: Int, rIp: ByteArray, rPort: Int): String =
        aIp.joinToString(".") + ":" + aPort + ">" + rIp.joinToString(".") + ":" + rPort

    private fun writeTun(bytes: ByteArray, uid: Int) {
        try {
            tunOut.write(bytes)
            CaptureStats.writtenToTun.addAndGet(bytes.size.toLong())
        } catch (e: IOException) {
            return
        }
        IpPacket.parse(bytes, bytes.size)?.let { CaptureLog.add(it, outgoing = false, uid = uid) }
    }

    private fun ownerUid(proto: Int, p: IpPacket): Int {
        if (Build.VERSION.SDK_INT < 29 || cm == null) return -1
        return try {
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(InetAddress.getByAddress(p.src), p.srcPort),
                InetSocketAddress(InetAddress.getByAddress(p.dst), p.dstPort)
            )
        } catch (e: Exception) {
            -1
        }
    }

    private fun handle(p: IpPacket) {
        when (p.protocol) {
            6 -> handleTcp(p)
            17 -> handleUdp(p)
            else -> CaptureLog.add(p, outgoing = true, uid = -1) // ICMP etc. can't be forwarded without root
        }
    }

    // ---- UDP ---------------------------------------------------------------------

    private fun handleUdp(p: IpPacket) {
        val k = flowKey(p.src, p.srcPort, p.dst, p.dstPort)
        var f = udp[k]
        if (f == null) {
            val ch = DatagramChannel.open()
            try {
                ch.configureBlocking(false)
                if (!vpn.protect(ch.socket())) throw IOException("protect failed")
                ch.connect(InetSocketAddress(InetAddress.getByAddress(p.dst), p.dstPort))
            } catch (e: Exception) {
                runCatching { ch.close() }
                CaptureLog.add(p, outgoing = true, uid = -1)
                return
            }
            f = UdpFlow(p.src, p.srcPort, p.dst, p.dstPort, ch, ownerUid(17, p))
            f.key2 = ch.register(selector, SelectionKey.OP_READ, f)
            udp[k] = f
        }
        CaptureLog.add(p, outgoing = true, uid = f.uid)
        f.lastActive = SystemClock.elapsedRealtime()
        try {
            f.ch.write(ByteBuffer.wrap(p.raw, p.dataOff, p.dataLen))
        } catch (e: Exception) {
            // dropped
        }
    }

    private fun onUdpReadable(f: UdpFlow) {
        while (true) {
            val bb = ByteBuffer.wrap(readBuf)
            val n = try {
                f.ch.read(bb)
            } catch (e: Exception) {
                closeUdp(f)
                return
            }
            if (n <= 0) break
            f.lastActive = SystemClock.elapsedRealtime()
            writeTun(Packets.udp(f.remIp, f.appIp, f.remPort, f.appPort, readBuf, 0, n), f.uid)
        }
    }

    private fun closeUdp(f: UdpFlow) {
        f.key2?.cancel()
        runCatching { f.ch.close() }
        udp.remove(flowKey(f.appIp, f.appPort, f.remIp, f.remPort))
    }

    // ---- TCP ---------------------------------------------------------------------

    private fun handleTcp(p: IpPacket) {
        val k = flowKey(p.src, p.srcPort, p.dst, p.dstPort)
        var c = tcp[k]

        if (p.has(TcpFlag.RST)) {
            CaptureLog.add(p, outgoing = true, uid = c?.uid ?: -1)
            c?.let { closeQuietly(it) }
            return
        }

        if (c == null) {
            if (p.has(TcpFlag.SYN) && !p.has(TcpFlag.ACK)) {
                c = openTcp(p, k)
                if (c == null) return
            } else {
                CaptureLog.add(p, outgoing = true, uid = -1)
                // unknown connection: tell the app to give up on it
                val ackNo = p.seq + p.dataLen + (if (p.has(TcpFlag.SYN) || p.has(TcpFlag.FIN)) 1 else 0)
                writeTun(
                    Packets.tcp(
                        p.dst, p.src, p.dstPort, p.srcPort,
                        if (p.has(TcpFlag.ACK)) p.ack else 0L, ackNo,
                        TcpFlag.RST or TcpFlag.ACK, 0
                    ),
                    -1
                )
                return
            }
            CaptureLog.add(p, outgoing = true, uid = c.uid)
            return
        }

        CaptureLog.add(p, outgoing = true, uid = c.uid)
        val now = SystemClock.elapsedRealtime()
        c.lastActive = now
        if (p.has(TcpFlag.SYN)) return // retransmitted SYN; the SYN-ACK timer handles it

        if (c.state == SYN_RCVD) {
            if (p.has(TcpFlag.ACK) && p.ack == ((c.isn + 1) and SEQ_MASK)) {
                c.state = ESTABLISHED
                c.sndUna = p.ack
                c.appWnd = p.window
                c.lastProgress = now
                setInterest(c)
            } else {
                return
            }
        }
        if (c.state != ESTABLISHED) return

        if (p.has(TcpFlag.ACK)) {
            val acked = seqDiff(p.ack, c.sndUna)
            if (acked > 0 && seqDiff(p.ack, c.sndNxt) <= 0) {
                c.toApp.drop(acked)
                c.sndUna = p.ack
                c.lastProgress = now
            }
            c.appWnd = p.window
        }

        if (p.dataLen > 0) {
            if (seqDiff(p.seq, c.appNext) == 0) {
                c.toRemote.append(p.raw, p.dataOff, p.dataLen)
                c.appNext = (c.appNext + p.dataLen) and SEQ_MASK
                flushRemote(c, fromWritable = false)
            }
            sendAck(c)
        }

        if (p.has(TcpFlag.FIN)) {
            if (!c.appFin && seqDiff((p.seq + p.dataLen) and SEQ_MASK, c.appNext) == 0) {
                c.appNext = (c.appNext + 1) and SEQ_MASK
                c.appFin = true
                flushRemote(c, fromWritable = false)
            }
            sendAck(c)
        }

        trySend(c)
        setInterest(c)
        maybeClose(c)
    }

    private fun openTcp(p: IpPacket, k: String): TcpConn? {
        val ch = SocketChannel.open()
        try {
            ch.configureBlocking(false)
            if (!vpn.protect(ch.socket())) throw IOException("protect failed")
            val c = TcpConn(k, p.src, p.srcPort, p.dst, p.dstPort, ch, ownerUid(6, p))
            c.appNext = (p.seq + 1) and SEQ_MASK
            c.isn = (rnd.nextInt().toLong()) and SEQ_MASK
            c.sndNxt = c.isn
            c.sndUna = c.isn
            c.appWnd = p.window
            val cap = if (p.src.size == 4) 1_460 else 1_440
            c.mss = (if (p.mss > 0) p.mss else 536).coerceAtMost(cap)
            tcp[k] = c
            val done = ch.connect(InetSocketAddress(InetAddress.getByAddress(p.dst), p.dstPort))
            c.key2 = ch.register(selector, SelectionKey.OP_CONNECT, c)
            if (done) onConnected(c)
            return c
        } catch (e: Exception) {
            tcp.remove(k)
            runCatching { ch.close() }
            CaptureLog.add(p, outgoing = true, uid = -1)
            writeTun(
                Packets.tcp(p.dst, p.src, p.dstPort, p.srcPort, 0L, (p.seq + 1) and SEQ_MASK, TcpFlag.RST or TcpFlag.ACK, 0),
                -1
            )
            return null
        }
    }

    private fun onConnected(c: TcpConn) {
        c.state = SYN_RCVD
        sendSynAck(c)
        setInterest(c)
    }

    private fun sendSynAck(c: TcpConn) {
        val ourMss = if (c.appIp.size == 4) 1_400 else 1_380
        writeTun(
            Packets.tcp(
                c.remIp, c.appIp, c.remPort, c.appPort,
                c.isn, c.appNext, TcpFlag.SYN or TcpFlag.ACK, 65_535, mss = ourMss
            ),
            c.uid
        )
        c.sndNxt = (c.isn + 1) and SEQ_MASK
        c.synAckAt = SystemClock.elapsedRealtime()
        c.synAckTries++
    }

    private fun onTcpKey(c: TcpConn, k: SelectionKey) {
        if (k.isConnectable) {
            try {
                if (c.ch.finishConnect()) onConnected(c)
            } catch (e: Exception) {
                reset(c)
            }
            return
        }
        if (k.isReadable) onTcpReadable(c)
        if (k.isValid && k.isWritable) flushRemote(c, fromWritable = true)
        if (tcp.containsKey(c.key)) {
            setInterest(c)
            maybeClose(c)
        }
    }

    private fun onTcpReadable(c: TcpConn) {
        if (c.toApp.size >= MAX_BUFFER) return
        val n = try {
            c.ch.read(ByteBuffer.wrap(readBuf))
        } catch (e: Exception) {
            reset(c)
            return
        }
        if (n < 0) {
            c.remoteEof = true
        } else if (n > 0) {
            c.toApp.append(readBuf, 0, n)
        }
        c.lastActive = SystemClock.elapsedRealtime()
        trySend(c)
    }

    /** Sends as much buffered server data to the app as its window allows. */
    private fun trySend(c: TcpConn) {
        if (c.state != ESTABLISHED) return
        var inflight = seqDiff(c.sndNxt, c.sndUna)
        while (!c.finSent) {
            val unsent = c.toApp.size - inflight
            val allowed = c.appWnd - inflight
            if (unsent <= 0 || allowed <= 0) break
            val len = minOf(c.mss, unsent, allowed)
            writeTun(
                Packets.tcp(
                    c.remIp, c.appIp, c.remPort, c.appPort,
                    c.sndNxt, c.appNext, TcpFlag.ACK or TcpFlag.PSH, window(c),
                    c.toApp.array(), c.toApp.offset() + inflight, len
                ),
                c.uid
            )
            c.sndNxt = (c.sndNxt + len) and SEQ_MASK
            inflight += len
        }
        if (c.remoteEof && !c.finSent && c.toApp.size - inflight <= 0) {
            writeTun(
                Packets.tcp(
                    c.remIp, c.appIp, c.remPort, c.appPort,
                    c.sndNxt, c.appNext, TcpFlag.FIN or TcpFlag.ACK, window(c)
                ),
                c.uid
            )
            c.sndNxt = (c.sndNxt + 1) and SEQ_MASK
            c.finSent = true
        }
    }

    private fun window(c: TcpConn): Int = (65_535 - c.toRemote.size).coerceAtLeast(0)

    private fun sendAck(c: TcpConn) {
        val w = window(c)
        c.lastAdvWnd = w
        writeTun(
            Packets.tcp(c.remIp, c.appIp, c.remPort, c.appPort, c.sndNxt, c.appNext, TcpFlag.ACK, w),
            c.uid
        )
    }

    /** Writes app data to the server; shuts the server side down once the app has finished. */
    private fun flushRemote(c: TcpConn, fromWritable: Boolean) {
        if (c.state == CONNECTING) return
        try {
            while (c.toRemote.size > 0) {
                val n = c.ch.write(ByteBuffer.wrap(c.toRemote.array(), c.toRemote.offset(), c.toRemote.size))
                if (n <= 0) break
                c.toRemote.drop(n)
            }
            if (c.toRemote.size == 0) {
                if (c.appFin && !c.outShut) {
                    c.outShut = true
                    runCatching { c.ch.socket().shutdownOutput() }
                }
                if (fromWritable && c.lastAdvWnd < 32_768) sendAck(c) // window update
            }
        } catch (e: Exception) {
            reset(c)
        }
    }

    private fun setInterest(c: TcpConn) {
        val k = c.key2 ?: return
        if (!k.isValid) return
        var ops = 0
        if (c.state == CONNECTING) {
            ops = SelectionKey.OP_CONNECT
        } else {
            if (!c.remoteEof && c.toApp.size < MAX_BUFFER) ops = ops or SelectionKey.OP_READ
            if (c.toRemote.size > 0) ops = ops or SelectionKey.OP_WRITE
        }
        runCatching { k.interestOps(ops) }
    }

    private fun maybeClose(c: TcpConn) {
        if (c.appFin && c.finSent && c.sndUna == c.sndNxt && c.toRemote.size == 0) closeQuietly(c)
    }

    private fun reset(c: TcpConn) {
        writeTun(
            Packets.tcp(c.remIp, c.appIp, c.remPort, c.appPort, c.sndNxt, c.appNext, TcpFlag.RST or TcpFlag.ACK, 0),
            c.uid
        )
        closeQuietly(c)
    }

    private fun closeQuietly(c: TcpConn) {
        c.key2?.cancel()
        runCatching { c.ch.close() }
        tcp.remove(c.key)
    }

    private fun timers(now: Long) {
        for (c in tcp.values.toList()) {
            when (c.state) {
                CONNECTING -> if (now - c.created > 20_000) reset(c)
                SYN_RCVD -> if (now - c.synAckAt > 1_000) {
                    if (c.synAckTries >= 5) reset(c) else sendSynAck(c)
                }
                ESTABLISHED -> {
                    if (seqDiff(c.sndNxt, c.sndUna) > 0 && now - c.lastProgress > 1_500) {
                        // nothing acknowledged for a while: resend from the oldest unacknowledged byte
                        c.sndNxt = c.sndUna
                        c.finSent = false
                        c.lastProgress = now
                        trySend(c)
                    } else if (now - c.lastActive > 30 * 60_000L) {
                        reset(c)
                    }
                }
            }
        }
        for (f in udp.values.toList()) {
            val idle = if (f.remPort == 53) 15_000 else 120_000
            if (now - f.lastActive > idle) closeUdp(f)
        }
    }

    companion object {
        private const val CONNECTING = 0
        private const val SYN_RCVD = 1
        private const val ESTABLISHED = 2
        private const val MAX_BUFFER = 512 * 1024
    }
}
