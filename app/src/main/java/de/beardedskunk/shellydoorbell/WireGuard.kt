package de.beardedskunk.shellydoorbell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Was die App über WireGuard wissen kann — und was nicht.
 *
 * **Kann sie:** ob die offizielle App installiert ist (hier), und ob gerade ein VPN-Netz steht
 * (`TRANSPORT_VPN`, siehe `DoorbellService.requestVpn`). Beides ohne Berechtigung.
 *
 * **Kann sie nicht:** in die Tunnelliste der WireGuard-App schauen. Es gibt keinen Provider und
 * keinen Abfrage-Intent; ob ein Tunnel *nach Hause* führt, lässt sich nur beweisen, indem der
 * Shelly hindurch antwortet (siehe `LocalSettings.tunnelReachedAt`). Deshalb muss der Tunnelname
 * eingetippt werden (`LocalSettings.wgTunnel`), er wird für das spätere Schalten gebraucht.
 *
 * Braucht den `<queries><package …>`-Eintrag im Manifest, sonst ist das Paket ab Android 11
 * unsichtbar und [isInstalled] lügt.
 */
object WireGuard {

    const val PACKAGE = "com.wireguard.android"

    /** Von der WireGuard-App deklariert (`dangerous`), muss zur Laufzeit erfragt werden. */
    const val PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"

    // Am Pixel nachgeprueft (23.08.2026, dumpsys package, Version 1.0.20260315): Empfaenger
    // com.wireguard.android/.model.TunnelManager$IntentReceiver fuer genau diese Aktionen.
    private const val ACTION_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
    private const val ACTION_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"

    /** Extra mit dem Tunnelnamen (aus dem Quelltext der WireGuard-App). */
    private const val EXTRA_TUNNEL = "tunnel"

    /** Darf die App Tunnel schalten? Installiert + Berechtigung erteilt. Ob WireGuard die
     *  Fernsteuerung in seinen Einstellungen erlaubt, laesst sich NICHT abfragen — das zeigt sich
     *  erst daran, ob nach [setTunnel] ein VPN-Netz auftaucht (DoorbellService.tunnelAutomation). */
    fun canControl(context: Context): Boolean =
        isInstalled(context) &&
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Tunnel [name] ein- oder ausschalten. Feuer und vergiss: WireGuard antwortet nicht; ob es
     * gewirkt hat, verraet nur das VPN-Netz. false = nicht erlaubt, nichts gesendet.
     */
    fun setTunnel(context: Context, name: String, up: Boolean): Boolean {
        if (name.isBlank() || !canControl(context)) return false
        val intent = Intent(if (up) ACTION_UP else ACTION_DOWN)
            .setPackage(PACKAGE)
            .putExtra(EXTRA_TUNNEL, name)
        context.sendBroadcast(intent)
        return true
    }

    /** Die WireGuard-App oeffnen (fuer „Fernsteuerung erlauben" in deren Einstellungen). */
    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)

    /** Nicht installiert (oder unsichtbar) heisst NameNotFoundException — beides ist „nein". */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)
}
