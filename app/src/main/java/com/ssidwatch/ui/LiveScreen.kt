package com.ssidwatch.ui

import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ssidwatch.MonitorService
import com.ssidwatch.data.LiveRecorder
import com.ssidwatch.data.Traffic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlin.math.abs

private const val MIN_SPAN = 30_000L
private const val MAX_SPAN = 7L * 24 * 60 * 60 * 1000
private const val DEFAULT_SPAN = 2L * 60 * 1000
private const val LIVE_LAG = 1_500L

private val Palette = listOf(
    Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFE57373),
    Color(0xFFBA68C8), Color(0xFFFFF176), Color(0xFF4DB6AC), Color(0xFFF06292),
    Color(0xFF9FA8DA), Color(0xFFA1887F),
)

private val TICKS = longArrayOf(
    1_000, 2_000, 5_000, 10_000, 15_000, 30_000,
    60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000,
    3_600_000, 7_200_000, 10_800_000, 21_600_000, 43_200_000, 86_400_000, 172_800_000,
)

private val F_SEC = DateTimeFormatter.ofPattern("HH:mm:ss")
private val F_MIN = DateTimeFormatter.ofPattern("HH:mm")
private val F_DAY = DateTimeFormatter.ofPattern("d MMM")
private val F_FULL = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss")

private fun tickInterval(span: Long): Long = TICKS.firstOrNull { span / it <= 5 } ?: TICKS.last()

private fun tickLabel(t: Long, iv: Long): String {
    val z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
    return when {
        iv < 60_000 -> z.format(F_SEC)
        iv < 86_400_000 -> if (z.hour == 0 && z.minute == 0) z.format(F_DAY) else z.format(F_MIN)
        else -> z.format(F_DAY)
    }
}

private fun rangeLabel(start: Long, end: Long): String {
    val zone = ZoneId.systemDefault()
    val s = Instant.ofEpochMilli(start).atZone(zone)
    val e = Instant.ofEpochMilli(end).atZone(zone)
    val today = LocalDate.now(zone)
    fun day(d: LocalDate) = when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(F_DAY)
    }
    return if (s.toLocalDate() == e.toLocalDate()) {
        "${day(s.toLocalDate())} ${s.format(F_SEC)} – ${e.format(F_SEC)}"
    } else {
        "${day(s.toLocalDate())} ${s.format(F_MIN)} – ${day(e.toLocalDate())} ${e.format(F_MIN)}"
    }
}

private fun spanLabel(span: Long): String = when {
    span < 60_000 -> "${span / 1000} s"
    span < 3_600_000 -> "${Math.round(span / 60_000.0)} min"
    span < 86_400_000 -> String.format("%.1f h", span / 3_600_000.0).replace(".0 h", " h")
    else -> String.format("%.1f days", span / 86_400_000.0).replace(".0 days", " days")
}

private fun maxInView(d: GraphData, start: Long, end: Long): Float {
    var m = 0f
    for (i in 0 until d.size) {
        val bs = d.from + i * d.bucket
        if (bs + d.bucket < start || bs > end) continue
        val v = d.rx[i] + d.tx[i]
        if (v > m) m = v
    }
    return m
}

@Composable
fun LiveScreen(padding: PaddingValues) {
    val ctx = LocalContext.current
    var live by remember { mutableStateOf(true) }
    var span by remember { mutableLongStateOf(DEFAULT_SPAN) }
    var end by remember { mutableLongStateOf(System.currentTimeMillis() - LIVE_LAG) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var data by remember { mutableStateOf<GraphData?>(null) }
    var selT by remember { mutableStateOf<Long?>(null) }
    var focusUid by remember { mutableStateOf<Int?>(null) }
    var overlayOn by remember { mutableStateOf(MonitorService.isOverlayOn(ctx)) }
    var note by remember { mutableStateOf<String?>(null) }
    val latest by LiveRecorder.latest.collectAsState()
    val colorIndex = remember { HashMap<Int, Int>() }

    // Frame clock: keeps the graph gliding smoothly while following live.
    LaunchedEffect(live) {
        while (live) {
            withFrameMillis { }
            nowMs = System.currentTimeMillis()
        }
    }

    // Load data for the current view; keep refreshing while live.
    val bucketKey = if (live) 0L else end / maxOf(1_000L, span / 240)
    LaunchedEffect(live, span, bucketKey) {
        delay(40)
        while (true) {
            val e = if (live) System.currentTimeMillis() - LIVE_LAG else end
            data = withContext(Dispatchers.IO) { loadGraph(ctx, e - span, e) }
            if (!live) break
            delay((span / 240).coerceIn(1_000L, 10_000L))
        }
    }

    // Keep the pop-out button in step if the box is closed by long-press.
    LaunchedEffect(Unit) {
        while (true) {
            overlayOn = MonitorService.isOverlayOn(ctx)
            delay(2_000)
        }
    }

    fun onTransform(cx: Float, panX: Float, zoom: Float, width: Float) {
        if (width <= 0f) return
        val now = System.currentTimeMillis()
        val liveEnd = now - LIVE_LAG
        val curEnd = if (live) liveEnd else end
        val newSpan = (span / zoom).toLong().coerceIn(MIN_SPAN, MAX_SPAN)
        if (live) {
            span = newSpan
            if (panX > 1f) {
                live = false
                end = liveEnd - (panX / width * newSpan).toLong()
            }
            return
        }
        val anchor = curEnd - span + (cx / width * span).toLong()
        var newEnd = anchor + ((1f - cx / width) * newSpan).toLong()
        newEnd -= (panX / width * newSpan).toLong()
        span = newSpan
        if (newEnd >= liveEnd) {
            live = true
        } else {
            end = newEnd.coerceAtLeast(now - MAX_SPAN)
        }
    }

    val d = data
    val viewEndC = if (live) System.currentTimeMillis() - LIVE_LAG else end
    val target = d?.let { maxInView(it, viewEndC - span, viewEndC) } ?: 0f
    val yMax by animateFloatAsState((target * 1.15f).coerceAtLeast(8_000f), tween(durationMillis = 500), label = "yMax")
    val colors: Map<Int, Color> = d?.let { dd ->
        (dd.apps.map { it.first } + dd.appTotals.map { it.first }).distinct().associateWith { uid ->
            Palette[colorIndex.getOrPut(uid) { colorIndex.size } % Palette.size]
        }
    } ?: emptyMap()

    LazyColumn(contentPadding = padding) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Wi-Fi right now",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "↓ " + fmtBits(LiveRecorder.bps(latest.rx, latest.dur)),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(
                            "↑ " + fmtBits(LiveRecorder.bps(latest.tx, latest.dur)),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (live) "Live · last ${spanLabel(span)}" else rangeLabel(end - span, end),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Scale " + fmtBits(yMax.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    GraphCanvas(
                        data = d,
                        span = span,
                        viewEnd = { if (live) nowMs - LIVE_LAG else end },
                        yMax = yMax,
                        colors = colors,
                        selT = selT,
                        focusUid = focusUid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .pointerInput(Unit) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    onTransform(centroid.x, pan.x, zoom, size.width.toFloat())
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { o ->
                                        val w = size.width.toFloat()
                                        val vEnd = if (live) System.currentTimeMillis() - LIVE_LAG else end
                                        val t = vEnd - span + (o.x / w * span).toLong()
                                        val cur = selT
                                        selT = if (cur != null && abs(cur - t) < span / 25) null else t
                                    },
                                    onDoubleTap = {
                                        live = true
                                        span = DEFAULT_SPAN
                                        selT = null
                                    }
                                )
                            }
                    )
                    val shownApps = d?.apps?.map { it.first }.orEmpty()
                    if (focusUid != null && focusUid !in shownApps) focusUid = null
                    if (shownApps.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        AppIconLegend(
                            uids = shownApps,
                            colors = colors,
                            focusUid = focusUid,
                            onTap = { uid -> focusUid = if (focusUid == uid) null else uid }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { span = (span * 2).coerceAtMost(MAX_SPAN) }) { Text("−") }
                        OutlinedButton(onClick = { span = (span / 2).coerceAtLeast(MIN_SPAN) }) { Text("+") }
                        FilledTonalButton(
                            onClick = {
                                live = true
                                selT = null
                            },
                            enabled = !live
                        ) { Text("Live") }
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(onClick = {
                            when {
                                overlayOn -> {
                                    MonitorService.setOverlay(ctx, false)
                                    overlayOn = false
                                    note = null
                                }
                                !Settings.canDrawOverlays(ctx) -> {
                                    note = "Allow “Display over other apps” for SSID Watch, then tap Pop out again."
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:" + ctx.packageName)
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                                else -> {
                                    MonitorService.setOverlay(ctx, true)
                                    overlayOn = true
                                    note = "Floating box on. Drag it to move it, tap it to open SSID Watch, long-press it to close it."
                                }
                            }
                        }) { Text(if (overlayOn) "Close pop-out" else "Pop out") }
                    }
                    note?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            EmptyNote(
                "Drag the graph to go back in time, pinch to zoom (30 s to 7 days), tap for exact values, " +
                    "double-tap to return to live. Recording runs all the time and keeps 7 days."
            )
        }
        item { SectionTitle(if (live) "Apps in the last ${spanLabel(span)}" else "Apps in this view") }
        if (d == null) {
            item { EmptyNote("Loading…") }
        } else if (d.appTotals.isEmpty()) {
            item { EmptyNote("No app used Wi-Fi in this time range.") }
        }
        if (d != null) {
            val maxTotal = (d.appTotals.maxOfOrNull { it.second.total } ?: 0L).coerceAtLeast(1L)
            items(d.appTotals, key = { it.first }) { (uid, t) ->
                LiveAppRow(
                    uid = uid,
                    t = t,
                    fraction = t.total.toFloat() / maxTotal,
                    color = colors[uid] ?: MaterialTheme.colorScheme.outline,
                    activeNow = live && uid in d.active
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun GraphCanvas(
    data: GraphData?,
    span: Long,
    viewEnd: () -> Long,
    yMax: Float,
    colors: Map<Int, Color>,
    selT: Long?,
    focusUid: Int?,
    modifier: Modifier,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val totalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tipBg = MaterialTheme.colorScheme.surface
    val tipFg = MaterialTheme.colorScheme.onSurface
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    Canvas(modifier) {
        val w = size.width
        val axisH = 18.dp.toPx()
        val h = size.height - axisH
        val vEnd = viewEnd()
        val vStart = vEnd - span
        val maxV = yMax.coerceAtLeast(1f)
        val native = drawContext.canvas.nativeCanvas

        for (k in 0..4) {
            val y = h * k / 4f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        // time axis
        paint.textSize = 10.sp.toPx()
        paint.color = labelColor.toArgb()
        paint.textAlign = Paint.Align.CENTER
        val iv = tickInterval(span)
        val off = TimeZone.getDefault().getOffset(vStart).toLong()
        var t = ((vStart + off) / iv + 1) * iv - off
        while (t <= vEnd) {
            val x = (t - vStart).toFloat() / span * w
            drawLine(grid.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            native.drawText(tickLabel(t, iv), x, size.height - 4.dp.toPx(), paint)
            t += iv
        }

        val d = data ?: return@Canvas
        val b = d.bucket
        fun xOf(time: Long) = (time - vStart).toFloat() / span * w
        fun yOf(v: Float) = h - h * (v / maxV).coerceIn(0f, 1f)

        val lastComplete = ((d.dataUntil - d.from) / b).toInt() - 1
        val i0 = ((vStart - d.from) / b - 1).toInt().coerceAtLeast(0)
        val i1 = minOf(((vEnd - d.from) / b + 1).toInt(), d.size - 1, lastComplete)

        fun smoothPath(p: List<Offset>): Path {
            val path = Path()
            path.moveTo(p[0].x, p[0].y)
            for (i in 1 until p.size) {
                val a = p[i - 1]
                val c = p[i]
                val mx = (a.x + c.x) / 2f
                path.cubicTo(mx, a.y, mx, c.y, c.x, c.y)
            }
            return path
        }

        if (i1 - i0 >= 1) {
            fun pts(get: (Int) -> Float) = (i0..i1).map { i -> Offset(xOf(d.from + i * b + b / 2), yOf(get(i))) }
            clipRect(0f, 0f, w, h) {
                val tp = pts { i -> d.rx[i] + d.tx[i] }
                val fill = smoothPath(tp)
                fill.lineTo(tp.last().x, h)
                fill.lineTo(tp.first().x, h)
                fill.close()
                drawPath(fill, totalColor.copy(alpha = 0.14f))
                drawPath(smoothPath(tp), totalColor.copy(alpha = 0.5f), style = Stroke(width = 1.5.dp.toPx()))
                // the focused app (if any) is drawn last, thicker, with the others faded
                d.apps.asReversed().sortedBy { it.first == focusUid }.forEach { (uid, arr) ->
                    val dimmed = focusUid != null && uid != focusUid
                    val c = colors[uid] ?: totalColor
                    drawPath(
                        smoothPath(pts { i -> arr[i] }),
                        if (dimmed) c.copy(alpha = 0.18f) else c,
                        style = Stroke(width = (if (uid == focusUid) 3.5 else 2.5).dp.toPx())
                    )
                }
            }
        }

        // tapped point: marker line + values box
        val st = selT
        if (st != null && st in vStart..vEnd) {
            val x = xOf(st)
            drawLine(tipFg.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5.dp.toPx())
            val i = ((st - d.from) / b).toInt()
            if (i in 0 until d.size) {
                val lines = listOf(
                    Instant.ofEpochMilli(st).atZone(ZoneId.systemDefault()).format(F_FULL),
                    "↓ " + fmtBits(d.rx[i].toLong()) + "   ↑ " + fmtBits(d.tx[i].toLong()),
                )
                paint.textSize = 11.sp.toPx()
                paint.textAlign = Paint.Align.LEFT
                val pad = 8.dp.toPx()
                val lineH = 15.sp.toPx()
                val boxW = lines.maxOf { paint.measureText(it) } + pad * 2
                val boxH = lineH * lines.size + pad * 1.5f
                val bx = if (x + 10.dp.toPx() + boxW < w) x + 10.dp.toPx() else (x - 10.dp.toPx() - boxW).coerceAtLeast(0f)
                val by = 6.dp.toPx()
                drawRoundRect(
                    tipBg.copy(alpha = 0.94f),
                    topLeft = Offset(bx, by),
                    size = Size(boxW, boxH),
                    cornerRadius = CornerRadius(8.dp.toPx())
                )
                paint.color = tipFg.toArgb()
                lines.forEachIndexed { k, s ->
                    native.drawText(s, bx + pad, by + pad + lineH * (k + 1) - 4.dp.toPx(), paint)
                }
            }
        }
    }
}

/** App icons for the lines on the graph, each underlined in its line colour. Tap to focus a line. */
@Composable
private fun AppIconLegend(uids: List<Int>, colors: Map<Int, Color>, focusUid: Int?, onTap: (Int) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(uids, key = { it }) { uid ->
            val app = rememberApp(uid)
            val color = colors[uid] ?: Color.Gray
            val focused = focusUid == uid
            val faded = focusUid != null && !focused
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) color.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onTap(uid) }
                    .padding(horizontal = 4.dp, vertical = 3.dp)
            ) {
                val icon = app?.icon
                if (icon != null) {
                    Image(
                        icon,
                        contentDescription = app?.name,
                        alpha = if (faded) 0.35f else 1f,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text((app?.name ?: "?").take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .width(22.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (faded) color.copy(alpha = 0.3f) else color)
                )
            }
        }
    }
}

@Composable
private fun LiveAppRow(uid: Int, t: Traffic, fraction: Float, color: Color, activeNow: Boolean) {
    val app = rememberApp(uid)
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
                if (activeNow) {
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
                    "↓ ${fmtBytes(t.rx)}   ↑ ${fmtBytes(t.tx)}" + if (activeNow) "   · using data now" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(fmtBytes(t.total), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        ProportionBar(fraction, color, Modifier.fillMaxWidth().padding(start = 52.dp))
    }
}
