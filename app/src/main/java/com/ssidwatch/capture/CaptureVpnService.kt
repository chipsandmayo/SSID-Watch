package com.ssidwatch.capture

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Local "VPN" used only for packet capture. Nothing leaves the phone except through the
 * Forwarder's normal sockets; it just lets SSID Watch see each packet on the way.
 */
class CaptureVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null
    private var forwarder: Forwarder? = null
    private var reader: Thread? = null
    @Volatile private var active = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!active) startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val b = Builder()
            .setSession("SSID Watch packet capture")
            .setMtu(1500)
            .addAddress("10.215.173.1", 32)
            .addRoute("0.0.0.0", 0)
        try {
            b.addAddress("fd00:5175:10::1", 128)
            b.addRoute("::", 0)
        } catch (e: Exception) {
            // no IPv6
        }
        // Keep using the network's own DNS servers (their traffic is then captured too).
        var dnsAdded = false
        try {
            val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            lp?.dnsServers?.forEach { a ->
                if (a is Inet4Address || a is Inet6Address) {
                    runCatching {
                        b.addDnsServer(a)
                        dnsAdded = true
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        if (!dnsAdded) b.addDnsServer("8.8.8.8")
        runCatching { b.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) runCatching { b.setMetered(false) }

        val fd = try {
            b.establish()
        } catch (e: Exception) {
            null
        }
        if (fd == null) {
            CaptureLog.lastError = "Android didn't allow the capture to start."
            CaptureLog.setRunning(false)
            stopSelf()
            return
        }
        pfd = fd
        active = true
        CaptureLog.lastError = null
        CaptureLog.setRunning(true)

        val fwd = Forwarder(this, FileOutputStream(fd.fileDescriptor), cm)
        forwarder = fwd
        fwd.start()

        val input = FileInputStream(fd.fileDescriptor)
        reader = Thread({
            val buf = ByteArray(32_767)
            try {
                while (active) {
                    val n = input.read(buf)
                    if (n > 0) {
                        CaptureStats.readFromTun.addAndGet(n.toLong())
                        val copy = buf.copyOf(n)
                        IpPacket.parse(copy, n)?.let { fwd.submit(it) }
                    } else {
                        Thread.sleep(5)
                    }
                }
            } catch (e: Exception) {
                // interface closed
            }
        }, "ssidwatch-tun-reader").also { it.start() }
    }

    private fun stopCapture() {
        active = false
        forwarder?.shutdown()
        forwarder = null
        runCatching { pfd?.close() }
        pfd = null
        reader?.interrupt()
        reader = null
        CaptureLog.setRunning(false)
    }

    override fun onRevoke() {
        // another VPN took over, or the user turned it off in Settings
        stopCapture()
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ssidwatch.CAPTURE_START"
        const val ACTION_STOP = "com.ssidwatch.CAPTURE_STOP"

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
