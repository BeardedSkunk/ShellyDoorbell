package de.beardedskunk.shellydoorbell

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
    }
}

object Channels {
    const val SERVICE = "service"

    /**
     * Klingeln unterwegs bei aktivem „Nicht stoeren": bewusst OHNE setBypassDnd, ohne Ton,
     * ohne Vibration. Daheim durchbricht der Alarm „Nicht stoeren" absichtlich (Kanal unten);
     * unterwegs soll er es nicht — so hat es der Nutzer entschieden (docs/vpn-von-unterwegs.md).
     * Der Dienst waehlt diesen Kanal nur, wenn die Verbindung ueber den Tunnel laeuft UND
     * „Nicht stoeren" an ist; sonst klingelt es wie daheim.
     */
    const val RING_QUIET = "ring_quiet"

    private const val SP = "channels"
    private const val KEY_ALARM_VER = "alarm_ver"

    fun alarmChannelId(context: Context): String =
        "alarm_v" + context.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt(KEY_ALARM_VER, 1)

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                SERVICE,
                context.getString(R.string.channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                RING_QUIET,
                context.getString(R.string.channel_ring_quiet),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                alarmChannelId(context),
                context.getString(R.string.channel_alarm),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                // Greift nur, wenn die App "Nicht stoeren"-Zugriff hat (siehe Einstellungen).
                setBypassDnd(true)
                // Ton + Vibration macht der Service selbst (geloopt, Wecker-Lautstaerke).
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun canBypassDnd(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Das Kanal-Flag allein reicht nicht: Entzieht der Nutzer den
        // "Nicht stoeren"-Zugriff wieder, bleibt es am Kanal stehen.
        return nm.isNotificationPolicyAccessGranted &&
            nm.getNotificationChannel(alarmChannelId(context))?.canBypassDnd() == true
    }

    /**
     * Kanal-Einstellungen sind nach dem Anlegen eingefroren. Hat der Nutzer den
     * "Nicht stoeren"-Zugriff nachtraeglich erteilt, wird eine neue Kanal-Version
     * angelegt, damit setBypassDnd wirken kann.
     */
    fun upgradeAlarmChannelIfPossible(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted || canBypassDnd(context)) return
        val sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val oldId = alarmChannelId(context)
        sp.edit { putInt(KEY_ALARM_VER, sp.getInt(KEY_ALARM_VER, 1) + 1) }
        nm.deleteNotificationChannel(oldId)
        ensure(context)
    }
}
