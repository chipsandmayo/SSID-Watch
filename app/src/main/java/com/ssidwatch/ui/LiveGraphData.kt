package com.ssidwatch.ui

import android.content.Context
import com.ssidwatch.data.LivePoint
import com.ssidwatch.data.LiveRecorder
import com.ssidwatch.data.Traffic
import com.ssidwatch.data.UsageDb

/**
 * Data for one view of the live graph. Values are bits per second per bucket.
 * Bucket i covers [from + i*bucket, from + (i+1)*bucket).
 */
class GraphData(
    val from: Long,
    val bucket: Long,
    val rx: FloatArray,
    val tx: FloatArray,
    val apps: List<Pair<Int, FloatArray>>, // top apps, combined down+up
    val appTotals: List<Pair<Int, Traffic>>, // apps within the visible window, biggest first
    val active: Set<Int>,
    val dataUntil: Long, // newest recorded time
) {
    val size: Int get() = rx.size
}

private const val TWO_HOURS = 2 * 60 * 60 * 1000L
private const val SIX_HOURS = 6 * 60 * 60 * 1000L

/** Spread each row's bytes evenly over the buckets its interval overlaps. */
private fun spread(rows: List<LivePoint>, from: Long, b: Long, rxOut: DoubleArray, txOut: DoubleArray?) {
    val n = rxOut.size
    val end = from + n * b
    for (r in rows) {
        val s = r.ts - r.dur
        val e = r.ts
        if (e <= from || s >= end || r.dur <= 0) continue
        var i = ((maxOf(s, from) - from) / b).toInt()
        val last = ((minOf(e, end) - 1 - from) / b).toInt().coerceAtMost(n - 1)
        while (i <= last) {
            val bs = from + i * b
            val overlap = minOf(e, bs + b) - maxOf(s, bs)
            if (overlap > 0) {
                val f = overlap.toDouble() / r.dur
                rxOut[i] += r.rx * f
                if (txOut != null) txOut[i] += r.tx * f
            }
            i++
        }
    }
}

/**
 * Loads [viewStart, viewEnd] plus one view-width either side (so panning is instant),
 * in about [perView] buckets per visible width.
 */
fun loadGraph(ctx: Context, viewStart: Long, viewEnd: Long, perView: Int = 240, topApps: Int = 8): GraphData {
    val db = UsageDb.get(ctx)
    val span = (viewEnd - viewStart).coerceAtLeast(1_000)
    val b = (span / perView).coerceAtLeast(1_000)
    val from = (viewStart - span) / b * b
    val to = viewEnd + span
    val n = ((to - from) / b + 1).toInt()

    val totalRows = (if (span <= TWO_HOURS) db.liveRows(from, to) else db.liveRowsAgg(from, to, 60_000)) +
        LiveRecorder.pendingSince(from)
    val rx = DoubleArray(n)
    val tx = DoubleArray(n)
    spread(totalRows, from, b, rx, tx)
    val dataUntil = maxOf(totalRows.maxOfOrNull { it.ts } ?: 0L, LiveRecorder.latest.value.ts)

    val appRows = if (span <= SIX_HOURS) db.liveAppRows(from, to) else db.liveAppRowsAgg(from, to, 300_000)
    val byUid = appRows.groupBy({ it.first }, { it.second })

    // totals inside the visible window (proportional to overlap)
    val visible = byUid.mapValues { (_, rows) ->
        var r = 0.0
        var t = 0.0
        for (p in rows) {
            val s = p.ts - p.dur
            val overlap = minOf(p.ts, viewEnd) - maxOf(s, viewStart)
            if (overlap > 0 && p.dur > 0) {
                val f = overlap.toDouble() / p.dur
                r += p.rx * f
                t += p.tx * f
            }
        }
        Traffic(r.toLong(), t.toLong())
    }.filter { it.value.total > 0 }
        .toList()
        .sortedByDescending { it.second.total }

    val top = visible.take(topApps).map { it.first }
    val apps = top.map { uid ->
        val arr = DoubleArray(n)
        val rows = byUid[uid].orEmpty().map { LivePoint(it.ts, it.dur, it.rx + it.tx, 0) }
        spread(rows, from, b, arr, null)
        uid to FloatArray(n) { i -> (arr[i] * 8_000 / b).toFloat() }
    }

    val active = db.liveActiveUids(System.currentTimeMillis() - 45_000)

    return GraphData(
        from = from,
        bucket = b,
        rx = FloatArray(n) { i -> (rx[i] * 8_000 / b).toFloat() },
        tx = FloatArray(n) { i -> (tx[i] * 8_000 / b).toFloat() },
        apps = apps,
        appTotals = visible,
        active = active,
        dataUntil = dataUntil,
    )
}
