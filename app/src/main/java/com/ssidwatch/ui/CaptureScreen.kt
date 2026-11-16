package com.ssidwatch.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ssidwatch.capture.CaptureLog
import com.ssidwatch.capture.CaptureVpnService
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FILTERS = listOf("All", "TCP", "UDP", "DNS", "TLS", "HTTP", "QUIC", "ICMP")
private val F_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun protoColor(proto: String, info: String): Color = when {
    info.contains("RST") -> Color(0xFFE57373)
    proto == "TCP" -> Color(0xFF9575CD)
    proto == "UDP" -> Color(0xFF64B5F6)
    proto == "DNS" || proto == "MDNS" -> Color(0xFF4FC3F7)
    proto == "TLS" -> Color(0xFF81C784)
    proto == "HTTP" -> Color(0xFFAED581)
    proto == "QUIC" -> Color(0xFFFFB74D)
    proto.startsWith("ICMP") -> Color(0xFFF06292)
    else -> Color(0xFF90A4AE)
}

private fun hexDump(b: ByteArray): String {
    val sb = StringBuilder()
    var i = 0
    while (i < b.size) {
        sb.append(String.format("%04x  ", i))
        for (j in 0 until 16) {
            if (i + j < b.size) sb.append(String.format("%02x ", b[i + j].toInt() and 0xFF)) else sb.append("   ")
            if (j == 7) sb.append(' ')
        }
        sb.append(' ')
        for (j in 0 until 16) {
            if (i + j >= b.size) break
            val c = b[i + j].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        sb.append('\n')
        i += 16
    }
    return sb.toString().trimEnd()
}

@Composable
fun CaptureScreen(padding: PaddingValues) {
    val ctx = LocalContext.current
    val running by CaptureLog.running.collectAsState()
    var entries by remember { mutableStateOf<List<CaptureLog.Entry>>(emptyList()) }
    var filter by remember { mutableStateOf("All") }
    var follow by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var packets by remember { mutableStateOf(0L) }
    var bytes by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            CaptureVpnService.start(ctx)
            note = null
        } else {
            note = "Capture needs permission to set up the local VPN."
        }
    }

    LaunchedEffect(running) {
        while (true) {
            entries = CaptureLog.snapshot()
            packets = CaptureLog.packets.get()
            bytes = CaptureLog.bytes.get()
            CaptureLog.lastError?.let { note = it }
            if (!running) break
            delay(500)
        }
    }

    val shown = remember(entries, filter) {
        if (filter == "All") entries
        else entries.filter { it.proto == filter || (filter == "ICMP" && it.proto.startsWith("ICMP")) || (filter == "DNS" && it.proto == "MDNS") }
    }

    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (running) {
                                CaptureVpnService.stop(ctx)
                            } else {
                                val prep = try {
                                    VpnService.prepare(ctx)
                                } catch (e: Exception) {
                                    null
                                }
                                if (prep != null) launcher.launch(prep) else CaptureVpnService.start(ctx)
                            }
                        },
                        colors = if (running) {
                            ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White)
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Text(if (running) "■  Stop" else "▶  Start", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) "Capturing" else if (packets > 0) "Stopped" else "Not capturing",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (running) Color(0xFF43A047) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "$packets packets · ${fmtBytes(bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = {
                        CaptureLog.clear()
                        entries = emptyList()
                        packets = 0
                        bytes = 0
                        expanded = null
                    }) { Text("Clear") }
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
                    }
                    items(FILTERS) { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                    }
                }
                note?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (!running && packets == 0L) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Start sets up a local VPN on the phone so SSID Watch can see each packet (a key icon " +
                            "appears). Nothing is sent anywhere else. Internet may be a little slower while capturing, " +
                            "and another VPN can't run at the same time. Tap Stop to return to normal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // the scroll box
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            if (shown.isEmpty()) {
                Text(
                    if (running) "Waiting for packets…" else "No packets yet. Tap Start.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.no }) { e ->
                    PacketRow(
                        e = e,
                        expanded = expanded == e.no,
                        onClick = {
                            expanded = if (expanded == e.no) null else e.no
                            follow = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PacketRow(e: CaptureLog.Entry, expanded: Boolean, onClick: () -> Unit) {
    val app = if (e.uid >= 0) rememberApp(e.uid) else null
    val tint = protoColor(e.proto, e.info)
    val mono = FontFamily.Monospace
    val rel = (e.time - CaptureLog.startedAt).coerceAtLeast(0) / 1000.0
    Column(
        Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = if (expanded) 0.22f else 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.no.toString(),
                fontFamily = mono,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp)
            )
            Text(
                String.format("%.3f", rel),
                fontFamily = mono,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp)
            )
            Text(
                if (e.outgoing) "↑" else "↓",
                fontSize = 12.sp,
                color = if (e.outgoing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(14.dp)
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(tint.copy(alpha = 0.85f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(e.proto, fontFamily = mono, fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
            Text("${e.length} B", fontFamily = mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            val icon = app?.icon
            if (icon != null) Image(icon, contentDescription = app?.name, modifier = Modifier.size(16.dp))
        }
        Text(
            "${e.src} → ${e.dst}",
            fontFamily = mono,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            e.info,
            fontFamily = mono,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) 6 else 1,
            overflow = TextOverflow.Ellipsis
        )
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            val time = Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()).format(F_TIME)
            val appName = when {
                e.uid < 0 -> "Unknown"
                app == null -> "…"
                else -> app.name + (app.packageName?.let { " ($it)" } ?: "")
            }
            Detail("Time", time)
            Detail("Direction", if (e.outgoing) "Sent from phone" else "Received by phone")
            Detail("App", appName)
            Detail("Source", e.src)
            Detail("Destination", e.dst)
            Detail("Protocol", e.proto)
            Detail("Length", "${e.length} bytes")
            Spacer(Modifier.height(6.dp))
            Text(
                hexDump(e.head) + if (e.length > e.head.size) "\n… (first ${e.head.size} of ${e.length} bytes)" else "",
                fontFamily = mono,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(6.dp)
            )
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
