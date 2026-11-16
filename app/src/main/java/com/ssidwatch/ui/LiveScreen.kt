package com.ssidwatch.ui

import android.net.TrafficStats
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssidwatch.data.Traffic
import com.ssidwatch.data.Tracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val WINDOW = 60 // seconds shown on the graph

private data class AppLive(val uid: Int, val rxBps: Long, val txBps: Long, val session: Traffic) {
    val rate: Long get() = rxBps + txBps
}

@Composable
fun LiveScreen(padding: PaddingValues) {
    val ctx = LocalContext.current
    val samples = remember { mutableStateListOf<Traffic>() } // bits per second (rx, tx)
    var rxNow by remember { mutableLongStateOf(0L) }
    var txNow by remember { mutableLongStateOf(0L) }
    var apps by remember { mutableStateOf<List<AppLive>>(emptyList()) }
    var measuring by remember { mutableStateOf(true) }

    // Whole-phone Wi-Fi speed, every second (all traffic minus mobile data).
    LaunchedEffect(Unit) {
        fun wifiRx() = (TrafficStats.getTotalRxBytes().coerceAtLeast(0) -
            TrafficStats.getMobileRxBytes().coerceAtLeast(0)).coerceAtLeast(0)
        fun wifiTx() = (TrafficStats.getTotalTxBytes().coerceAtLeast(0) -
            TrafficStats.getMobileTxBytes().coerceAtLeast(0)).coerceAtLeast(0)
        var lastRx = wifiRx()
        var lastTx = wifiTx()
        var lastT = SystemClock.elapsedRealtime()
        while (true) {
            delay(1_000)
            val rx = wifiRx()
            val tx = wifiTx()
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastT).coerceAtLeast(1)
            rxNow = (rx - lastRx).coerceAtLeast(0) * 8_000 / dt
            txNow = (tx - lastTx).coerceAtLeast(0) * 8_000 / dt
            lastRx = rx
            lastTx = tx
            lastT = now
            samples.add(Traffic(rxNow, txNow))
            while (samples.size > WINDOW) samples.removeAt(0)
        }
    }

    // Per-app speeds. Android refreshes per-app counters less often than the total,
    // so rates are worked out over the time between counter changes.
    LaunchedEffect(Unit) {
        val start = Tracker.liveStart()
        var first: Map<Int, Traffic>? = null
        var prev: Map<Int, Traffic> = emptyMap()
        var prevT = 0L
        var rates: Map<Int, Pair<Long, Long>> = emptyMap()
        while (true) {
            val snap = withContext(Dispatchers.IO) { Tracker.liveSnapshot(ctx, start) }
            val now = SystemClock.elapsedRealtime()
            if (snap != null) {
                val base = first ?: snap.also {
                    first = it
                    prev = it
                    prevT = now
                }
                if (snap != prev) {
                    val dt = (now - prevT).coerceAtLeast(1_000)
                    val old = prev
                    rates = snap.mapNotNull { (uid, t) ->
                        val p = old[uid] ?: Traffic.ZERO
                        val drx = (t.rx - p.rx).coerceAtLeast(0)
                        val dtx = (t.tx - p.tx).coerceAtLeast(0)
                        if (drx + dtx > 0) uid to (drx * 8_000 / dt to dtx * 8_000 / dt) else null
                    }.toMap()
                    prev = snap
                    prevT = now
                } else if (now - prevT > 20_000) {
                    rates = emptyMap()
                }
                val r = rates
                apps = snap.mapNotNull { (uid, t) ->
                    val b = base[uid] ?: Traffic.ZERO
                    val session = Traffic((t.rx - b.rx).coerceAtLeast(0), (t.tx - b.tx).coerceAtLeast(0))
                    val rate = r[uid]
                    if (session.total == 0L && rate == null) null
                    else AppLive(uid, rate?.first ?: 0L, rate?.second ?: 0L, session)
                }.sortedWith(compareByDescending<AppLive> { it.rate }.thenByDescending { it.session.total })
                measuring = false
            }
            delay(3_000)
        }
    }

    LazyColumn(contentPadding = padding) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Wi-Fi right now",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("↓ " + fmtBits(rxNow), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(20.dp))
                        Dot(MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(6.dp))
                        Text("↑ " + fmtBits(txNow), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    val peak = samples.maxOfOrNull { maxOf(it.rx, it.tx) } ?: 0L
                    Text(
                        "Peak " + fmtBits(peak),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LiveGraph(samples, Modifier.fillMaxWidth().height(170.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("60 s ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { SectionTitle("Apps using Wi-Fi") }
        if (measuring) {
            item { EmptyNote("Measuring…") }
        } else if (apps.isEmpty()) {
            item { EmptyNote("No app has used Wi-Fi since you opened this tab.") }
        }
        val maxRate = (apps.maxOfOrNull { it.rate } ?: 0L).coerceAtLeast(1L)
        items(apps, key = { it.uid }) { a ->
            LiveAppRow(a, a.rate.toFloat() / maxRate)
        }
        item {
            EmptyNote(
                "The graph updates every second. Per-app speeds update every few seconds because " +
                    "Android refreshes app counters less often. “Session” is data used since you opened this tab."
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun LiveGraph(samples: List<Traffic>, modifier: Modifier) {
    val rxC = MaterialTheme.colorScheme.primary
    val txC = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.padding(vertical = 6.dp)) {
        for (k in 0..4) {
            val y = size.height * k / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (samples.size < 2) return@Canvas
        val max = (samples.maxOfOrNull { maxOf(it.rx, it.tx) } ?: 0L).coerceAtLeast(8_000L).toFloat() * 1.1f
        val step = size.width / (WINDOW - 1)
        val offset = (WINDOW - samples.size) * step

        fun series(pick: (Traffic) -> Long, color: Color) {
            val line = Path()
            val fill = Path()
            samples.forEachIndexed { i, s ->
                val x = offset + i * step
                val y = size.height - size.height * (pick(s).toFloat() / max)
                if (i == 0) {
                    line.moveTo(x, y)
                    fill.moveTo(x, size.height)
                    fill.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }
            fill.lineTo(offset + (samples.size - 1) * step, size.height)
            fill.close()
            drawPath(fill, color.copy(alpha = 0.2f))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
        }
        series({ it.rx }, rxC)
        series({ it.tx }, txC)
    }
}

@Composable
private fun LiveAppRow(a: AppLive, fraction: Float) {
    val app = rememberApp(a.uid)
    val active = a.rate > 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                val icon = app?.icon
                if (icon != null) {
                    Image(icon, contentDescription = app?.name, modifier = Modifier.size(40.dp))
                } else {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text((app?.name ?: "?").take(1).uppercase(), fontWeight = FontWeight.Bold)
                    }
                }
                if (active) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF43A047))
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app?.name ?: "…",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (active) "↓ ${fmtBits(a.rxBps)}   ↑ ${fmtBits(a.txBps)}" else "Idle",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtBytes(a.session.total), style = MaterialTheme.typography.titleMedium)
                Text("session", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        ProportionBar(fraction, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().padding(start = 52.dp))
    }
}
