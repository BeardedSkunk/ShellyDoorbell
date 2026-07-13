@file:OptIn(ExperimentalMaterial3Api::class)

package de.beardedskunk.shellydoorbell.ui

import android.app.NotificationManager
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.beardedskunk.shellydoorbell.Channels
import de.beardedskunk.shellydoorbell.data.Prefs
import de.beardedskunk.shellydoorbell.service.ConnCheck
import de.beardedskunk.shellydoorbell.service.DoorbellService
import de.beardedskunk.shellydoorbell.shelly.ConnectionState
import de.beardedskunk.shellydoorbell.shelly.SharedSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Lokale Einstellungen dieses Geraets + Berechtigungs-Checkliste. */
@Composable
fun SettingsScreen(
    service: DoorbellService,
    resumeTick: Int,
    onPickRingtone: (Intent) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val settings by prefs.settings.collectAsState(initial = null)
    var ipField by remember(settings?.ip) { mutableStateOf(settings?.ip ?: "") }
    var pwField by remember(settings?.password) { mutableStateOf(settings?.password ?: "") }
    var pwVisible by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<ConnCheck?>(null) }
    val shared by service.shared.collectAsState()
    val conn by service.connectionState.collectAsState()
    val connected = conn is ConnectionState.Connected
    val listenOnly = settings?.listenOnly ?: false

    // Berechtigungs-Status; resumeTick sorgt fuer Neubewertung nach Rueckkehr aus den System-Settings
    val powerManager = context.getSystemService(PowerManager::class.java)
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val batteryOk = remember(resumeTick) { powerManager.isIgnoringBatteryOptimizations(context.packageName) }
    val fullScreenOk = remember(resumeTick) {
        Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()
    }
    val dndBypassOk = remember(resumeTick) { Channels.canBypassDnd(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shelly", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = ipField,
                        onValueChange = { ipField = it },
                        label = { Text("IP-Adresse") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Lauschmodus (ohne Passwort)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Nur mithören: Klingel-Alarm läuft ohne Passwort. Schalten, " +
                                    "Klingelzeiten und Einstellungen sind ausgeblendet.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = listenOnly,
                            onCheckedChange = { on -> scope.launch { prefs.setListenOnly(on) } },
                            enabled = settings != null,
                        )
                    }
                    if (!listenOnly) {
                        OutlinedTextField(
                            value = pwField,
                            onValueChange = { pwField = it },
                            label = { Text("Passwort (nur falls am Shelly gesetzt)") },
                            singleLine = true,
                            visualTransformation = if (pwVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                TextButton(onClick = { pwVisible = !pwVisible }) {
                                    Text(if (pwVisible) "Verbergen" else "Zeigen")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Benutzer ist beim Shelly immer „admin“. Leer lassen, wenn kein " +
                                "Passwortschutz aktiv ist.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val ipChanged = ipField.isNotBlank() && ipField.trim() != settings?.ip
                    val pwChanged = pwField != (settings?.password ?: "")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                checking = true
                                checkResult = null
                                scope.launch {
                                    // Erst die eingetippten Zugangsdaten übernehmen (persistent
                                    // + sofort in die laufende Verbindung), dann prüfen – sonst
                                    // liefe die Prüfung noch gegen das alte Passwort.
                                    val ipVal = ipField.trim().ifBlank { settings?.ip ?: "" }
                                    if (ipChanged) prefs.setIp(ipVal)
                                    if (pwChanged) prefs.setPassword(pwField)
                                    service.applyCredentials(ipVal, pwField)
                                    val result = service.checkConnection()
                                    // Bei Erfolg die Hauptdaten gleich mit den (nun gültigen)
                                    // Zugangsdaten nachladen – sequentiell nach der Prüfung.
                                    if (result.ok) service.reloadSettings()
                                    checkResult = result
                                    checking = false
                                }
                            },
                            enabled = !checking && ipField.isNotBlank(),
                        ) {
                            Text("Verbindung prüfen")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    if (ipChanged) prefs.setIp(ipField)
                                    if (pwChanged) prefs.setPassword(pwField)
                                }
                            },
                            enabled = ipField.isNotBlank() && (ipChanged || pwChanged),
                        ) {
                            Text("Übernehmen")
                        }
                    }
                }
            }

            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Alarm", style = MaterialTheme.typography.titleMedium)
                    val toneName = remember(settings?.alarmUri) {
                        val custom = settings?.alarmUri?.toUri()
                        val uri = custom ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }
                            .getOrNull()
                            // Titel kann ohne Medien-Berechtigung unlesbar sein -> nicht
                            // faelschlich "Standard" anzeigen, der Ton spielt trotzdem
                            ?: if (custom != null) "Eigener Ton" else "Standard-Weckton"
                    }
                    Text("Alarmton: $toneName", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarmton wählen")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    settings?.alarmUri?.toUri()
                                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                                )
                            }
                            onPickRingtone(intent)
                        }) {
                            Text("Ton wählen")
                        }
                        OutlinedButton(onClick = { service.testAlarm() }) {
                            Text("Alarm testen")
                        }
                    }
                    Text(
                        "Der Alarm läuft über die Wecker-Lautstärke und klingelt so lange, " +
                            "bis er weggedrückt wird (max. 10 Minuten).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // "Erkennung" schreibt in den Shelly-KVS -> im Lauschmodus ausblenden.
            if (!listenOnly) {
                SharedCard(shared, connected, onSave = { t, d -> service.saveShared(t, d) })
            }

            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Autostart nach Boot", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Lausch-Dienst startet automatisch nach dem Einschalten des Handys",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = settings?.autostart ?: true,
                            onCheckedChange = { on -> scope.launch { prefs.setAutostart(on) } },
                        )
                    }
                }
            }

            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Zuverlässigkeit", style = MaterialTheme.typography.titleMedium)
                    PermissionRow(
                        ok = batteryOk,
                        title = if (batteryOk) "Akku-Optimierung aus" else "Akku-Optimierung aktiv",
                        detail = if (batteryOk) {
                            "Android legt den Lausch-Dienst nicht schlafen."
                        } else {
                            "Android kann den Lausch-Dienst einschläfern – bitte Ausnahme erlauben."
                        },
                        buttonText = "Erlauben",
                    ) {
                        @Suppress("BatteryLife")
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                "package:${context.packageName}".toUri()
                            )
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 34) {
                        PermissionRow(
                            ok = fullScreenOk,
                            title = "Vollbild-Alarm",
                            detail = "Alarm darf sich über den Sperrbildschirm legen",
                            buttonText = "Einstellen",
                        ) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    "package:${context.packageName}".toUri()
                                )
                            )
                        }
                    }
                    PermissionRow(
                        ok = dndBypassOk,
                        title = "„Nicht stören“ durchbrechen",
                        detail = if (dndBypassOk) {
                            "Der Alarm erscheint auch bei aktivem Nicht-stören-Modus."
                        } else {
                            "Alarm-Benachrichtigung erscheint auch bei aktivem Nicht-stören-Modus. " +
                                "Dafür der App den Nicht-stören-Zugriff geben und danach hierher zurückkehren."
                        },
                        buttonText = if (dndBypassOk) "Ändern" else "Zugriff geben",
                        notOkIcon = DndIcon,
                        alwaysShowButton = true,
                    ) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                }
            }
        }

        if (checking || checkResult != null) {
            ConnectionCheckDialog(
                checking = checking,
                result = checkResult,
                onDismiss = { if (!checking) checkResult = null },
            )
        }
    }
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
private fun ConnectionCheckDialog(checking: Boolean, result: ConnCheck?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !checking) { Text("OK") }
        },
        title = { Text("Verbindung prüfen") },
        text = {
            if (checking || result == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("Prüfe Verbindung …")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Eine Gesamtaussage: erreichbar + Passwort ok + Script läuft.
                    // Fehlendes/veraltetes/gestopptes Script repariert die Prüfung selbst.
                    CheckRow(ok = result.ok, label = "Shelly-Verbindung & doorbell-Script")
                    if (result.detail != null) {
                        Text(result.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

@Composable
private fun CheckRow(ok: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Filled.Check else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) Color(0xFF43A047) else MaterialTheme.colorScheme.error,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun PermissionRow(
    ok: Boolean,
    title: String,
    detail: String,
    buttonText: String,
    notOkIcon: ImageVector = Icons.Filled.Warning,
    alwaysShowButton: Boolean = false,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Filled.Check else notOkIcon,
            contentDescription = null,
            tint = if (ok) Color(0xFF43A047) else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok || alwaysShowButton) {
            OutlinedButton(onClick = onClick) { Text(buttonText) }
        }
    }
}

/**
 * "Nicht stoeren"-Symbol (Kreis mit Querbalken) — die schlanke
 * material-icons-core-Abhaengigkeit enthaelt es nicht, daher hier als Pfad.
 */
private val DndIcon: ImageVector = materialIcon(name = "Filled.DoNotDisturbOn") {
    materialPath {
        moveTo(12.0f, 2.0f)
        curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
        reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
        reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
        reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
        close()
        moveTo(17.0f, 13.0f)
        lineTo(7.0f, 13.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineToRelative(10.0f)
        verticalLineToRelative(2.0f)
        close()
    }
}
