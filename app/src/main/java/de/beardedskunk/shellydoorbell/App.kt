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
        return nm.getNotificationChannel(alarmChannelId(context))?.canBypassDnd() == true
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
