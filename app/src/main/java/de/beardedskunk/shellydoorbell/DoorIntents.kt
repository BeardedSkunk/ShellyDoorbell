package de.beardedskunk.shellydoorbell

import android.content.Context
import android.content.Intent

/**
 * Anbindung an die Türsprecher-App (falls installiert): Sie exportiert eine
 * Activity mit der Action [ACTION_OPEN_DOOR], die ohne weitere Auswahl direkt
 * die dort als "Tür" hinterlegte Kamera/Szene öffnet. Ist die App nicht
 * installiert, liefert [doorIntent] null und alles läuft wie ohne sie.
 * (Braucht den <queries>-Eintrag im Manifest, sonst ist die Action ab
 * Android 11 unsichtbar.)
 */
object DoorIntents {

    const val ACTION_OPEN_DOOR = "de.videoapp.action.OPEN_DOOR"

    fun doorIntent(context: Context): Intent? {
        val probe = Intent(ACTION_OPEN_DOOR)
        val resolved = context.packageManager.resolveActivity(probe, 0) ?: return null
        return Intent(ACTION_OPEN_DOOR)
            .setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
