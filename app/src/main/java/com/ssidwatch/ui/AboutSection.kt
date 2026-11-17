package com.ssidwatch.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Whether the Connection tab may use the two online lookup services. */
object Lookups {
    private const val PREFS = "settings"
    private const val KEY = "onlineLookups"
    var enabled by mutableStateOf(true)
        private set

    fun load(ctx: Context) {
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

private const val PRIVACY_TEXT =
    "SSID Watch keeps everything on your phone: usage history, Wi-Fi names, the live graph " +
        "recording and captured packets are stored only in the app's private storage and are " +
        "never uploaded. Uninstalling the app deletes all of it.\n\n" +
        "Location permission is used only because Android requires it to reveal the Wi-Fi name. " +
        "The app never reads your GPS position.\n\n" +
        "Packet capture runs only between Start and Stop. Packets stay on the phone; the app " +
        "just passes them on to their normal destination.\n\n" +
        "The app contacts these services:\n" +
        "• ipinfo.io: when you open the Connection tab, to show your internet provider and " +
        "approximate location. It sees your public IP address.\n" +
        "• api.macvendors.com: to show your router's manufacturer. It receives only the first " +
        "half of the router's hardware address (the manufacturer prefix).\n" +
        "• github.com: to check for and download updates.\n" +
        "• Google AdMob: shows the small ad banner at the bottom of the screen. Google may use " +
        "your device's advertising ID and IP address to choose and measure ads, depending on " +
        "the choices you made in the ad consent screen.\n\n" +
        "You can turn the ipinfo.io and macvendors lookups off below."

private data class Library(val name: String, val owner: String)

private val LIBRARIES = listOf(
    Library("AndroidX Core, Activity and Lifecycle", "The Android Open Source Project"),
    Library("Jetpack Compose (UI, Foundation, Material 3, Material Icons)", "The Android Open Source Project"),
    Library("Kotlin Standard Library", "JetBrains s.r.o. and Kotlin Programming Language contributors"),
    Library("kotlinx.coroutines", "JetBrains s.r.o. and Kotlin Programming Language contributors"),
)

private const val GOOGLE_ADS_NOTE =
    "Ads are provided by the Google Mobile Ads SDK and the Google User Messaging Platform SDK, " +
        "used under the Google APIs Terms of Service and the Google Mobile Ads SDK Terms."

@Composable
fun PrivacySection() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (open) PRIVACY_TEXT else PRIVACY_TEXT.substringBefore("\n\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { open = !open }) { Text(if (open) "Show less" else "Read the full privacy note") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Online lookups", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Provider, location and router maker on the Connection tab",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = Lookups.enabled, onCheckedChange = { Lookups.set(ctx, it) })
            }
            AdPrivacyButton()
        }
    }
}

@Composable
fun LicencesSection() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Open-source licences",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(if (open) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "SSID Watch is built with the following open-source software, each used under the " +
                        "Apache License, Version 2.0. The libraries are distributed on an “AS IS” " +
                        "basis, without warranties or conditions of any kind.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LIBRARIES.forEach { lib ->
                    Spacer(Modifier.height(8.dp))
                    Text(lib.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Copyright © ${lib.owner}. Licensed under the Apache License, Version 2.0.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    GOOGLE_ADS_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.apache.org/licenses/LICENSE-2.0"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Read the Apache License 2.0") }
            }
        }
    }
}
