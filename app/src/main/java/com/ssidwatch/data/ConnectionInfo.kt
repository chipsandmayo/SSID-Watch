package com.ssidwatch.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class WifiDetails(
    val connected: Boolean,
    val ssid: String,
    val hidden: Boolean,
    val bssid: String?,
    val standard: String?,
    val frequency: Int?,
    val channel: Int?,
    val channelWidth: String?,
    val rssi: Int?,
    val securityProtocols: String?,
    val securityType: String?,
    val rxLink: Int?,
    val txLink: Int?,
    val iface: String?,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val subnet: String?,
    val gateway: String?,
    val dns: List<String>,
    val dhcp: String?,
)

data class IspInfo(
    val ip: String?,
    val name: String?,
    val asn: String?,
    val country: String?,
    val region: String?,
    val city: String?,
)

/** Reads details about the current Wi-Fi connection, like a Wi-Fi analyser app. */
object ConnectionInfo {

    @Suppress("DEPRECATION")
    fun read(ctx: Context): WifiDetails {
        val app = ctx.applicationContext
        val wm = app.getSystemService(WifiManager::class.java)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val net = cm?.let { wifiNetwork(it) }
        val info: WifiInfo? = try {
            wm?.connectionInfo
        } catch (e: Exception) {
            null
        }
        val connected = net != null
        val bssid = info?.bssid?.takeIf { connected && it != "02:00:00:00:00:00" }?.uppercase()
        val freq = info?.frequency?.takeIf { connected && it > 0 }
        val scan: ScanResult? = try {
            if (bssid == null) null
            else wm?.scanResults?.firstOrNull { it.BSSID.equals(bssid, ignoreCase = true) }
        } catch (e: Exception) {
            null
        }

        val (protocols, type) = when {
            scan != null -> parseCapabilities(scan.capabilities ?: "")
            connected && Build.VERSION.SDK_INT >= 31 && info != null -> securityFromType(info.currentSecurityType)
            else -> null to null
        }

        val standard = if (connected && Build.VERSION.SDK_INT >= 30 && info != null) {
            when (info.wifiStandard) {
                1 -> "802.11a/b/g"
                4 -> "802.11n (Wi-Fi 4)"
                5 -> "802.11ac (Wi-Fi 5)"
                6 -> "802.11ax (Wi-Fi 6)"
                7 -> "802.11ad"
                8 -> "802.11be (Wi-Fi 7)"
                else -> null
            }
        } else null

        val width = scan?.let {
            when (it.channelWidth) {
                0 -> "20 MHz"
                1 -> "40 MHz"
                2 -> "80 MHz"
                3 -> "160 MHz"
                4 -> "80+80 MHz"
                5 -> "320 MHz"
                else -> null
            }
        }

        var rx: Int? = null
        var tx: Int? = null
        if (connected && info != null) {
            if (Build.VERSION.SDK_INT >= 29) {
                rx = info.rxLinkSpeedMbps.takeIf { it > 0 }
                tx = info.txLinkSpeedMbps.takeIf { it > 0 }
            } else {
                rx = info.linkSpeed.takeIf { it > 0 }
                tx = rx
            }
        }

        // IP configuration
        val lp = try {
            net?.let { cm?.getLinkProperties(it) }
        } catch (e: Exception) {
            null
        }
        val v4 = lp?.linkAddresses?.filter { it.address is Inet4Address } ?: emptyList()
        val v6 = lp?.linkAddresses?.filter { it.address is Inet6Address } ?: emptyList()
        val gateway = lp?.routes
            ?.firstOrNull { r ->
                val g = r.gateway
                g is Inet4Address && !g.isAnyLocalAddress && r.destination.prefixLength == 0
            }?.gateway?.hostAddress
        val dhcp = if (Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress else null

        return WifiDetails(
            connected = connected,
            ssid = if (connected) Tracker.cleanSsid(info?.ssid) else "Not connected",
            hidden = connected && (info?.hiddenSSID ?: false),
            bssid = bssid,
            standard = standard,
            frequency = freq,
            channel = freq?.let { channelOf(it) },
            channelWidth = width,
            rssi = info?.rssi?.takeIf { connected && it > -127 && it < 0 },
            securityProtocols = protocols,
            securityType = type,
            rxLink = rx,
            txLink = tx,
            iface = lp?.interfaceName,
            ipv4 = v4.mapNotNull { it.address.hostAddress },
            ipv6 = v6.mapNotNull { it.address.hostAddress?.substringBefore('%') },
            subnet = v4.firstOrNull()?.prefixLength?.let { prefixToMask(it) },
            gateway = gateway,
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') } ?: emptyList(),
            dhcp = dhcp,
        )
    }

    @Suppress("DEPRECATION")
    private fun wifiNetwork(cm: ConnectivityManager): Network? {
        fun isWifi(n: Network): Boolean {
            val caps = cm.getNetworkCapabilities(n) ?: return false
            // skip VPNs (including the packet capture), which also report the Wi-Fi transport
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        cm.activeNetwork?.let { if (isWifi(it)) return it }
        return cm.allNetworks.firstOrNull { isWifi(it) }
    }

    fun channelOf(f: Int): Int? = when (f) {
        2484 -> 14
        in 2412..2472 -> (f - 2407) / 5
        in 5160..5885 -> (f - 5000) / 5
        in 5955..7115 -> (f - 5950) / 5
        else -> null
    }

    private fun prefixToMask(p: Int): String {
        val m = if (p <= 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return "${(m shr 24) and 255}.${(m shr 16) and 255}.${(m shr 8) and 255}.${m and 255}"
    }

    /** "[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]" -> ("WPA2/WPA3", "PSK/SAE") */
    private fun parseCapabilities(caps: String): Pair<String?, String?> {
        val c = caps.uppercase()
        val protocols = ArrayList<String>()
        if (c.contains("[WEP")) protocols += "WEP"
        if (c.contains("[WPA-")) protocols += "WPA"
        if (c.contains("WPA2") || c.contains("[RSN-PSK") || c.contains("[RSN-EAP")) protocols += "WPA2"
        if (c.contains("SAE") || c.contains("WPA3") || c.contains("OWE")) protocols += "WPA3"
        val types = ArrayList<String>()
        if (c.contains("PSK")) types += "PSK"
        if (c.contains("SAE")) types += "SAE"
        if (c.contains("EAP")) types += "EAP"
        if (c.contains("OWE")) types += "OWE"
        if (protocols.isEmpty() && types.isEmpty()) return "None" to "Open"
        return protocols.distinct().joinToString("/").ifEmpty { null } to
            types.distinct().joinToString("/").ifEmpty { null }
    }

    private fun securityFromType(t: Int): Pair<String?, String?> = when (t) {
        0 -> "None" to "Open"
        1 -> "WEP" to "WEP"
        2 -> "WPA/WPA2" to "PSK"
        3 -> "WPA2" to "EAP"
        4 -> "WPA3" to "SAE"
        5, 9 -> "WPA3" to "EAP"
        6 -> "WPA3" to "OWE"
        else -> null to null
    }

    // ---- online lookups --------------------------------------------------------

    private val vendorCache = ConcurrentHashMap<String, String>()

    /** Router manufacturer from the BSSID's first three bytes (looked up online once, then cached). */
    fun vendor(bssid: String): String? {
        val first = bssid.take(2).toIntOrNull(16) ?: return null
        if ((first and 0x02) != 0) return "Unknown (randomised MAC)"
        val oui = bssid.uppercase().take(8)
        vendorCache[oui]?.let { return it }
        val text = httpGet("https://api.macvendors.com/$oui") ?: return null
        val v = text.trim()
        if (v.isEmpty() || v.startsWith("{")) return null
        vendorCache[oui] = v
        return v
    }

    /** Public IP, provider, ASN and rough location from ipinfo.io. */
    fun fetchIsp(): IspInfo? {
        val text = httpGet("https://ipinfo.io/json") ?: return null
        return try {
            val j = JSONObject(text)
            val org = j.optString("org")
            val asn = org.substringBefore(' ').takeIf { it.startsWith("AS") }
            val name = if (asn != null) org.substringAfter(' ', "") else org
            IspInfo(
                ip = j.optString("ip").ifEmpty { null },
                name = name.ifEmpty { null },
                asn = asn,
                country = j.optString("country").ifEmpty { null },
                region = j.optString("region").ifEmpty { null },
                city = j.optString("city").ifEmpty { null },
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("User-Agent", "SSIDWatch")
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** Round-trip time in ms using the system ping tool, or null if it fails. */
    fun ping(host: String): Double? = try {
        val p = ProcessBuilder("ping", "-c", "1", "-W", "2", host).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        Regex("time[=<]\\s*([\\d.]+)").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
    } catch (e: Exception) {
        null
    }
}
