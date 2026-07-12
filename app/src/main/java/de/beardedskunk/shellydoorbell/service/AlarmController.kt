package de.beardedskunk.shellydoorbell.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Spielt den Klingel-Alarm als Dauerton auf dem Wecker-Stream (unabhaengig von
 * der Klingelton-Lautstaerke), plus Vibration — bis jemand auf Stopp drueckt
 * oder die Sicherheitsabschaltung greift.
 */
class AlarmController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var timeoutJob: Job? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun start(soundUri: Uri?) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(MAX_DURATION_MS)
            stop()
        }
        if (_active.value) return // laeuft schon, nur die Laufzeit wurde verlaengert
        _active.value = true
        startSound(soundUri)
        startVibration()
    }

    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        _active.value = false
    }

    private fun startSound(soundUri: Uri?) {
        // Der gewaehlte Ton kann ungueltig geworden sein (Datei geloescht etc.) ->
        // der Reihe nach durchprobieren, damit der Alarm nie stumm bleibt.
        val candidates = listOfNotNull(
            soundUri,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ).distinct()
        for (uri in candidates) {
            val candidate = MediaPlayer()
            val ok = runCatching {
                candidate.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                candidate.setDataSource(context, uri)
                candidate.isLooping = true
                candidate.prepare()
                candidate.start()
            }.isSuccess
            if (ok) {
                player = candidate
                return
            }
            candidate.release()
        }
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator = v
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
        }
    }

    companion object {
        /** Sicherheitsabschaltung, falls niemand den Alarm wegdrueckt. */
        private const val MAX_DURATION_MS = 10 * 60_000L
    }
}
