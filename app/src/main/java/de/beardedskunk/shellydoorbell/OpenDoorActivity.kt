package de.beardedskunk.shellydoorbell

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import de.beardedskunk.shellydoorbell.service.DoorbellService

/**
 * Ziel des "Tür ansehen"-Buttons der Klingel-Notification: stoppt den Alarm und
 * öffnet die Türsprecher-App. (Unsichtbares Trampolin — eine Notification-Action
 * darf seit Android 12 nur direkt eine Activity starten, und wir wollen dabei
 * zusätzlich den Alarm beenden.)
 */
class OpenDoorActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startService(
                Intent(this, DoorbellService::class.java).setAction(DoorbellService.ACTION_STOP_ALARM)
            )
        }
        DoorIntents.doorIntent(this)?.let { runCatching { startActivity(it) } }
        finish()
    }
}
