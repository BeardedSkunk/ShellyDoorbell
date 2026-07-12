package de.beardedskunk.shellydoorbell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.beardedskunk.shellydoorbell.data.Prefs
import de.beardedskunk.shellydoorbell.service.DoorbellService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Startet den Lausch-Service nach dem Booten bzw. nach App-Updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val autostart = runBlocking { Prefs(context).settings.first().autostart }
        if (autostart) DoorbellService.start(context)
    }
}
