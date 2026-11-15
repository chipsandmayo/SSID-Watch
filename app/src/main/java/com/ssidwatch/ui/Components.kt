package com.ssidwatch.ui

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

/** Stacked download (bottom) / upload (top) bar chart. */
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
    val max = (values.maxOfOrNull { it.total } ?: 0L).coerceAtLeast(1L).toFloat()
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
        for (k in 1..3) {
            val y = size.height * k / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        val slot = size.width / n
        val barW = (slot * 0.68f).coerceAtLeast(1f)
        values.forEachIndexed { i, t ->
            val x = i * slot + (slot - barW) / 2f
            val hRx = size.height * (t.rx.toFloat() / max)
            val hTx = size.height * (t.tx.toFloat() / max)
            val a = if (highlight >= 0 && highlight != i) 0.4f else 1f
            if (hRx > 0f) drawRect(rxC.copy(alpha = a), Offset(x, size.height - hRx), Size(barW, hRx))
            if (hTx > 0f) drawRect(txC.copy(alpha = a), Offset(x, size.height - hRx - hTx), Size(barW, hTx))
        }
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

@Composable
fun ProportionBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(color)
        )
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
                            net,
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
