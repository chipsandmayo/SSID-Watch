package com.ssidwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

private val TERM_BG = Color(0xFF0C0C0C)
private val TERM_FG = Color(0xFFCCCCCC)
private val TERM_PROMPT = Color(0xFF4FC3F7)
private val TERM_ERR = Color(0xFFE57373)
private val TERM_OK = Color(0xFF81C784)

private const val MAX_LINES = 500
private val HOST_RE = Regex("^[A-Za-z0-9.:%_-]{1,253}$")

private const val HELP = """Commands:
  ping <host>          send 4 pings (like Windows)
  ping -n <N> <host>   send N pings
  ping -t <host>       ping until you tap Stop
  nslookup <host>      look up a name's IP addresses
  cls                  clear the screen
  help                 show this list
Examples: ping 8.8.8.8   ping -t 192.168.0.1   nslookup google.com"""

private class Line(val text: String, val color: Color)

/** A small command-prompt-style box for ping and nslookup. */
@Composable
fun TerminalSection() {
    var open by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf(Line("SSID Watch command prompt. Type help for commands.", TERM_FG)) }
    var input by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var proc by remember { mutableStateOf<Process?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var running by remember { mutableStateOf(false) }

    fun add(text: String, color: Color = TERM_FG) {
        lines.add(Line(text, color))
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    fun stop() {
        proc?.destroy()
        proc = null
        job?.cancel()
        job = null
        running = false
    }

    fun runPing(args: List<String>) {
        var count = 4
        var forever = false
        var host: String? = null
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-t" -> forever = true
                "-n", "-c" -> {
                    count = args.getOrNull(i + 1)?.toIntOrNull()?.coerceIn(1, 1000) ?: run {
                        add("ping: $a needs a number, e.g. ping -n 10 8.8.8.8", TERM_ERR)
                        return
                    }
                    i++
                }
                else -> host = a
            }
            i++
        }
        val h = host
        if (h == null || !HOST_RE.matches(h)) {
            add("ping: give an IP address or host name, e.g. ping 8.8.8.8", TERM_ERR)
            return
        }
        val cmd = ArrayList<String>()
        cmd += if (h.contains(':')) "ping6" else "ping"
        if (!forever) {
            cmd += "-c"
            cmd += count.toString()
        }
        cmd += "-W"
        cmd += "2"
        cmd += h
        running = true
        job = scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    ProcessBuilder(cmd).redirectErrorStream(true).start()
                }
                proc = p
                withContext(Dispatchers.IO) {
                    p.inputStream.bufferedReader().useLines { seq ->
                        for (l in seq) {
                            if (!isActive) break
                            val color = when {
                                l.contains("time=") -> TERM_OK
                                l.contains("unreachable", true) || l.contains("unknown host", true) ||
                                    l.contains("100% packet loss") -> TERM_ERR
                                else -> TERM_FG
                            }
                            withContext(Dispatchers.Main) { add(l, color) }
                        }
                    }
                    runCatching { p.waitFor() }
                }
            } catch (e: Exception) {
                if (isActive) add("ping: ${e.message ?: "failed"}", TERM_ERR)
            } finally {
                proc?.destroy()
                proc = null
                running = false
            }
        }
    }

    fun runNslookup(args: List<String>) {
        val h = args.firstOrNull()
        if (h == null || !HOST_RE.matches(h)) {
            add("nslookup: give a host name, e.g. nslookup google.com", TERM_ERR)
            return
        }
        running = true
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    InetAddress.getAllByName(h).map { it.hostAddress ?: "?" }
                } catch (e: Exception) {
                    null
                }
            }
            if (result == null || result.isEmpty()) {
                add("*** Can't find $h: Non-existent domain", TERM_ERR)
            } else {
                add("Name:    $h")
                add(if (result.size > 1) "Addresses:" else "Address:")
                result.forEach { add("  $it", TERM_OK) }
            }
            running = false
        }
    }

    fun execute() {
        val line = input.trim()
        input = ""
        if (line.isEmpty()) return
        add("> $line", TERM_PROMPT)
        val parts = line.split(Regex("\\s+"))
        val cmd = parts[0].lowercase()
        val args = parts.drop(1)
        when (cmd) {
            "help", "?" -> HELP.lines().forEach { add(it) }
            "cls", "clear" -> lines.clear()
            "ping" -> runPing(args)
            "nslookup" -> runNslookup(args)
            else -> add("'${parts[0]}' is not a recognised command. Type help for the list.", TERM_ERR)
        }
    }

    LaunchedEffect(lines.size, open) {
        if (open && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

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
                    "Command prompt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(if (open) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(TERM_BG)
                        .padding(8.dp)
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(260.dp)) {
                        itemsIndexed(lines) { _, l ->
                            Text(l.text, color = l.color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("> ", color = TERM_PROMPT, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            enabled = !running,
                            textStyle = TextStyle(color = TERM_FG, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            cursorBrush = SolidColor(TERM_PROMPT),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                                autoCorrectEnabled = false
                            ),
                            keyboardActions = KeyboardActions(onGo = { execute() }),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (input.isEmpty()) {
                                    Text(
                                        if (running) "running… tap Stop" else "e.g. ping 8.8.8.8",
                                        color = Color(0xFF666666),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                }
                                inner()
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(onClick = {
                            stop()
                            add("^C", TERM_PROMPT)
                        }) { Text("Stop") }
                    } else {
                        Button(onClick = { execute() }, enabled = input.isNotBlank()) { Text("Run") }
                    }
                    OutlinedButton(onClick = { lines.clear() }) { Text("Clear") }
                    OutlinedButton(onClick = { HELP.lines().forEach { add(it) } }) { Text("Help") }
                }
            }
        }
    }
}
