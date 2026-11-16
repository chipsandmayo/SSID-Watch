package com.ssidwatch.data

import android.content.Context
import android.net.TrafficStats
import com.ssidwatch.capture.CaptureStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Records whole-phone Wi-Fi speed continuously (called by the tracking service every
 * second while the screen is on, every ~10 s while it's off). Recent points are kept in
 * memory and written to the database in small batches.
 */
object LiveRecorder {
    private const val FLUSH_MS = 10_000L
    private const val CHUNK_MS = 10_000L
    private const val MAX_GAP_MS = 24 * 60 * 60 * 1000L

    private val lock = Any()
    private val pending = ArrayList<LivePoint>()
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastT = 0L
    private var lastFlush = 0L

    private val _latest = MutableStateFlow(LivePoint(0L, 1_000L, 0L, 0L))
    /** Most recent measurement; the UI and floating box show its speed. */
    val latest: StateFlow<LivePoint> = _latest

    // During packet capture, traffic also passes through the capture interface; subtract it
    // so it isn't counted twice.
    private fun wifiRx() = (TrafficStats.getTotalRxBytes().coerceAtLeast(0) -
        TrafficStats.getMobileRxBytes().coerceAtLeast(0) -
        CaptureStats.writtenToTun.get()).coerceAtLeast(0)

    private fun wifiTx() = (TrafficStats.getTotalTxBytes().coerceAtLeast(0) -
        TrafficStats.getMobileTxBytes().coerceAtLeast(0) -
        CaptureStats.readFromTun.get()).coerceAtLeast(0)

    fun tick(ctx: Context) {
        val now = System.currentTimeMillis()
        val rx = wifiRx()
        val tx = wifiTx()
        synchronized(lock) {
            if (lastRx >= 0 && now > lastT) {
                val dur = now - lastT
                val drx = (rx - lastRx).coerceAtLeast(0)
                val dtx = (tx - lastTx).coerceAtLeast(0)
                if (dur <= MAX_GAP_MS) {
                    if (dur <= CHUNK_MS + 5_000) {
                        pending.add(LivePoint(now, dur, drx, dtx))
                    } else {
                        // a long gap (phone asleep): spread it evenly in 10 s pieces
                        val n = ((dur + CHUNK_MS - 1) / CHUNK_MS).toInt()
                        for (i in 0 until n) {
                            val end = lastT + minOf(dur, (i + 1) * CHUNK_MS)
                            val start = lastT + i * CHUNK_MS
                            pending.add(LivePoint(end, end - start, drx / n, dtx / n))
                        }
                    }
                }
                _latest.value = LivePoint(now, dur, drx, dtx)
            }
            lastRx = rx
            lastTx = tx
            lastT = now
        }
        if (now - lastFlush >= FLUSH_MS) flush(ctx)
    }

    fun flush(ctx: Context) {
        val batch: List<LivePoint>
        synchronized(lock) {
            batch = ArrayList(pending)
            pending.clear()
            lastFlush = System.currentTimeMillis()
        }
        if (batch.isNotEmpty()) {
            try {
                UsageDb.get(ctx).insertLive(batch)
            } catch (e: Exception) {
                // drop on failure
            }
        }
    }

    /** Points not yet written to the database. */
    fun pendingSince(from: Long): List<LivePoint> = synchronized(lock) { pending.filter { it.ts > from } }

    fun bps(bytes: Long, durMs: Long): Long = if (durMs <= 0) 0 else bytes * 8_000 / durMs
}
