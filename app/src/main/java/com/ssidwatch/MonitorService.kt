package com.ssidwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ssidwatch.data.Tracker
import com.ssidwatch.data.UsageDb
import com.ssidwatch.ui.fmtBytes
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Always-on foreground service: watches which Wi-Fi network is connected and snapshots
 * per-app counters on every network change and every few minutes.
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var cm: ConnectivityManager
    private lateinit var wifi: WifiManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    // only touched on the worker thread
    private var wifiNetwork: Network? = null
    private var ssid: String? = null

    @Volatile private var title = "Watching Wi-Fi usage"
    @Volatile private var text = ""

    private val ticker = object : Runnable {
        override fun run() {
            worker.execute { sampleAndNotify(ssid) }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Missing location permission or started from background without it.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            ssid = Tracker.currentSsid(this)
            registerWifiCallback()
            handler.post(ticker)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        worker.execute { Tracker.sample(this, ssid) }
        worker.shutdown()
        super.onDestroy()
    }

    private fun registerWifiCallback() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
        } else {
            WifiCallback()
        }
        callback = cb
        try {
            cm.registerNetworkCallback(req, cb)
        } catch (e: Exception) {
            callback = null
        }
        // If we're not on Wi-Fi at all right now, record that.
        worker.execute {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (wifiNetwork == null && ssid != null) onSsid(null)
            }
        }
    }

    private inner class WifiCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(31)
        constructor(flags: Int) : super(flags)

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val name = readSsid(caps)
            worker.execute {
                wifiNetwork = network
                if (name != ssid) onSsid(name)
            }
        }

        override fun onLost(network: Network) {
            worker.execute {
                if (network == wifiNetwork) {
                    wifiNetwork = null
                    onSsid(null)
                }
            }
        }
    }

    /** Called on the worker thread when the connected SSID changes. */
    private fun onSsid(newSsid: String?) {
        sampleAndNotify(newSsid)
        ssid = newSsid
    }

    private fun sampleAndNotify(next: String?) {
        Tracker.sample(this, next)
        updateText(next)
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }

    @Suppress("DEPRECATION")
    private fun readSsid(caps: NetworkCapabilities?): String {
        var raw: String? = null
        if (Build.VERSION.SDK_INT >= 29) raw = (caps?.transportInfo as? WifiInfo)?.ssid
        if (raw.isNullOrEmpty() || raw == "<unknown ssid>") {
            raw = try {
                wifi.connectionInfo?.ssid
            } catch (e: Exception) {
                null
            }
        }
        return Tracker.cleanSsid(raw)
    }

    private fun updateText(current: String?) {
        val db = UsageDb.get(this)
        val today = LocalDate.now().toString()
        val all = db.total(today, today, null)
        if (current != null) {
            val here = db.total(today, today, current)
            title = "Wi-Fi: $current"
            text = "Today here ${fmtBytes(here.total)} · all Wi-Fi ${fmtBytes(all.total)}"
        } else {
            title = "Not on Wi-Fi"
            text = "Today on Wi-Fi: ${fmtBytes(all.total)}"
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "Wi-Fi tracking", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows the current network and today's usage"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 5 * 60 * 1000L

        fun start(ctx: Context) {
            if (!Perms.state(ctx).ready) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            } catch (e: Exception) {
                // Background start not allowed right now; the app will start it when opened.
            }
        }
    }
}
