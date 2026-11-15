package com.ssidwatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ssidwatch.data.Traffic
import com.ssidwatch.data.UsageDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))
    }
}

private class DayData(
    val networks: List<Pair<String, Traffic>> = emptyList(),
    val apps: List<Pair<Int, Traffic>> = emptyList(),
    val hours: List<Traffic> = List(24) { Traffic.ZERO },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    day: LocalDate,
    onDay: (LocalDate) -> Unit,
    network: String?,
    onNetwork: (String?) -> Unit,
    tick: Int,
    padding: PaddingValues,
) {
    val ctx = LocalContext.current
    val ds = day.toString()
    val today = LocalDate.now()
    var showPicker by remember { mutableStateOf(false) }
    var expanded by remember(ds, network) { mutableStateOf<Int?>(null) }

    val data by produceState(DayData(), ds, network, tick) {
        value = withContext(Dispatchers.IO) {
            val db = UsageDb.get(ctx)
            DayData(
                networks = db.ssidTotals(ds, ds),
                apps = db.appTotals(ds, ds, network),
                hours = db.hourly(ds, network).toList(),
            )
        }
    }
    val total = if (network == null) {
        data.networks.fold(Traffic.ZERO) { a, p -> a + p.second }
    } else {
        data.networks.firstOrNull { it.first == network }?.second ?: Traffic.ZERO
    }
    // Keep the selected network visible as a chip even on days it wasn't used.
    val chipNames = data.networks.map { it.first }.let { names ->
        if (network != null && network !in names) names + network else names
    }

    LazyColumn(contentPadding = padding) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { onDay(day.minusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                }
                Text(
                    dayLabel(day),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showPicker = true }.padding(8.dp)
                )
                IconButton(onClick = { onDay(day.plusDays(1)) }, enabled = day < today) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                }
            }
        }
        item { NetworkChips(chipNames, network, onNetwork) }
        item {
            Column {
                Spacer(Modifier.height(12.dp))
                TotalsCard(network ?: "All Wi-Fi networks", total)
            }
        }
        item {
            Column {
                SectionTitle("By hour")
                StackedBars(data.hours, Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 16.dp))
                AxisLabels(listOf("00:00", "06:00", "12:00", "18:00", "24:00"))
            }
        }
        if (network == null) {
            item { SectionTitle("Networks") }
            if (data.networks.isEmpty()) item { EmptyNote("No Wi-Fi usage recorded for this day.") }
            val maxN = (data.networks.maxOfOrNull { it.second.total } ?: 1L).coerceAtLeast(1L)
            items(data.networks, key = { "n_" + it.first }) { (name, t) ->
                UsageRow(
                    title = name,
                    t = t,
                    fraction = t.total.toFloat() / maxN,
                    italic = isSpecialNetwork(name),
                    onClick = { onNetwork(name) }
                )
            }
        }
        item { SectionTitle(if (network == null) "Apps (tap to see networks)" else "Apps on $network") }
        if (data.apps.isEmpty()) item { EmptyNote("No app usage recorded.") }
        val maxA = (data.apps.maxOfOrNull { it.second.total } ?: 1L).coerceAtLeast(1L)
        items(data.apps, key = { "a_" + it.first }) { (uid, t) ->
            AppRow(
                uid = uid,
                t = t,
                fraction = t.total.toFloat() / maxA,
                from = ds,
                to = ds,
                expanded = expanded == uid,
                showNetworks = network == null,
                tick = tick,
                onToggle = { expanded = if (expanded == uid) null else uid }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showPicker) {
        val nowUtc = System.currentTimeMillis()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= nowUtc
            }
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}
