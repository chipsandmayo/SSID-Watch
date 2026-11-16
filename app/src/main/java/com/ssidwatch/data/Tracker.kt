package com.ssidwatch.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * How tracking works
 * ------------------
 * Android keeps per-app Wi-Fi byte counters (NetworkStatsManager) but does NOT record
 * which Wi-Fi network (SSID) the bytes were used on. So we take snapshots of the
 * per-app counters regularly and whenever the SSID changes. The growth between two
 * snapshots belongs to the SSID that was connected in between.
 *
 * Counters are read cumulatively from a fixed "anchor" time that sits on a bucket
 * boundary (UTC midnight), so Android's 2-hour bucket interpolation never smears
 * usage from one network onto another. The anchor is moved forward weekly so we
 * never depend on history Android may prune.
 */
object Tracker {
    const val UNKNOWN = "Unknown network"
    const val BEFORE = "Before tracking"

    private const val DAY_MS = 86_400_000L
    private const val ANCHOR_MAX_AGE = 7 * DAY_MS
    private const val BACKFILL_DAYS = 30

    private const val K_ANCHOR = "anchor"
    private const val K_SSID = "ssid"           // SSID connected right now (null = not on Wi-Fi)
    private const val K_PREV_SSID = "prevSsid"  // last non-null SSID
    private const val K_LAST = "lastSample"
    private const val K_TRACK_START = "trackStart" // when per-SSID tracking began
    private const val K_BACKFILL_V = "backfillV"
    private const val BACKFILL_VERSION = "2"

    fun currentSsid(ctx: Context): String? = UsageDb.get(ctx).getMeta(K_SSID)

    fun lastSampleTime(ctx: Context): Long? = UsageDb.get(ctx).getMeta(K_LAST)?.toLongOrNull()

    /** Snapshot without changing the connected network (used by the UI). */
    @Synchronized
    fun refresh(ctx: Context): Boolean = sample(ctx, currentSsid(ctx))

    fun cleanSsid(raw: String?): String {
        if (raw == null) return UNKNOWN
        var s = raw.trim()
        if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") return UNKNOWN
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        return s.ifEmpty { UNKNOWN }
    }

    /**
     * Read Android's counters, attribute growth since the last snapshot to the SSID that
     * was connected, then record [newSsid] as the network connected from now on.
     * Returns false if usage access hasn't been granted.
     */
    @Synchronized
    fun sample(ctx: Context, newSsid: String?): Boolean {
        val app = ctx.applicationContext
        val db = UsageDb.get(app)
        val nsm = app.getSystemService(NetworkStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()

        val storedAnchor = db.getMeta(K_ANCHOR)?.toLongOrNull()
        val firstRun = storedAnchor == null
        var anchor = storedAnchor ?: alignToDay(now)

        val current = cumulative(nsm, anchor, now) ?: return false

        val w = db.writableDatabase
        w.beginTransaction()
        try {
            val zone = ZoneId.systemDefault()
            val day = LocalDate.now(zone).toString()
            val hour = LocalTime.now(zone).hour

            if (firstRun) {
                // Give the user some history straight away: Android's last 30 days of
                // Wi-Fi usage per app, filed under "Before tracking" (network unknown).
                backfill(db, nsm, anchor, includeUntilDay = true)
                db.setMeta(K_TRACK_START, anchor.toString())
                db.setMeta(K_BACKFILL_V, BACKFILL_VERSION)
                var sum = Traffic.ZERO
                for ((uid, t) in current) if (t.total > 0) {
                    db.addUsage(day, BEFORE, uid, t.rx, t.tx)
                    sum += t
                }
                if (sum.total > 0) db.addHourly(day, hour, BEFORE, sum.rx, sum.tx)
            } else {
                if (db.getMeta(K_BACKFILL_V) != BACKFILL_VERSION) {
                    // Upgrade from v1 (30-day daily import): replace it with the full
                    // hourly history Android keeps.
                    val start = db.getMeta(K_TRACK_START)?.toLongOrNull() ?: anchor
                    val startDay = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString()
                    db.deleteNetworkBefore(BEFORE, startDay)
                    backfill(db, nsm, start, includeUntilDay = false)
                    db.setMeta(K_TRACK_START, start.toString())
                    db.setMeta(K_BACKFILL_V, BACKFILL_VERSION)
                }
                val base = db.baselines()
                val ssid = db.getMeta(K_SSID) ?: db.getMeta(K_PREV_SSID) ?: UNKNOWN
                var sum = Traffic.ZERO
                for ((uid, t) in current) {
                    val b = base[uid] ?: Traffic.ZERO
                    val drx = (t.rx - b.rx).coerceAtLeast(0)
                    val dtx = (t.tx - b.tx).coerceAtLeast(0)
                    if (drx + dtx > 0) {
                        db.addUsage(day, ssid, uid, drx, dtx)
                        sum += Traffic(drx, dtx)
                    }
                }
                if (sum.total > 0) db.addHourly(day, hour, ssid, sum.rx, sum.tx)
            }

            var newBase = current
            if (now - anchor > ANCHOR_MAX_AGE) {
                val newAnchor = alignToDay(now)
                val rebased = cumulative(nsm, newAnchor, now)
                if (rebased != null) {
                    anchor = newAnchor
                    newBase = rebased
                }
            }
            db.replaceBaselines(newBase)
            db.setMeta(K_ANCHOR, anchor.toString())
            db.setMeta(K_SSID, newSsid)
            if (newSsid != null) db.setMeta(K_PREV_SSID, newSsid)
            db.setMeta(K_LAST, now.toString())
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        return true
    }

    private fun alignToDay(t: Long) = t - (t % DAY_MS)

    /** Per-uid Wi-Fi bytes from [start] up to now (the end is pushed into the future so
     *  the current, still-filling bucket is counted in full rather than pro-rated). */
    private fun cumulative(nsm: NetworkStatsManager, start: Long, now: Long): Map<Int, Traffic>? =
        query(nsm, start, now + DAY_MS)

    @Suppress("DEPRECATION")
    private fun query(nsm: NetworkStatsManager, start: Long, end: Long): Map<Int, Traffic>? {
        return try {
            val stats = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, start, end)
            val acc = HashMap<Int, LongArray>()
            val b = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                if (b.tag != NetworkStats.Bucket.TAG_NONE) continue
                val a = acc.getOrPut(b.uid) { LongArray(2) }
                a[0] += b.rxBytes
                a[1] += b.txBytes
            }
            stats.close()
            acc.mapValues { Traffic(it.value[0], it.value[1]) }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Import Android's own per-app Wi-Fi history (as far back as the phone keeps it,
     * typically ~3 months, in 2-hour buckets) for everything before [until].
     * Android never recorded which network it was, so it's filed under [BEFORE].
     */
    @Suppress("DEPRECATION")
    private fun backfill(db: UsageDb, nsm: NetworkStatsManager, until: Long, includeUntilDay: Boolean) {
        val zone = ZoneId.systemDefault()
        val untilDay = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        val stats = try {
            nsm.queryDetails(ConnectivityManager.TYPE_WIFI, null, 0L, until)
        } catch (e: Exception) {
            return
        }
        val perApp = HashMap<Pair<String, Int>, LongArray>()
        val perHour = HashMap<Pair<String, Int>, LongArray>()
        val b = NetworkStats.Bucket()
        try {
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                if (b.tag != NetworkStats.Bucket.TAG_NONE) continue
                if (b.startTimeStamp >= until) continue
                if (b.rxBytes + b.txBytes <= 0) continue
                val z = Instant.ofEpochMilli(b.startTimeStamp).atZone(zone)
                val d = z.toLocalDate()
                if (d.isAfter(untilDay) || (!includeUntilDay && d == untilDay)) continue
                val ds = d.toString()
                val a = perApp.getOrPut(ds to b.uid) { LongArray(2) }
                a[0] += b.rxBytes
                a[1] += b.txBytes
                val h = perHour.getOrPut(ds to z.hour) { LongArray(2) }
                h[0] += b.rxBytes
                h[1] += b.txBytes
            }
        } catch (e: Exception) {
            // keep whatever was read
        } finally {
            stats.close()
        }
        for ((k, v) in perApp) db.addUsage(k.first, BEFORE, k.second, v[0], v[1])
        for ((k, v) in perHour) db.addHourly(k.first, k.second, BEFORE, v[0], v[1])
    }
}
