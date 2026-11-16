package com.ssidwatch

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.ssidwatch.data.LiveRecorder
import com.ssidwatch.ui.fmtBits
import kotlin.math.abs
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

    private var lastPrune = 0L
    private var overlay: TextView? = null
    private var overlayLp: WindowManager.LayoutParams? = null

    private fun interactive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    /** Per-app snapshot: every ~16 s with the screen on (Android's limit), 5 min with it off. */
    private val ticker = object : Runnable {
        override fun run() {
            val wait = Tracker.msUntilFreshPoll()
            if (wait > 0) {
                handler.postDelayed(this, wait + 250)
                return
            }
            worker.execute {
                sampleAndNotify(ssid)
                val now = System.currentTimeMillis()
                if (now - lastPrune > 3_600_000L) {
                    lastPrune = now
                    runCatching { UsageDb.get(this@MonitorService).pruneLive(now - LIVE_KEEP_MS) }
                }
            }
            handler.postDelayed(this, if (interactive()) Tracker.POLL_GAP_MS + 250 else INTERVAL_MS)
        }
    }

    /** Whole-phone Wi-Fi speed: every second with the screen on, every 10 s with it off. */
    private val recorder = object : Runnable {
        override fun run() {
            worker.execute {
                LiveRecorder.tick(this@MonitorService)
                handler.post { updateOverlay() }
            }
            handler.postDelayed(this, if (interactive()) 1_000L else 10_000L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // speed back up as soon as the screen turns on
            handler.removeCallbacks(recorder)
            handler.post(recorder)
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
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
            handler.post(recorder)
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        when (intent?.action) {
            ACTION_OVERLAY_ON -> showOverlay()
            ACTION_OVERLAY_OFF -> hideOverlay()
            else -> if (prefs().getBoolean(K_OVERLAY, false)) showOverlay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(recorder)
        runCatching { unregisterReceiver(screenReceiver) }
        removeOverlayView()
        worker.execute { LiveRecorder.flush(this) }
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

    // ---- floating speed box ---------------------------------------------------

    private fun prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayText(): String {
        val p = LiveRecorder.latest.value
        return "↓ " + fmtBits(LiveRecorder.bps(p.rx, p.dur)) + "\n↑ " + fmtBits(LiveRecorder.bps(p.tx, p.dur))
    }

    private fun updateOverlay() {
        overlay?.text = overlayText()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) {
            prefs().edit().putBoolean(K_OVERLAY, false).apply()
            return
        }
        prefs().edit().putBoolean(K_OVERLAY, true).apply()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val tv = TextView(this)
        tv.setTextColor(android.graphics.Color.WHITE)
        tv.textSize = 12f
        tv.typeface = Typeface.MONOSPACE
        tv.setPadding(dp(10), dp(6), dp(10), dp(6))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        bg.setColor(0xE6101418.toInt())
        bg.setStroke(dp(1), 0x994FC3F7.toInt())
        tv.background = bg
        tv.text = overlayText()

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs().getInt(K_OVERLAY_X, dp(16))
        lp.y = prefs().getInt(K_OVERLAY_Y, dp(140))

        tv.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) + abs(dy) > dp(6)) moved = true
                        if (moved) {
                            lp.x = startX + dx
                            lp.y = startY + dy
                            runCatching { wm.updateViewLayout(v, lp) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            prefs().edit().putInt(K_OVERLAY_X, lp.x).putInt(K_OVERLAY_Y, lp.y).apply()
                        } else if (e.eventTime - e.downTime > 600) {
                            hideOverlay() // long-press closes the box
                        } else {
                            val open = Intent(this@MonitorService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            runCatching { startActivity(open) }
                        }
                    }
                }
                return true
            }
        })
        try {
            wm.addView(tv, lp)
            overlay = tv
            overlayLp = lp
        } catch (e: Exception) {
            prefs().edit().putBoolean(K_OVERLAY, false).apply()
        }
    }

    private fun removeOverlayView() {
        val v = overlay ?: return
        runCatching { getSystemService(WindowManager::class.java)?.removeView(v) }
        overlay = null
        overlayLp = null
    }

    private fun hideOverlay() {
        prefs().edit().putBoolean(K_OVERLAY, false).apply()
        removeOverlayView()
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
        const val ACTION_OVERLAY_ON = "com.ssidwatch.OVERLAY_ON"
        const val ACTION_OVERLAY_OFF = "com.ssidwatch.OVERLAY_OFF"
        const val K_OVERLAY = "overlay"
        private const val K_OVERLAY_X = "overlayX"
        private const val K_OVERLAY_Y = "overlayY"
        private const val LIVE_KEEP_MS = 7 * 24 * 60 * 60 * 1000L
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 5 * 60 * 1000L

        fun isOverlayOn(ctx: Context): Boolean =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(K_OVERLAY, false)

        /** Show or hide the floating speed box (needs "Display over other apps"). */
        fun setOverlay(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(K_OVERLAY, on).apply()
            try {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, MonitorService::class.java).setAction(if (on) ACTION_OVERLAY_ON else ACTION_OVERLAY_OFF)
                )
            } catch (e: Exception) {
                // ignore
            }
        }

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
