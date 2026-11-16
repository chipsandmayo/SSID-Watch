package com.ssidwatch.ui

import android.net.TrafficStats
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ssidwatch.data.ConnectionInfo
import com.ssidwatch.data.IspInfo
import com.ssidwatch.data.WifiDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun fmtBits(bps: Long): String = when {
    bps < 1_000 -> "$bps bps"
    bps < 1_000_000 -> String.format("%.1f Kbps", bps / 1_000.0)
    bps < 1_000_000_000 -> String.format("%.1f Mbps", bps / 1_000_000.0)
    else -> String.format("%.2f Gbps", bps / 1_000_000_000.0)
}

private fun countryFlag(code: String?): String {
    if (code == null || code.length != 2) return ""
    val c = code.uppercase()
    if (!c.all { it in 'A'..'Z' }) return ""
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) +
        String(Character.toChars(0x1F1E6 + (c[1] - 'A'))) + " "
}

@Composable
fun ConnectionScreen(padding: PaddingValues) {
    val ctx = LocalContext.current
    var details by remember { mutableStateOf<WifiDetails?>(null) }
    var vendor by remember { mutableStateOf<String?>(null) }
    var isp by remember { mutableStateOf<IspInfo?>(null) }
    var ispLoaded by remember { mutableStateOf(false) }
    var rxRate by remember { mutableLongStateOf(0L) }
    var txRate by remember { mutableLongStateOf(0L) }
    var pingDns by remember { mutableStateOf<Double?>(null) }
    var pingGw by remember { mutableStateOf<Double?>(null) }

    // Connection details every 2 s
    LaunchedEffect(Unit) {
        while (true) {
            details = withContext(Dispatchers.IO) { ConnectionInfo.read(ctx) }
            delay(2_000)
        }
    }
    // Live receive / transfer rate every second
    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
        var lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
        var lastT = SystemClock.elapsedRealtime()
        while (true) {
            delay(1_000)
            val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
            val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastT).coerceAtLeast(1)
            rxRate = (rx - lastRx).coerceAtLeast(0) * 8_000 / dt
            txRate = (tx - lastTx).coerceAtLeast(0) * 8_000 / dt
            lastRx = rx
            lastTx = tx
            lastT = now
        }
    }
    val bssid = details?.bssid
    val lookups = Lookups.enabled
    LaunchedEffect(bssid, lookups) {
        vendor = null
        if (bssid != null && lookups) vendor = withContext(Dispatchers.IO) { ConnectionInfo.vendor(bssid) }
    }
    val ssidKey = details?.ssid
    LaunchedEffect(ssidKey, lookups) {
        isp = null
        if (ssidKey == null || !lookups) return@LaunchedEffect
        ispLoaded = false
        isp = withContext(Dispatchers.IO) { ConnectionInfo.fetchIsp() }
        ispLoaded = true
    }
    val gw = details?.gateway
    LaunchedEffect(gw) {
        while (true) {
            pingDns = withContext(Dispatchers.IO) { ConnectionInfo.ping("8.8.8.8") }
            pingGw = if (gw != null) withContext(Dispatchers.IO) { ConnectionInfo.ping(gw) } else null
            delay(5_000)
        }
    }

    val d = details
    LazyColumn(contentPadding = padding) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            InfoCard("Wi-Fi") {
                if (d == null) {
                    InfoRow("Status", "Reading…")
                } else if (!d.connected) {
                    InfoRow("Status", "Not connected to Wi-Fi")
                } else {
                    InfoRow("SSID", d.ssid)
                    InfoRow("Hidden SSID", if (d.hidden) "Yes" else "No")
                    InfoRow("BSSID", d.bssid)
                    InfoRow(
                        "Manufacturer",
                        vendor ?: if (!lookups) "Lookups off in Settings" else if (d.bssid != null) "Looking up…" else null
                    )
                    InfoRow("Standard", d.standard)
                    InfoRow("Channel", d.channel?.toString())
                    InfoRow("Frequency", d.frequency?.let { "$it MHz" })
                    InfoRow("Channel width", d.channelWidth)
                    RssiRow(d.rssi)
                    InfoRow("Security protocols", d.securityProtocols)
                    InfoRow("Security type", d.securityType)
                }
            }
        }
        item {
            InfoCard("Speed") {
                InfoRow("Receive link speed", d?.rxLink?.let { "$it Mbps" })
                InfoRow("Transmit link speed", d?.txLink?.let { "$it Mbps" })
                InfoRow("Receive rate", "↓ " + fmtBits(rxRate))
                InfoRow("Transfer rate", "↑ " + fmtBits(txRate))
            }
        }
        item {
            InfoCard("Internet service provider") {
                val i = isp
                if (i == null) {
                    InfoRow(
                        "Status",
                        when {
                            !lookups -> "Online lookups are off in Settings"
                            ispLoaded -> "Couldn't reach ipinfo.io"
                            else -> "Looking up…"
                        }
                    )
                } else {
                    InfoRow("Network name", i.name)
                    InfoRow("ASN", i.asn)
                    InfoRow("Country", i.country?.let { countryFlag(it) + it })
                    InfoRow("Region", i.region)
                    InfoRow("City", i.city)
                    InfoRow("External IP address", i.ip)
                }
            }
        }
        item {
            InfoCard("Ping") {
                InfoRow("8.8.8.8", pingDns?.let { "${it.toInt()} ms" } ?: "–")
                InfoRow("Gateway", pingGw?.let { "${it.toInt()} ms" } ?: "–")
            }
        }
        item {
            InfoCard("Network configuration") {
                InfoRow("Interface name", d?.iface)
                d?.ipv4?.forEach { InfoRow("Internal IP address", it) }
                d?.ipv6?.forEach { InfoRow("Internal IP address", it) }
                InfoRow("Subnet mask", d?.subnet)
                InfoRow("Gateway", d?.gateway)
                d?.dns?.forEachIndexed { idx, s -> InfoRow("DNS${idx + 1}", s) }
                InfoRow("DHCP server", d?.dhcp)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value ?: "–",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RssiRow(rssi: Int?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "RSSI",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (rssi == null) {
            Text("–", style = MaterialTheme.typography.bodyLarge)
        } else {
            val bg = when {
                rssi >= -60 -> Color(0xFF2E7D32)
                rssi >= -72 -> Color(0xFFF9A825)
                else -> Color(0xFFC62828)
            }
            Text(
                "$rssi dBm",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}
