package com.ssidwatch

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

data class PermState(
    val usage: Boolean,
    val location: Boolean,
    val background: Boolean,
    val locationOn: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
) {
    /** Minimum needed to track at all. */
    val ready: Boolean get() = usage && location
    /** Everything needed for reliable SSID names in the background. */
    val complete: Boolean get() = ready && background && locationOn
}

object Perms {
    fun state(ctx: Context) = PermState(
        usage = hasUsageAccess(ctx),
        location = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
        background = Build.VERSION.SDK_INT < 29 ||
            granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        locationOn = ctx.getSystemService(LocationManager::class.java)
            ?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false,
        notifications = Build.VERSION.SDK_INT < 33 ||
            granted(ctx, Manifest.permission.POST_NOTIFICATIONS),
        battery = ctx.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true,
    )

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            granted(ctx, Manifest.permission.PACKAGE_USAGE_STATS)
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
