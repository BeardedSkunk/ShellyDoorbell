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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.beardedskunk.shellydoorbell.data.AppDb
import de.beardedskunk.shellydoorbell.data.Prefs
import de.beardedskunk.shellydoorbell.service.DoorbellService
import de.beardedskunk.shellydoorbell.shelly.BellEntry
import de.beardedskunk.shellydoorbell.shelly.BellTimes
import de.beardedskunk.shellydoorbell.shelly.BellWindow
import de.beardedskunk.shellydoorbell.shelly.ConnectionState
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    service: DoorbellService,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showAuthDialog by remember { mutableStateOf(false) }
    LaunchedEffect(service) { service.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(service) { service.authError.collect { showAuthDialog = true } }

    val settings by prefs.settings.collectAsState(initial = null)
    val listenOnly = settings?.listenOnly ?: false
    val conn by service.connectionState.collectAsState()
    val watts by service.watts.collectAsState()
    val bellOn by service.bellOn.collectAsState()
    val bellTimes by service.bellTimes.collectAsState()
    val muteUntil by service.muteUntil.collectAsState()
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
            BellCard(
                bellOn = bellOn,
                muteUntil = muteUntil,
                connected = connected,
                listenOnly = listenOnly,
                onToggle = { service.setBell(it) },
                onMute = { service.setMute(it) },
                onClearMute = { service.clearMute() },
            )
            LocalAlarmCard()
            // Klingelzeiten schreiben auf den Shelly -> im Lauschmodus ausblenden.
            if (!listenOnly) {
                BellTimesCard(
                    entries = bellTimes,
                    connected = connected,
                    onAdd = { service.addBellTime(it) },
                    onRemove = { service.removeBellTime(it) },
                )
            }
            EventsCard(onHistory)
        }
    }

    if (showAuthDialog) {
        AuthErrorDialog(
            onDismiss = { showAuthDialog = false },
            onSave = { pw ->
                scope.launch { prefs.setPassword(pw) }
                // Sofort in die laufende Verbindung übernehmen und neu abgleichen
                // (nicht auf den DataStore-Umweg warten).
                service.applyPassword(pw)
                showAuthDialog = false
            },
            onOpenSettings = {
                showAuthDialog = false
                onSettings()
            },
        )
    }
}

/**
 * Erscheint, wenn der Shelly zwar erreichbar ist, aber Befehle mangels
 * (richtigem) Passwort mit 401 abweist. Erlaubt die Passworteingabe direkt,
 * alternativ Sprung in die Einstellungen.
 */
@Composable
private fun AuthErrorDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Passwort erforderlich") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Der Shelly ist im Netz erreichbar, verlangt aber ein Passwort für Schalt- " +
                        "und Einstellungsbefehle. Bitte das in der Shelly-Web-UI gesetzte Passwort " +
                        "eingeben (Benutzer ist immer „admin“).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Passwort") },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(if (visible) "Verbergen" else "Zeigen")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(pw) }, enabled = pw.isNotBlank()) {
                Text("Speichern & erneut verbinden")
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenSettings) { Text("Einstellungen") }
        },
    )
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
                    "Die App richtet es normalerweise selbst ein; unter Einstellungen → " +
                    "„Verbindung prüfen“ lässt sich das erneut anstoßen.",
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun BellCard(
    bellOn: Boolean?,
    muteUntil: Long?,
    connected: Boolean,
    listenOnly: Boolean,
    onToggle: (Boolean) -> Unit,
    onMute: (Long) -> Unit,
    onClearMute: () -> Unit,
) {
    var pickMute by remember { mutableStateOf(false) }
    val off = bellOn == false
    Card(
        colors = if (off) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                // Der Toggle schreibt auf den Shelly -> im Lauschmodus weglassen.
                if (!listenOnly) {
                    Switch(
                        checked = bellOn == true,
                        onCheckedChange = onToggle,
                        enabled = connected && bellOn != null,
                    )
                }
            }
            // "Ruhe bis" schreibt ebenfalls auf den Shelly -> im Lauschmodus weg.
            if (!listenOnly) {
                if (muteUntil != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Ruhe bis ${Fmt.muteUntil(muteUntil)}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(onClick = { pickMute = true }, enabled = connected) { Text("Ändern") }
                        OutlinedButton(onClick = onClearMute, enabled = connected) { Text("Beenden") }
                    }
                } else {
                    OutlinedButton(onClick = { pickMute = true }, enabled = connected) {
                        Text("Ruhe bis …")
                    }
                }
            }
        }
    }

    if (pickMute) {
        // Vorbelegung: laufende Ruhe, sonst in einer Stunde
        val initial = muteUntil
            ?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalTime() }
            ?: LocalTime.now().plusHours(1)
        TimePickerDialog(
            initialMin = initial.hour * 60 + initial.minute,
            onDismiss = { pickMute = false },
            onConfirm = { min -> pickMute = false; onMute(nextOccurrence(min)) },
        )
    }
}

/**
 * Rein lokaler Schalter: Alarm nur auf DIESEM Handy aus. Shelly, andere
 * Geraete und die History bleiben unberuehrt.
 */
@Composable
private fun LocalAlarmCard() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val settings by prefs.settings.collectAsState(initial = null)
    val enabled = settings?.alarmEnabled ?: true

    Card(
        colors = if (!enabled) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Phone, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Alarm auf diesem Handy", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) {
                        "Dieses Handy schlägt bei Klingeln Alarm"
                    } else {
                        "Stumm – Lausch-Dienst pausiert, andere Geräte klingeln weiter"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on -> scope.launch { prefs.setAlarmEnabled(on) } },
                enabled = settings != null,
            )
        }
    }
}

/** Naechster Zeitpunkt mit dieser Uhrzeit als Unix-Sekunden: heute, sonst morgen. */
private fun nextOccurrence(minuteOfDay: Int): Long {
    val now = LocalDateTime.now()
    var t = now.toLocalDate().atTime(minuteOfDay / 60, minuteOfDay % 60)
    if (!t.isAfter(now)) t = t.plusDays(1)
    return t.atZone(ZoneId.systemDefault()).toEpochSecond()
}

@Composable
private fun BellTimesCard(
    entries: List<BellEntry>?,
    connected: Boolean,
    onAdd: (BellWindow) -> Unit,
    onRemove: (BellEntry) -> Unit,
) {
    // Editor fuer eine NEUE Klingelzeit; uebernommen wird sie erst mit dem Plus.
    var startMin by remember { mutableIntStateOf(BellWindow.DEFAULT.startMin) }
    var endMin by remember { mutableIntStateOf(BellWindow.DEFAULT.endMin) }
    var days by remember { mutableStateOf(BellWindow.DEFAULT.days) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }
    val ready = connected && entries != null

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Klingelzeiten", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Nur in diesen Zeiten ist die Klingel aktiv",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(
                    onClick = {
                        val w = BellWindow(startMin, endMin, days)
                        val existing = entries.orEmpty()
                        when {
                            existing.size >= BellTimes.MAX_WINDOWS ->
                                warning = "Es sind maximal ${BellTimes.MAX_WINDOWS} Klingelzeiten möglich " +
                                    "(Schedule-Limit des Shelly)."
                            existing.any { BellTimes.overlaps(it.window, w) } ->
                                warning = "Die neue Klingelzeit überschneidet oder berührt eine bereits " +
                                    "eingerichtete Klingelzeit und wurde deshalb nicht übernommen."
                            else -> onAdd(w)
                        }
                    },
                    enabled = ready && days.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Klingelzeit hinzufügen")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickStart = true }, enabled = ready) {
                    Text(Fmt.minutes(startMin))
                }
                Text("bis")
                OutlinedButton(onClick = { pickEnd = true }, enabled = ready) {
                    Text(Fmt.minutes(endMin))
                }
                if (endMin <= startMin) {
                    Text("(über Nacht)", style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Fmt.DAY_LABELS.forEachIndexed { day, label ->
                    FilterChip(
                        selected = day in days,
                        onClick = { days = if (day in days) days - day else days + day },
                        label = { Text(label) },
                        enabled = ready,
                    )
                }
            }
            HorizontalDivider()
            when {
                entries == null -> Text("Klingelzeiten werden geladen …", style = MaterialTheme.typography.bodySmall)
                entries.isEmpty() -> Text(
                    "Keine Klingelzeiten eingerichtet – die Klingel ist immer aktiv.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Column {
                    entries.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                Fmt.window(entry.window) +
                                    if (entry.enabled) "" else " (in der Shelly-App deaktiviert)",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(
                                onClick = { onRemove(entry) },
                                enabled = connected,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Text(
                                    "−",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (warning != null) {
        AlertDialog(
            onDismissRequest = { warning = null },
            confirmButton = { TextButton(onClick = { warning = null }) { Text("OK") } },
            title = { Text("Nicht übernommen") },
            text = { Text(warning!!) },
        )
    }
    if (pickStart) {
        TimePickerDialog(
            initialMin = startMin,
            onDismiss = { pickStart = false },
            onConfirm = { pickStart = false; startMin = it },
        )
    }
    if (pickEnd) {
        TimePickerDialog(
            initialMin = endMin,
            onDismiss = { pickEnd = false },
            onConfirm = { pickEnd = false; endMin = it },
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
                        Text(
                            Fmt.ringSummary(event.count, event.durationS),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
