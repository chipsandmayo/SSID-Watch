package com.ssidwatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ssidwatch.data.Traffic
import com.ssidwatch.data.UsageDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class Range(val label: String, val days: Long?) {
    WEEK("7 days", 7), MONTH("30 days", 30), QUARTER("90 days", 90), ALL("All time", null)
}

private class HistoryData(
    val from: LocalDate = LocalDate.now(),
    val days: List<Pair<LocalDate, Traffic>> = emptyList(),
    val networks: List<Pair<String, Traffic>> = emptyList(),
    val apps: List<Pair<Int, Traffic>> = emptyList(),
)

@Composable
fun HistoryScreen(
    network: String?,
    onNetwork: (String?) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    tick: Int,
    padding: PaddingValues,
) {
    val ctx = LocalContext.current
    var range by rememberSaveable { mutableStateOf(Range.WEEK) }
    var expanded by remember(range, network) { mutableStateOf<Int?>(null) }

    val data by produceState(HistoryData(), range, network, tick) {
        value = withContext(Dispatchers.IO) {
            val db = UsageDb.get(ctx)
            val today = LocalDate.now()
            val from = range.days?.let { today.minusDays(it - 1) }
                ?: db.firstDay()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: today
            val f = from.toString()
            val t = today.toString()
            val totals = db.dailyTotals(f, t, network)
            val days = generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(today) }
                .map { it to (totals[it.toString()] ?: Traffic.ZERO) }
                .toList()
            HistoryData(from, days, db.ssidTotals(f, t), db.appTotals(f, t, network))
        }
    }
    val f = data.from.toString()
    val t = LocalDate.now().toString()
    val total = data.days.fold(Traffic.ZERO) { a, p -> a + p.second }
    val chipNames = data.networks.map { it.first }.let { names ->
        if (network != null && network !in names) names + network else names
    }
    val short = DateTimeFormatter.ofPattern("d MMM")

    LazyColumn(contentPadding = padding) {
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Range.entries.forEach { r ->
                    FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
                }
            }
        }
        item { NetworkChips(chipNames, network, onNetwork) }
        item {
            Column {
                Spacer(Modifier.height(12.dp))
                TotalsCard(
                    (network ?: "All Wi-Fi networks") + " · " + data.from.format(short) + " – today",
                    total
                )
            }
        }
        item {
            Column {
                SectionTitle("Per day (tap a bar to open that day)")
                StackedBars(
                    data.days.map { it.second },
                    Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 16.dp),
                    onTap = { i -> data.days.getOrNull(i)?.let { onOpenDay(it.first) } }
                )
                if (data.days.isNotEmpty()) {
                    AxisLabels(listOf(data.days.first().first.format(short), "Today"))
                }
            }
        }
        if (network == null) {
            item { SectionTitle("Networks") }
            if (data.networks.isEmpty()) item { EmptyNote("Nothing recorded yet.") }
            val maxN = (data.networks.maxOfOrNull { it.second.total } ?: 1L).coerceAtLeast(1L)
            items(data.networks, key = { "n_" + it.first }) { (name, tr) ->
                UsageRow(
                    title = name,
                    t = tr,
                    fraction = tr.total.toFloat() / maxN,
                    italic = isSpecialNetwork(name),
                    onClick = { onNetwork(name) }
                )
            }
        }
        item { SectionTitle(if (network == null) "Apps (tap to see networks)" else "Apps on $network") }
        if (data.apps.isEmpty()) item { EmptyNote("No app usage recorded.") }
        val maxA = (data.apps.maxOfOrNull { it.second.total } ?: 1L).coerceAtLeast(1L)
        items(data.apps.take(50), key = { "a_" + it.first }) { (uid, tr) ->
            AppRow(
                uid = uid,
                t = tr,
                fraction = tr.total.toFloat() / maxA,
                from = f,
                to = t,
                expanded = expanded == uid,
                showNetworks = network == null,
                tick = tick,
                onToggle = { expanded = if (expanded == uid) null else uid }
            )
        }
        item { SectionTitle("Days") }
        val maxD = (data.days.maxOfOrNull { it.second.total } ?: 1L).coerceAtLeast(1L)
        items(data.days.reversed(), key = { "d_" + it.first }) { (d, tr) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenDay(d) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dayLabel(d), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "↓ ${fmtBytes(tr.rx)}  ↑ ${fmtBytes(tr.tx)}   ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(fmtBytes(tr.total), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(6.dp))
                ProportionBar(tr.total.toFloat() / maxD, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
