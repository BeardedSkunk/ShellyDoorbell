package de.beardedskunk.shellydoorbell

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import de.beardedskunk.shellydoorbell.data.Prefs
import de.beardedskunk.shellydoorbell.service.DoorbellService
import de.beardedskunk.shellydoorbell.ui.AppTheme
import de.beardedskunk.shellydoorbell.ui.HistoryScreen
import de.beardedskunk.shellydoorbell.ui.MainScreen
import de.beardedskunk.shellydoorbell.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val service = mutableStateOf<DoorbellService?>(null)
    private val resumeTick = mutableIntStateOf(0)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? DoorbellService.LocalBinder)?.service
            service.value = svc
            svc?.setUiVisible(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service.value = null
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Auf Activity-Ebene, nicht im SettingsScreen: waehrend der System-Picker
    // offen ist, wird der Service entbunden, die UI faellt auf den Lade-Spinner
    // zurueck und ein im Composable registrierter Launcher wuerde mitsamt dem
    // Ergebnis entsorgt — der gewaehlte Ton kaeme nie an.
    private val ringtonePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            // "Lautlos" ist im Picker abgeschaltet -> null heisst nie "Ton loeschen"
            if (result.resultCode == RESULT_OK && uri != null) {
                lifecycleScope.launch { Prefs(applicationContext).setAlarmUri(uri.toString()) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DoorbellService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(
                        service = service.value,
                        resumeTick = resumeTick.intValue,
                        onPickRingtone = ringtonePicker::launch,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DoorbellService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        // Falls der Nutzer gerade den "Nicht stoeren"-Zugriff erteilt hat
        Channels.upgradeAlarmChannelIfPossible(this)
    }

    override fun onStop() {
        service.value?.setUiVisible(false)
        service.value = null
        runCatching { unbindService(connection) }
        super.onStop()
    }
}

private enum class Screen { Main, History, Settings }

@Composable
private fun AppRoot(
    service: DoorbellService?,
    resumeTick: Int,
    onPickRingtone: (Intent) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Main) }

    if (service == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }

    when (screen) {
        Screen.Main -> MainScreen(
            service = service,
            onHistory = { screen = Screen.History },
            onSettings = { screen = Screen.Settings },
        )
        Screen.History -> HistoryScreen(onBack = { screen = Screen.Main })
        Screen.Settings -> SettingsScreen(
            service = service,
            resumeTick = resumeTick,
            onPickRingtone = onPickRingtone,
            onBack = { screen = Screen.Main },
        )
    }
}
