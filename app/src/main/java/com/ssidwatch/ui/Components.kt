package com.ssidwatch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssidwatch.data.AppLabel
import com.ssidwatch.data.Apps
import com.ssidwatch.data.Traffic
import com.ssidwatch.data.Tracker
import com.ssidwatch.data.UsageDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp)
    )
}

@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun TotalsCard(label: String, t: Traffic) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                fmtBytes(t.total),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Download ${fmtBytes(t.rx)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(18.dp))
                Dot(MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(6.dp))
                Text("Upload ${fmtBytes(t.tx)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Rounded-top bar shape (flat at the bottom). */
private fun topRoundedPath(left: Float, top: Float, right: Float, bottom: Float, r: Float): Path {
    val rr = r.coerceAtMost((right - left) / 2f).coerceAtMost((bottom - top).coerceAtLeast(0f))
    return Path().apply {
        addRoundRect(
            RoundRect(
                left, top, right, bottom,
                topLeftCornerRadius = CornerRadius(rr),
                topRightCornerRadius = CornerRadius(rr),
                bottomRightCornerRadius = CornerRadius.Zero,
                bottomLeftCornerRadius = CornerRadius.Zero,
            )
        )
    }
}

/** Faint diagonal stripes across the current clip, for a bit of texture. */
private fun DrawScope.stripes(left: Float, top: Float, right: Float, bottom: Float, spacing: Float, color: Color, width: Float) {
    val h = bottom - top
    var d = -h
    while (d < right - left) {
        drawLine(color, Offset(left + d, bottom), Offset(left + d + h, top), strokeWidth = width)
        d += spacing
    }
}

/**
 * Stacked download (bottom) / upload (top) bar chart with rounded, gradient-filled,
 * lightly textured bars over faint track bars. Bars grow in when first shown and glide
 * to new values when the data changes.
 */
@Composable
fun StackedBars(
    values: List<Traffic>,
    modifier: Modifier = Modifier,
    highlight: Int = -1,
    onTap: ((Int) -> Unit)? = null,
) {
    val rxC = MaterialTheme.colorScheme.primary
    val txC = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val track = MaterialTheme.colorScheme.surfaceVariant
    val gap = MaterialTheme.colorScheme.background
    val max = (values.maxOfOrNull { it.total } ?: 0L).coerceAtLeast(1L).toFloat()

    // fractions of the chart height, animated towards the latest data
    val rxF = values.mapIndexed { i, t ->
        animateFloatAsState(t.rx / max, tween(durationMillis = 600, easing = FastOutSlowInEasing), label = "rx$i").value
    }
    val txF = values.mapIndexed { i, t ->
        animateFloatAsState(t.tx / max, tween(durationMillis = 600, easing = FastOutSlowInEasing), label = "tx$i").value
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing)) }

    val currentTap by rememberUpdatedState(onTap)
    val count = values.size
    val tapMod = if (onTap != null && count > 0) {
        Modifier.pointerInput(count) {
            detectTapGestures { o ->
                val i = (o.x / size.width * count).toInt().coerceIn(0, count - 1)
                currentTap?.invoke(i)
            }
        }
    } else Modifier

    Canvas(modifier.then(tapMod)) {
        val n = values.size
        if (n == 0) return@Canvas
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        for (k in 1..3) {
            val y = size.height * k / 4f
            drawLine(grid.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = dash)
        }

        val slot = size.width / n
        val barW = (slot * 0.62f).coerceAtLeast(1.5f)
        val radius = (barW / 2f).coerceAtMost(6.dp.toPx())
        val stripeGap = 6.dp.toPx()
        val stripeW = 1.2.dp.toPx()
        val sep = 1.5.dp.toPx()
        val bottom = size.height
        val a0 = appear.value

        for (i in 0 until n) {
            val x = i * slot + (slot - barW) / 2f
            val dim = highlight >= 0 && highlight != i
            // ghost track
            drawPath(topRoundedPath(x, 0f, x + barW, bottom, radius), track.copy(alpha = if (dim) 0.18f else 0.35f))

            // staggered grow-in on first show
            val p = ((a0 * 1.5f) - (i.toFloat() / n) * 0.5f).coerceIn(0f, 1f)
            val hRx = bottom * rxF.getOrElse(i) { 0f } * p
            val hTx = bottom * txF.getOrElse(i) { 0f } * p
            val hTot = hRx + hTx
            if (hTot < 0.5f) continue
            val top = bottom - hTot
            val alpha = if (dim) 0.45f else 1f

            clipPath(topRoundedPath(x, top, x + barW, bottom, radius)) {
                if (hRx > 0f) {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(lerp(rxC, Color.White, 0.22f).copy(alpha = alpha), rxC.copy(alpha = alpha * 0.85f)),
                            startY = bottom - hRx, endY = bottom
                        ),
                        topLeft = Offset(x, bottom - hRx),
                        size = Size(barW, hRx)
                    )
                }
                if (hTx > 0f) {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(lerp(txC, Color.White, 0.22f).copy(alpha = alpha), txC.copy(alpha = alpha * 0.85f)),
                            startY = top, endY = top + hTx
                        ),
                        topLeft = Offset(x, top),
                        size = Size(barW, hTx)
                    )
                    if (hRx > sep * 2) {
                        drawLine(gap.copy(alpha = 0.7f), Offset(x, bottom - hRx), Offset(x + barW, bottom - hRx), strokeWidth = sep)
                    }
                }
                if (barW >= 6.dp.toPx()) {
                    stripes(x, top, x + barW, bottom, stripeGap, Color.White.copy(alpha = 0.07f * alpha), stripeW)
                }
                // glossy edge along the top
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.28f * alpha), Color.Transparent),
                        startY = top, endY = top + (hTot * 0.35f).coerceAtMost(14.dp.toPx())
                    ),
                    topLeft = Offset(x, top),
                    size = Size(barW, hTot)
                )
            }
        }
        drawLine(grid, Offset(0f, bottom - 0.5f), Offset(size.width, bottom - 0.5f), strokeWidth = 1.5f)
    }
}

@Composable
fun AxisLabels(labels: List<String>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        labels.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NetworkChips(networks: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All networks") })
        }
        items(networks) { n ->
            FilterChip(selected = selected == n, onClick = { onSelect(n) }, label = { Text(n) })
        }
    }
}

/** Pill-shaped usage bar with gradient, light texture and a grow animation. */
@Composable
fun ProportionBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val target = fraction.coerceIn(0f, 1f)
    val anim by animateFloatAsState(target, tween(durationMillis = 700, easing = FastOutSlowInEasing), label = "bar")
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 800, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier.height(7.dp)) {
        val h = size.height
        val r = h / 2f
        drawRoundRect(track, cornerRadius = CornerRadius(r))
        val w = size.width * anim * appear.value
        if (w <= 0.5f) return@Canvas
        val wd = w.coerceAtLeast(h)
        val pill = Path().apply { addRoundRect(RoundRect(0f, 0f, wd, h, CornerRadius(r))) }
        clipPath(pill) {
            drawRect(
                Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color), startX = 0f, endX = wd),
                size = Size(wd, h)
            )
            stripes(0f, 0f, wd, h, 6.dp.toPx(), Color.White.copy(alpha = 0.12f), 1.5.dp.toPx())
            drawRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent), startY = 0f, endY = h * 0.6f),
                size = Size(wd, h)
            )
        }
    }
}

@Composable
fun UsageRow(
    title: String,
    t: Traffic,
    fraction: Float,
    icon: ImageBitmap? = null,
    italic: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingNote: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            } else {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(title.take(1).uppercase(), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "↓ ${fmtBytes(t.rx)}   ↑ ${fmtBytes(t.tx)}" + (trailingNote?.let { "   $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(fmtBytes(t.total), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        ProportionBar(fraction, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().padding(start = 48.dp))
    }
}

@Composable
fun rememberApp(uid: Int): AppLabel? {
    val ctx = LocalContext.current
    val app by produceState<AppLabel?>(null, uid) {
        value = withContext(Dispatchers.IO) { Apps.get(ctx, uid) }
    }
    return app
}

/** One app row; tapping it reveals how its usage splits across Wi-Fi networks. */
@Composable
fun AppRow(
    uid: Int,
    t: Traffic,
    fraction: Float,
    from: String,
    to: String,
    expanded: Boolean,
    showNetworks: Boolean,
    tick: Int,
    onToggle: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = rememberApp(uid)
    Column {
        UsageRow(
            title = app?.name ?: "…",
            t = t,
            fraction = fraction,
            icon = app?.icon,
            onClick = if (showNetworks) onToggle else null
        )
        if (expanded && showNetworks) {
            val split by produceState(emptyList<Pair<String, Traffic>>(), uid, from, to, tick) {
                value = withContext(Dispatchers.IO) { UsageDb.get(ctx).appSsids(from, to, uid) }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 64.dp, end = 16.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                split.forEach { (net, nt) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displayNetwork(net),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = if (isSpecialNetwork(net)) FontStyle.Italic else FontStyle.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "↓ ${fmtBytes(nt.rx)}  ↑ ${fmtBytes(nt.tx)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

fun isSpecialNetwork(name: String) = name == Tracker.BEFORE || name == Tracker.UNKNOWN

/** Past usage imported from Android has no Wi-Fi name; show it as such rather than as a network. */
fun displayNetwork(name: String) = if (name == Tracker.BEFORE) "Network not recorded" else name

const val NOT_RECORDED_NOTE =
    "Android keeps each app's past Wi-Fi usage but not the Wi-Fi name, so network names are only " +
        "known from when SSID Watch was installed."
