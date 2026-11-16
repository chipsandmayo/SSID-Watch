package com.ssidwatch.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.ssidwatch.InstallReceiver
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val versionCode: Long, val name: String, val apkUrl: String, val notes: String?)

/** Self-updater: checks the GitHub releases of this repo and installs the newest APK. */
object Updater {
    const val REPO = "chipsandmayo/SSID-Watch"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    fun installedVersion(ctx: Context): Pair<Long, String> = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        PackageInfoCompat.getLongVersionCode(pi) to (pi.versionName ?: "?")
    } catch (e: Exception) {
        0L to "?"
    }

    /** Latest release, or null if GitHub can't be reached. Tags look like v1.0.<build>. */
    fun latest(): Release? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(LATEST).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "SSIDWatch")
            if (c.responseCode !in 200..299) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    break
                }
            }
            if (url.isNullOrEmpty()) return null
            Release(code, tag.removePrefix("v"), url, j.optString("body").ifEmpty { null })
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** Downloads the APK into the app's cache, reporting progress 0..1. */
    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File? {
        val out = File(ctx.cacheDir, "update.apk")
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "SSIDWatch")
            if (c.responseCode !in 200..299) return null
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            onProgress(1f)
            out
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Hands the APK to Android's installer; Android then asks you to confirm the update. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags
            )
            session.commit(pending.intentSender)
        }
    }
}
