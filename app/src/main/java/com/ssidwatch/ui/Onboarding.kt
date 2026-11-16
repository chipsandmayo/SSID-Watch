package com.ssidwatch.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssidwatch.PermState

private fun Context.openSafely(vararg intents: Intent) {
    for (i in intents) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // try the next one
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun Onboarding(state: PermState, onRefresh: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onRefresh() }
    val oneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }
    val pkgUri = Uri.parse("package:" + ctx.packageName)
    var n = 0

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("SSID Watch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tracks Wi-Fi data per network and per app, all the time. Everything stays on your phone. " +
                "Android needs a few permissions first:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Step(++n, "Usage access", "Lets the app read Android's per-app data counters. Find SSID Watch in the list and switch it on.", state.usage, true) {
            ctx.openSafely(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri),
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            )
        }
        Step(++n, "Location", "Android only tells apps the Wi-Fi name (SSID) if they have location permission. Your location itself is never used.", state.location, true) {
            locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (Build.VERSION.SDK_INT >= 29) {
            Step(++n, "Location: Allow all the time", "So the network name can still be read while the app is closed. Choose “Allow all the time”.", state.background, state.location) {
                if (Build.VERSION.SDK_INT >= 30) {
                    oneLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            }
        }
        Step(++n, "Turn on location services", "Wi-Fi names are hidden from all apps while the phone's location switch is off.", state.locationOn, true) {
            ctx.openSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Step(++n, "Notifications", "Shows the tracking notification with the current network and today's usage.", state.notifications, true) {
                oneLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Step(++n, "Don't optimise battery", "Stops Android from killing the tracker in the background. Uses very little battery.", state.battery, true) {
            ctx.openSafely(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }

        Spacer(Modifier.height(20.dp))
        if (state.ready) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
        } else {
            Text(
                "Tracking starts automatically as soon as steps 1 and 2 are allowed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Re-check permissions") }
    }
}

@Composable
private fun Step(num: Int, title: String, body: String, done: Boolean, enabled: Boolean, action: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (done) "✓" else num.toString(),
                    color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!done) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = action, enabled = enabled) { Text("Allow") }
            }
        }
    }
}
