package com.ssidwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ssidwatch.data.Tracker
import com.ssidwatch.ui.AppTheme
import com.ssidwatch.ui.ConnectionScreen
import com.ssidwatch.ui.LiveScreen
import com.ssidwatch.ui.DayScreen
import com.ssidwatch.ui.HistoryScreen
import com.ssidwatch.ui.Onboarding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var perms by remember { mutableStateOf(Perms.state(ctx)) }
    var showSetup by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) perms = Perms.state(ctx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Tracking is always on: the moment the required permissions exist, the tracker runs.
    LaunchedEffect(perms.ready) {
        if (perms.ready) MonitorService.start(ctx)
    }

    if (!perms.ready || showSetup) {
        Onboarding(
            state = perms,
            onRefresh = { perms = Perms.state(ctx) },
            onDone = { showSetup = false }
        )
    } else {
        MainScreen(perms = perms, onFixPermissions = { showSetup = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(perms: PermState, onFixPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var topTab by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dayStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var network by rememberSaveable { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val day = LocalDate.parse(dayStr)

    // While the app is open, take a fresh snapshot every 30 s so numbers stay live.
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { Tracker.refresh(ctx) }
            tick++
            delay(30_000)
        }
    }
    val current by produceState<String?>(null, tick) {
        value = withContext(Dispatchers.IO) { Tracker.currentSsid(ctx) }
    }

    Scaffold(
        topBar = {
            Column {
            TopAppBar(
                title = {
                    Column {
                        Text("SSID Watch")
                        Text(
                            current?.let { "Connected to $it" } ?: "Not on Wi-Fi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { Tracker.refresh(ctx) }
                            tick++
                        }
                    }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = onFixPermissions) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
            TabRow(selectedTabIndex = topTab) {
                Tab(selected = topTab == 0, onClick = { topTab = 0 }, text = { Text("Usage") })
                Tab(selected = topTab == 1, onClick = { topTab = 1 }, text = { Text("Live") })
                Tab(selected = topTab == 2, onClick = { topTab = 2 }, text = { Text("Connection") })
            }
            }
        },
        bottomBar = {
            if (topTab == 0) NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text("Day") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("History") }
                )
            }
        }
    ) { inner ->
        Column(Modifier.padding(top = inner.calculateTopPadding())) {
            if (!perms.complete) {
                Text(
                    "Network names may show as “${Tracker.UNKNOWN}”: allow location all the time " +
                        "and keep location services on. Tap to fix.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondary)
                        .clickable(onClick = onFixPermissions)
                        .padding(12.dp)
                )
            }
            val listPadding = PaddingValues(bottom = inner.calculateBottomPadding())
            if (topTab == 1) {
                LiveScreen(padding = listPadding)
            } else if (topTab == 2) {
                ConnectionScreen(padding = listPadding)
            } else when (tab) {
                0 -> DayScreen(
                    day = day,
                    onDay = { if (!it.isAfter(LocalDate.now())) dayStr = it.toString() },
                    network = network,
                    onNetwork = { network = it },
                    tick = tick,
                    padding = listPadding
                )
                else -> HistoryScreen(
                    network = network,
                    onNetwork = { network = it },
                    onOpenDay = {
                        dayStr = it.toString()
                        tab = 0
                    },
                    tick = tick,
                    padding = listPadding
                )
            }
        }
    }
}
