package de.beardedskunk.shellydoorbell

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import de.beardedskunk.shellydoorbell.service.DoorbellService
import de.beardedskunk.shellydoorbell.ui.AppTheme
import de.beardedskunk.shellydoorbell.ui.HistoryScreen
import de.beardedskunk.shellydoorbell.ui.MainScreen
import de.beardedskunk.shellydoorbell.ui.SettingsScreen

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
                    AppRoot(service = service.value, resumeTick = resumeTick.intValue)
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
private fun AppRoot(service: DoorbellService?, resumeTick: Int) {
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
            onBack = { screen = Screen.Main },
        )
    }
}
