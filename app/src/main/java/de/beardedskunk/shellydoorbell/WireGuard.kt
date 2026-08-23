package de.beardedskunk.shellydoorbell

import android.content.Context

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

    /** Nicht installiert (oder unsichtbar) heisst NameNotFoundException — beides ist „nein". */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)
}
