package de.beardedskunk.shellydoorbell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.beardedskunk.shellydoorbell.service.DoorbellService
import de.beardedskunk.shellydoorbell.ui.Fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Vollbild-Alarm ("Es klingelt!"), erscheint per Full-Screen-Intent auch ueber
 * dem Sperrbildschirm. Der grosse Stopp-Button beendet Ton + Benachrichtigung.
 */
class AlarmActivity : ComponentActivity() {

    private var service: DoorbellService? = null
    private var watchJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? DoorbellService.LocalBinder)?.service
            // Wurde der Alarm anderweitig gestoppt (Notification-Button), schliessen
            watchJob?.cancel()
            watchJob = lifecycleScope.launch {
                service?.alarmActive?.collect { active -> if (!active) finish() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val hasDoor = DoorIntents.doorIntent(this) != null
        setContent {
            AlarmScreen(
                onStop = { stopAlarmAndFinish() },
                onDoor = if (hasDoor) ({ openDoorAndFinish() }) else null,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DoorbellService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        watchJob?.cancel()
        service = null
        runCatching { unbindService(connection) }
        super.onStop()
    }

    private fun stopAlarmAndFinish() {
        service?.stopAlarm()
            ?: startService(
                Intent(this, DoorbellService::class.java).setAction(DoorbellService.ACTION_STOP_ALARM)
            )
        finish()
    }

    /** "Tuer ansehen": Alarm stoppen und die Tuersprecher-App oeffnen. */
    private fun openDoorAndFinish() {
        DoorIntents.doorIntent(this)?.let { runCatching { startActivity(it) } }
        stopAlarmAndFinish()
    }
}

@Composable
private fun AlarmScreen(onStop: () -> Unit, onDoor: (() -> Unit)? = null) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFB71C1C))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(120.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Es klingelt!",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${Fmt.time(System.currentTimeMillis() / 1000)} Uhr",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(48.dp))
            if (onDoor != null) {
                // Tuersprecher-App installiert: ein Tap zeigt die Tuerkamera
                // (und stoppt den Alarm gleich mit)
                Button(
                    onClick = onDoor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFFB71C1C),
                    ),
                ) {
                    Text("🚪 TÜR ANSEHEN", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(16.dp))
            }
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (onDoor != null) Color(0x33FFFFFF) else Color.White,
                    contentColor = if (onDoor != null) Color.White else Color(0xFFB71C1C),
                ),
            ) {
                Text("ALARM STOPPEN", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
