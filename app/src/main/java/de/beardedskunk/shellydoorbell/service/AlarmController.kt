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

    /** Wunschton, fuer den unten vorgewaermt wurde (null = noch nichts vorbereitet). */
    @Volatile
    private var warmFor: Uri? = null

    /** Fertig geoeffneter Player — wartet nur noch auf start(). */
    @Volatile
    private var warmPlayer: MediaPlayer? = null

    /** Fertig erzeugtes Ringtone-Objekt fuer den Umweg ueber den Systemprozess. */
    @Volatile
    private var warmRingtone: Ringtone? = null

    private var warmJob: Job? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /**
     * Bereitet den Alarmton vor, damit beim Klingeln nichts mehr zu tun ist.
     *
     * Hintergrund (am Pixel gemessen): zwischen der Alarm-Benachrichtigung und dem
     * ersten Ton lagen **viereinhalb Sekunden** — lange genug, dass man die
     * Meldung wegdrueckt, bevor ueberhaupt etwas zu hoeren ist. Die Zeit ging fuer
     * das Suchen drauf: Die Toene des Sound-Pickers liegen im MediaStore, den
     * MediaPlayer ohne READ_MEDIA_AUDIO nicht oeffnen darf; also faellt
     * [startSound] auf die Ringtone-API zurueck, und deren Erzeugen geht ueber den
     * Medien-Provider. Beides passiert jetzt im Voraus (beim Dienststart, nach
     * jedem Tonwechsel und nach jedem Alarm), das Klingeln startet nur noch.
     *
     * Schlaegt beides fehl, aendert sich nichts: [startSound] laeuft dann wie
     * bisher die Kandidatenkette durch.
     */
    fun prepare(soundUri: Uri?) {
        if (warmFor == soundUri && (warmPlayer != null || warmRingtone != null)) return
        warmJob?.cancel()
        warmJob = scope.launch {
            dropWarm()
            warmFor = soundUri
            val uri = soundUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return@launch
            // Nur den WUNSCHton vorwaermen, nie einen Ersatzton: sonst gaebe der
            // vorbereitete Standardton den Ausschlag, obwohl der Wunschton ueber
            // die Ringtone-API sehr wohl spielbar waere (Reihenfolge in startSound).
            warmPlayer = opened(uri)
            // Das Ringtone-Objekt kostet den Loewenanteil der Anlaufzeit; play()
            // selbst ist billig. Auch dann vorhalten, wenn der Player klappt —
            // faellt der beim Starten aus, greift es ohne neue Wartezeit.
            warmRingtone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull()
        }
    }

    /** Vorgewaermtes wegwerfen (Tonwechsel, Dienstende). */
    private fun dropWarm() {
        warmPlayer?.let { runCatching { it.release() } }
        warmPlayer = null
        warmRingtone = null
    }

    /** Beim Herunterfahren des Dienstes aufraeumen. */
    fun release() {
        // Reihenfolge: erst stoppen (das waermt selbst wieder vor), dann den
        // frischen Auftrag abbrechen und wegwerfen — sonst bliebe ein Player offen.
        stop()
        warmJob?.cancel()
        dropWarm()
        warmFor = null
    }

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
        // Fuers naechste Klingeln gleich wieder vorwaermen (der eben verbrauchte
        // Player/Ringtone ist weg). Kostet nichts, wenn schon etwas bereitliegt.
        prepare(warmFor)
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

    /** Player fuer [uri] oeffnen und vorbereiten — ohne zu spielen (siehe [prepare]). */
    private fun opened(uri: Uri): MediaPlayer? {
        val candidate = MediaPlayer()
        val ok = runCatching {
            candidate.setAudioAttributes(alarmAttributes())
            candidate.setDataSource(context, uri)
            candidate.isLooping = true
            candidate.prepare()
        }.isSuccess
        if (ok) return candidate
        candidate.release()
        return null
    }

    private fun tryMediaPlayer(uri: Uri): Boolean {
        // Vorgewaermten Player nehmen, wenn er zu genau diesem Ton gehoert — dann
        // ist nur noch start() zu tun (das Oeffnen/Dekodieren ist schon erledigt).
        val candidate = warmPlayer?.takeIf { warmFor == uri }?.also { warmPlayer = null }
            ?: opened(uri)
            ?: return false
        if (runCatching { candidate.start() }.isSuccess) {
            player = candidate
            return true
        }
        candidate.release()
        return false
    }

    private suspend fun tryRingtone(uri: Uri): Boolean {
        // Vorgewaermtes Objekt fuer genau diesen Ton nehmen: das Erzeugen laeuft
        // ueber den Medien-Provider und kostet den Grossteil der Anlaufzeit.
        val r = warmRingtone?.takeIf { warmFor == uri }?.also { warmRingtone = null }
            ?: runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull()
            ?: return false
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
