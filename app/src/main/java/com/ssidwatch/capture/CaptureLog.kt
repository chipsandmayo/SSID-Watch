package com.ssidwatch.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/** In-memory packet list for the Capture tab (newest last, capped). */
object CaptureLog {
    const val MAX = 5_000

    class Entry(
        val no: Long,
        val time: Long,
        val outgoing: Boolean,
        val uid: Int,
        val proto: String,
        val src: String,
        val dst: String,
        val length: Int,
        val info: String,
        val head: ByteArray,
    )

    private val lock = Any()
    private val list = ArrayDeque<Entry>()
    private var counter = 0L

    @Volatile var startedAt = 0L
        private set
    val packets = AtomicLong()
    val bytes = AtomicLong()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    @Volatile var lastError: String? = null

    fun setRunning(on: Boolean) {
        if (on && !_running.value) startedAt = System.currentTimeMillis()
        _running.value = on
    }

    fun clear() {
        synchronized(lock) {
            list.clear()
            counter = 0
        }
        packets.set(0)
        bytes.set(0)
        startedAt = System.currentTimeMillis()
    }

    fun add(p: IpPacket, outgoing: Boolean, uid: Int) {
        val (proto, info) = try {
            Dissect.describe(p)
        } catch (e: Exception) {
            "IP" to "(could not decode)"
        }
        val ports = p.protocol == 6 || p.protocol == 17
        val e: Entry
        synchronized(lock) {
            counter++
            e = Entry(
                no = counter,
                time = System.currentTimeMillis(),
                outgoing = outgoing,
                uid = uid,
                proto = proto,
                src = Dissect.address(p.src, p.srcPort, ports),
                dst = Dissect.address(p.dst, p.dstPort, ports),
                length = p.total,
                info = info,
                head = p.raw.copyOf(minOf(p.length, 160)),
            )
            list.addLast(e)
            if (list.size > MAX) list.removeFirst()
        }
        packets.incrementAndGet()
        bytes.addAndGet(p.total.toLong())
    }

    fun snapshot(): List<Entry> = synchronized(lock) { list.toList() }
}

/** Bytes passing through the capture interface (so the Live graph doesn't count them twice). */
object CaptureStats {
    val readFromTun = AtomicLong()   // phone -> network
    val writtenToTun = AtomicLong()  // network -> phone
}
