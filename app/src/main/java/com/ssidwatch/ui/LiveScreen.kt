package com.ssidwatch.ui

import android.net.TrafficStats
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssidwatch.data.Traffic
import com.ssidwatch.data.Tracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WINDOW = 60 // seconds shown on the graph

private val Palette = listOf(
    Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFE57373),
    Color(0xFFBA68C8), Color(0xFFFFF176), Color(0xFF4DB6AC), Color(0xFFF06292),
    Color(0xFF9FA8DA), Color(0xFFA1887F),
)

private data class AppLive(val uid: Int, val rxBps: Long, val txBps: Long, val session: Traffic) {
    val rate: Long get() = rxBps + txBps
}

@Composable
fun LiveScreen(padding: PaddingValues) {
    val ctx = LocalContext.current
    var rxNow by remember { mutableLongStateOf(0L) }
    var txNow by remember { mutableLongStateOf(0L) }
    val totals = remember { mutableStateListOf<Float>() } // smoothed bps, oldest first
    var lines by remember { mutableStateOf<Map<Int, List<Float>>>(emptyMap()) } // per-app bps
    var rates by remember { mutableStateOf<Map<Int, Pair<Long, Long>>>(emptyMap()) }
    var apps by remember { mutableStateOf<List<AppLive>>(emptyList()) }
    var measuring by remember { mutableStateOf(true) }
    val colorIndex = remember { HashMap<Int, Int>() }
    var colors by remember { mutableStateOf<Map<Int, Color>>(emptyMap()) }
    val shift = remember { Animatable(1f) }

    // Every second: whole-phone Wi-Fi speed (all traffic minus mobile data), lightly
    // smoothed, then split between apps using their latest measured share.
    LaunchedEffect(Unit) {
        fun wifiRx() = (TrafficStats.getTotalRxBytes().coerceAtLeast(0) -
            TrafficStats.getMobileRxBytes().coerceAtLeast(0)).coerceAtLeast(0)
        fun wifiTx() = (TrafficStats.getTotalTxBytes().coerceAtLeast(0) -
            TrafficStats.getMobileTxBytes().coerceAtLeast(0)).coerceAtLeast(0)
        val work = LinkedHashMap<Int, ArrayList<Float>>()
        var lastRx = wifiRx()
        var lastTx = wifiTx()
        var lastT = SystemClock.elapsedRealtime()
        var smooth = -1f
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

            val raw = (rxNow + txNow).toFloat()
            smooth = if (smooth < 0f) raw else 0.55f * raw + 0.45f * smooth
            totals.add(smooth)
            while (totals.size > WINDOW + 1) totals.removeAt(0)

            val r = rates
            val sum = r.values.sumOf { p -> p.first + p.second }.toFloat()
            for (uid in r.keys) if (uid !in work) work[uid] = ArrayList()
            val iter = work.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val uid = entry.key
                val list = entry.value
                val rate = r[uid]
                val share = if (rate != null && sum > 0f) (rate.first + rate.second).toFloat() / sum else 0f
                val targetValue = smooth * share
                val prevValue = list.lastOrNull() ?: 0f
                list.add(0.6f * targetValue + 0.4f * prevValue)
                while (list.size > WINDOW + 1) list.removeAt(0)
                if (rate == null && list.all { v -> v < 1f }) iter.remove()
            }
            for (uid in work.keys) if (uid !in colorIndex) colorIndex[uid] = colorIndex.size
            colors = work.keys.associateWith { uid -> Palette[(colorIndex[uid] ?: 0) % Palette.size] }
            lines = work.mapValues { e -> e.value.toList() }

            // scroll the graph smoothly by one step over the next second
            launch {
                shift.snapTo(0f)
                shift.animateTo(1f, tween(durationMillis = 1_000, easing = LinearEasing))
            }
        }
    }

    // Per-app speeds. Android refreshes per-app counters less often than the total,
    // so rates are worked out over the time between counter changes.
    LaunchedEffect(Unit) {
        val start = Tracker.liveStart()
        var first: Map<Int, Traffic>? = null
        var prev: Map<Int, Traffic> = emptyMap()
        var prevT = 0L
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
            delay(2_000)
        }
    }

    val target = ((totals.maxOrNull() ?: 0f) * 1.15f).coerceAtLeast(8_000f)
    val yMax by animateFloatAsState(target, tween(durationMillis = 700), label = "yMax")

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
                        Text("↓ " + fmtBits(rxNow), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(20.dp))
                        Text("↑ " + fmtBits(txNow), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scale " + fmtBits(yMax.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LiveGraph(
                        totals = totals,
                        lines = lines,
                        colors = colors,
                        shift = { shift.value },
                        yMax = yMax,
                        modifier = Modifier.fillMaxWidth().height(190.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("60 s ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Legend(lines.keys.toList(), colors)
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
            LiveAppRow(a, a.rate.toFloat() / maxRate, colors[a.uid] ?: MaterialTheme.colorScheme.outline)
        }
        item {
            EmptyNote(
                "Each coloured line is one app; the grey area is all Wi-Fi traffic. The total updates every " +
                    "second; each app's share is refreshed every few seconds because Android updates app " +
                    "counters less often. “Session” is data used since you opened this tab."
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Smooth, continuously scrolling graph: grey area = total, one coloured line per app. */
@Composable
private fun LiveGraph(
    totals: List<Float>,
    lines: Map<Int, List<Float>>,
    colors: Map<Int, Color>,
    shift: () -> Float,
    yMax: Float,
    modifier: Modifier,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val totalColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.padding(vertical = 6.dp)) {
        val w = size.width
        val h = size.height
        for (k in 0..4) {
            val y = h * k / 4f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        val step = w / (WINDOW - 1)
        val maxV = yMax.coerceAtLeast(1f)
        val sh = shift()

        fun points(values: List<Float>): List<Offset> {
            val n = values.size
            return values.mapIndexed { i, v ->
                val x = w - (n - 1 - i) * step + (1f - sh) * step
                val y = h - h * (v / maxV).coerceIn(0f, 1f)
                Offset(x, y)
            }
        }

        fun smoothPath(p: List<Offset>): Path {
            val path = Path()
            path.moveTo(p[0].x, p[0].y)
            for (i in 1 until p.size) {
                val a = p[i - 1]
                val b = p[i]
                val mx = (a.x + b.x) / 2f
                path.cubicTo(mx, a.y, mx, b.y, b.x, b.y)
            }
            return path
        }

        clipRect {
            if (totals.size >= 2) {
                val p = points(totals)
                val fill = smoothPath(p)
                fill.lineTo(p.last().x, h)
                fill.lineTo(p.first().x, h)
                fill.close()
                drawPath(fill, totalColor.copy(alpha = 0.14f))
                drawPath(smoothPath(p), totalColor.copy(alpha = 0.5f), style = Stroke(width = 1.5.dp.toPx()))
            }
            // biggest current users drawn last so they sit on top
            lines.entries
                .sortedBy { e -> e.value.lastOrNull() ?: 0f }
                .forEach { e ->
                    if (e.value.size >= 2) {
                        drawPath(
                            smoothPath(points(e.value)),
                            colors[e.key] ?: totalColor,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                }
        }
    }
}

@Composable
private fun Legend(uids: List<Int>, colors: Map<Int, Color>) {
    if (uids.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(uids, key = { it }) { uid ->
            val app = rememberApp(uid)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(14.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors[uid] ?: Color.Gray)
                )
                Spacer(Modifier.width(5.dp))
                val icon = app?.icon
                if (icon != null) {
                    Image(icon, contentDescription = app?.name, modifier = Modifier.size(20.dp))
                } else {
                    Text((app?.name ?: "?").take(12), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun LiveAppRow(a: AppLive, fraction: Float, color: Color) {
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
                            .background(color)
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
                    color = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtBytes(a.session.total), style = MaterialTheme.typography.titleMedium)
                Text("session", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        ProportionBar(fraction, color, Modifier.fillMaxWidth().padding(start = 52.dp))
    }
}
