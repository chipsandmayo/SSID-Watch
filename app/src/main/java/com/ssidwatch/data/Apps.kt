package com.ssidwatch.data

import android.app.usage.NetworkStats
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

data class AppLabel(val name: String, val packageName: String?, val icon: ImageBitmap?)

/** Turns a Linux uid from the network stats into an app name + icon. */
object Apps {
    private val cache = ConcurrentHashMap<Int, AppLabel>()

    fun get(ctx: Context, uid: Int): AppLabel =
        cache[uid] ?: resolve(ctx.applicationContext, uid).also { cache[uid] = it }

    private fun resolve(ctx: Context, uid: Int): AppLabel {
        val pm = ctx.packageManager
        when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> return AppLabel("Uninstalled apps", null, null)
            NetworkStats.Bucket.UID_TETHERING -> return AppLabel("Hotspot & tethering", null, null)
            0 -> return AppLabel("System (kernel)", null, null)
            1000 -> return AppLabel("Android system", "android", iconOf(pm, "android"))
            1013 -> return AppLabel("Media server", null, null)
            1021 -> return AppLabel("GPS", null, null)
        }
        val pkgs = try {
            pm.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        }
        if (pkgs.isNullOrEmpty()) return AppLabel("App (uid $uid)", null, null)
        for (p in pkgs.sorted()) {
            try {
                @Suppress("DEPRECATION")
                val ai = pm.getApplicationInfo(p, 0)
                val label = pm.getApplicationLabel(ai).toString()
                val icon = try {
                    pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
                val name = if (pkgs.size > 1) "$label (+${pkgs.size - 1} shared)" else label
                return AppLabel(name, p, icon)
            } catch (e: Exception) {
                // try next package
            }
        }
        return AppLabel(pkgs.first(), pkgs.first(), null)
    }

    private fun iconOf(pm: PackageManager, pkg: String): ImageBitmap? = try {
        pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
