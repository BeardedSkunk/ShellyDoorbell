package de.beardedskunk.shellydoorbell.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
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
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var timeoutJob: Job? = null
    private var soundJob: Job? = null

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
        soundJob?.cancel()
        soundJob = null
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
        vibrator?.cancel()
        vibrator = null
        _active.value = false
    }

    private fun startSound(soundUri: Uri?) {
        // Der gewaehlte Ton kann ungueltig oder (ohne Medien-Berechtigung) fuer
        // MediaPlayer unlesbar sein -> pro Kandidat erst MediaPlayer, dann die
        // Ringtone-API (spielt notfalls im Systemprozess) probieren, damit der
        // Alarm moeglichst mit dem Wunschton und nie stumm losgeht.
        val candidates = listOfNotNull(
            soundUri,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ).distinct()
        soundJob?.cancel()
        soundJob = scope.launch {
            for (uri in candidates) {
                if (tryMediaPlayer(uri)) return@launch
                if (tryRingtone(uri)) return@launch
            }
        }
    }

    private fun alarmAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun tryMediaPlayer(uri: Uri): Boolean {
        val candidate = MediaPlayer()
        val ok = runCatching {
            candidate.setAudioAttributes(alarmAttributes())
            candidate.setDataSource(context, uri)
            candidate.isLooping = true
            candidate.prepare()
            candidate.start()
        }.isSuccess
        if (ok) {
            player = candidate
            return true
        }
        candidate.release()
        return false
    }

    private suspend fun tryRingtone(uri: Uri): Boolean {
        val r = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull() ?: return false
        // Vor dem isPlaying-Check merken, damit stop() den Ton immer erwischt
        ringtone = r
        val started = runCatching {
            r.audioAttributes = alarmAttributes()
            r.isLooping = true
            r.play()
        }.isSuccess
        if (started) {
            // play() wirft bei Fehlern nicht -> kurz warten und nachpruefen
            delay(300)
            if (runCatching { r.isPlaying }.getOrDefault(false)) return true
        }
        runCatching { r.stop() }
        ringtone = null
        return false
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
