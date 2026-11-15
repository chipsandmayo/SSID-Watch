package com.ssidwatch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00243A),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF3A2000),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE3E6EB),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE3E6EB),
    surfaceVariant = Color(0xFF1A1F27),
    onSurfaceVariant = Color(0xFF9AA3AF),
    surfaceContainer = Color(0xFF151A21),
    outlineVariant = Color(0xFF2A313B),
    secondaryContainer = Color(0xFF1F3A4A),
    onSecondaryContainer = Color(0xFFCDEBFA),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFFE65100),
    surfaceVariant = Color(0xFFEFF2F6),
    outlineVariant = Color(0xFFD5DAE1),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (v >= 100) String.format("%.0f %s", v, units[i]) else String.format("%.1f %s", v, units[i])
}
