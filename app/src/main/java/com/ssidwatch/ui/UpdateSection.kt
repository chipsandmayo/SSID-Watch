package com.ssidwatch.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssidwatch.data.Release
import com.ssidwatch.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { Updater.installedVersion(ctx) }
    var latest by remember { mutableStateOf<Release?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }

    fun check() {
        scope.launch {
            checking = true
            status = null
            val r = withContext(Dispatchers.IO) { Updater.latest() }
            checking = false
            latest = r
            status = when {
                r == null -> "Couldn't reach GitHub. Check your connection."
                r.versionCode > installed.first -> "Update available: version ${r.name}"
                else -> "You're on the latest version."
            }
        }
    }

    fun downloadAndInstall(r: Release) {
        if (!Updater.canInstall(ctx)) {
            status = "Allow SSID Watch to install apps (switch it on), then come back and tap Update again."
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // ignore
            }
            return
        }
        scope.launch {
            progress = 0f
            status = "Downloading version ${r.name}…"
            val file = withContext(Dispatchers.IO) {
                Updater.download(ctx, r.apkUrl) { p -> progress = p }
            }
            if (file == null) {
                progress = null
                status = "Download failed. Try again."
                return@launch
            }
            status = "Installing… confirm the update when Android asks."
            val err = withContext(Dispatchers.IO) {
                try {
                    Updater.install(ctx, file)
                    null
                } catch (e: Exception) {
                    e.message ?: "unknown error"
                }
            }
            progress = null
            if (err != null) status = "Install failed: $err"
        }
    }

    LaunchedEffect(Unit) { check() }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Installed version ${installed.second}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            progress?.let { p ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
            val r = latest
            if (r != null && r.versionCode > installed.first && !r.notes.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    r.notes.lineSequence().filter { !it.contains("Permanent link") }.joinToString("\n").trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { check() }, enabled = !checking && progress == null) {
                    Text(if (checking) "Checking…" else "Check for updates")
                }
                if (r != null && r.versionCode > installed.first) {
                    Button(onClick = { downloadAndInstall(r) }, enabled = progress == null) {
                        Text("Update")
                    }
                }
            }
        }
    }
}
