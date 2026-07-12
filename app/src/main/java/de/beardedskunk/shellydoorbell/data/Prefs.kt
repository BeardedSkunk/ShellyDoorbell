package de.beardedskunk.shellydoorbell.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Lokale, geraetebezogene Einstellungen (nicht mit anderen Nutzern geteilt). */
data class LocalSettings(
    val ip: String,
    val alarmUri: String?,
    val autostart: Boolean,
    /** false = dieses Handy bleibt bei Klingel-Ereignissen stumm. */
    val alarmEnabled: Boolean,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class Prefs(private val context: Context) {

    private object Keys {
        val IP = stringPreferencesKey("shelly_ip")
        val ALARM_URI = stringPreferencesKey("alarm_uri")
        val AUTOSTART = booleanPreferencesKey("autostart")
        val ALARM_ENABLED = booleanPreferencesKey("alarm_enabled")
    }

    val settings: Flow<LocalSettings> = context.dataStore.data.map { p ->
        LocalSettings(
            ip = p[Keys.IP] ?: DEFAULT_IP,
            alarmUri = p[Keys.ALARM_URI],
            autostart = p[Keys.AUTOSTART] ?: true,
            alarmEnabled = p[Keys.ALARM_ENABLED] ?: true,
        )
    }

    suspend fun setIp(ip: String) {
        context.dataStore.edit { it[Keys.IP] = ip.trim() }
    }

    suspend fun setAlarmUri(uri: String?) {
        context.dataStore.edit { p ->
            if (uri == null) p.remove(Keys.ALARM_URI) else p[Keys.ALARM_URI] = uri
        }
    }

    suspend fun setAutostart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTOSTART] = enabled }
    }

    suspend fun setAlarmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ALARM_ENABLED] = enabled }
    }

    companion object {
        const val DEFAULT_IP = "192.168.178.20"
    }
}
