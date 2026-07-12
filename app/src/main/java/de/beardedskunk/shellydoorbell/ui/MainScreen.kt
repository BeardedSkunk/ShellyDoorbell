@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.beardedskunk.shellydoorbell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.beardedskunk.shellydoorbell.data.AppDb
import de.beardedskunk.shellydoorbell.service.DoorbellService
import de.beardedskunk.shellydoorbell.shelly.ConnectionState
import de.beardedskunk.shellydoorbell.shelly.DndSettings
import de.beardedskunk.shellydoorbell.shelly.SharedSettings
import kotlin.math.roundToInt

private val DAY_LABELS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

@Composable
fun MainScreen(
    service: DoorbellService,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(service) { service.messages.collect { snackbar.showSnackbar(it) } }

    val conn by service.connectionState.collectAsState()
    val watts by service.watts.collectAsState()
    val bellOn by service.bellOn.collectAsState()
    val shared by service.shared.collectAsState()
    val dnd by service.dnd.collectAsState()
    val scriptOk by service.scriptOk.collectAsState()
    val alarmActive by service.alarmActive.collectAsState()
    val connected = conn is ConnectionState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Klingelüberwachung") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (alarmActive) AlarmBanner(onStop = { service.stopAlarm() })
            ConnectionCard(conn, watts, onReconnect = { service.reconnect() })
            if (connected && scriptOk == false) ScriptWarning()
            BellCard(bellOn, connected, onToggle = { service.setBell(it) })
            DndCard(dnd, connected, onChange = { service.saveDnd(it) })
            SharedCard(shared, connected, onSave = { t, d -> service.saveShared(t, d) })
            EventsCard(onHistory)
        }
    }
}

@Composable
private fun AlarmBanner(onStop: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "🔔 Es klingelt!",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Alarm stoppen")
            }
        }
    }
}

@Composable
private fun ConnectionCard(conn: ConnectionState, watts: Double?, onReconnect: () -> Unit) {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (color, title, sub) = when (conn) {
                is ConnectionState.Connected -> Triple(Color(0xFF43A047), "Verbunden", conn.deviceName)
                ConnectionState.Connecting -> Triple(Color(0xFFFB8C00), "Verbinde …", "Shelly wird gesucht")
                ConnectionState.NoWifi -> Triple(Color(0xFFE53935), "Kein WLAN", "Warte auf Heimnetz")
            }
            Box(Modifier.size(14.dp).background(color, CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            if (conn is ConnectionState.Connected) {
                Text(
                    Fmt.watts(watts),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                IconButton(onClick = onReconnect) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Neu verbinden")
                }
            }
        }
    }
}

@Composable
private fun ScriptWarning() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "Auf dem Shelly läuft kein Script namens „doorbell“ – Klingel-Alarme kommen so nicht an. " +
                    "Bitte shelly/doorbell.js installieren (siehe README).",
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun BellCard(bellOn: Boolean?, connected: Boolean, onToggle: (Boolean) -> Unit) {
    val off = bellOn == false
    Card(
        colors = if (off) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Klingel", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (bellOn) {
                        true -> "Trafo eingeschaltet – Klingel funktioniert"
                        false -> "Trafo AUS – es kann niemand klingeln!"
                        null -> "Status unbekannt"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = bellOn == true,
                onCheckedChange = onToggle,
                enabled = connected && bellOn != null,
            )
        }
    }
}

@Composable
private fun DndCard(dnd: DndSettings?, connected: Boolean, onChange: (DndSettings) -> Unit) {
    val current = dnd ?: DndSettings.DEFAULT
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ruhezeiten", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Klingel wird zeitgesteuert stromlos geschaltet",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = current.enabled,
                    onCheckedChange = { onChange(current.copy(enabled = it)) },
                    enabled = connected,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickStart = true }, enabled = connected) {
                    Text(Fmt.minutes(current.startMin))
                }
                Text("bis")
                OutlinedButton(onClick = { pickEnd = true }, enabled = connected) {
                    Text(Fmt.minutes(current.endMin))
                }
                if (current.endMin <= current.startMin) {
                    Text("(über Nacht)", style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAY_LABELS.forEachIndexed { day, label ->
                    FilterChip(
                        selected = day in current.days,
                        onClick = {
                            val days = if (day in current.days) current.days - day else current.days + day
                            if (days.isNotEmpty()) onChange(current.copy(days = days))
                        },
                        label = { Text(label) },
                        enabled = connected,
                    )
                }
            }
        }
    }

    if (pickStart) {
        TimePickerDialog(
            initialMin = current.startMin,
            onDismiss = { pickStart = false },
            onConfirm = { pickStart = false; onChange(current.copy(startMin = it)) },
        )
    }
    if (pickEnd) {
        TimePickerDialog(
            initialMin = current.endMin,
            onDismiss = { pickEnd = false },
            onConfirm = { pickEnd = false; onChange(current.copy(endMin = it)) },
        )
    }
}

@Composable
private fun TimePickerDialog(initialMin: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMin / 60,
        initialMinute = initialMin % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun SharedCard(shared: SharedSettings?, connected: Boolean, onSave: (Double, Int) -> Unit) {
    var threshold by remember(shared) { mutableFloatStateOf((shared?.thresholdW ?: DoorbellService.DEFAULT_THRESHOLD_W).toFloat()) }
    var debounce by remember(shared) { mutableFloatStateOf((shared?.debounceS ?: DoorbellService.DEFAULT_DEBOUNCE_S).toFloat()) }
    fun save() = onSave((threshold * 2).roundToInt() / 2.0, debounce.roundToInt())

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Erkennung (gilt für alle)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Klingel-Schwelle: ${Fmt.watts((threshold * 2).roundToInt() / 2.0)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = ::save,
                valueRange = 0.5f..15f,
                steps = 28,
                enabled = connected && shared != null,
            )
            Text(
                "Sperrzeit nach Klingeln: ${debounce.roundToInt()} s",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = debounce,
                onValueChange = { debounce = it },
                onValueChangeFinished = ::save,
                valueRange = 5f..120f,
                steps = 22,
                enabled = connected && shared != null,
            )
        }
    }
}

@Composable
private fun EventsCard(onHistory: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).ringDao() }
    val since = remember { System.currentTimeMillis() / 1000 - 24 * 3600 }
    val recent by dao.recent(since, 5).collectAsState(initial = emptyList())

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Zuletzt geklingelt",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onHistory) { Text("Verlauf") }
            }
            if (recent.isEmpty()) {
                Text(
                    "In den letzten 24 Stunden hat es nicht geklingelt.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                recent.forEach { event ->
                    Row {
                        Text(
                            Fmt.dayTime(event.ts),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        event.power?.let {
                            Text(Fmt.watts(it), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
