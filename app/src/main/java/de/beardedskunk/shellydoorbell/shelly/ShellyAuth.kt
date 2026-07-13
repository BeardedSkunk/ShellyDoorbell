package de.beardedskunk.shellydoorbell.shelly

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Digest-Authentifizierung fuer die Shelly-Gen2+/Gen3-RPC-API.
 *
 * Ist auf dem Shelly Passwortschutz aktiv, beantwortet das Geraet
 * nicht-authentifizierte Calls mit Fehlercode 401; die Fehler-Message ist ein
 * JSON mit der Challenge:
 *
 *   { "auth_type":"digest", "nonce":"<opak>", "nc":1, "realm":"<id>", "algorithm":"SHA-256" }
 *
 * Wichtig (an einem Plug M Gen3 verifiziert): die "nonce" ist ein *String*
 * (kein Integer), und sie ist einmal-gueltig — wird sie mit demselben "nc"
 * erneut benutzt, weist das Geraet das als Replay mit 401 ab. Man darf dieselbe
 * nonce aber wiederverwenden, solange man "nc" hochzaehlt und im auth-Objekt
 * mitschickt. Genau das macht [authObject]; [ShellyClient] fuehrt den Zaehler.
 *
 * Der Benutzer ist bei Shelly immer "admin", das Passwort ist das in der
 * Web-UI gesetzte.
 */
object ShellyAuth {

    const val USER = "admin"

    /**
     * Aus dem 401-Error-Frame geparste Challenge (nonce als String!).
     * [nc] ist der vom Geraet als naechstes erwartete Zaehlerstand — damit kann
     * sich der Client nach einem Replay-401 wieder auf das Geraet synchronisieren.
     */
    data class Challenge(val realm: String, val nonce: String, val nc: Int) {
        companion object {
            /** Parst die Challenge aus der Fehler-Message; null, wenn kein Digest-Challenge-JSON. */
            fun parse(message: String?): Challenge? {
                val json = runCatching { JSONObject(message ?: return null) }.getOrNull() ?: return null
                val realm = json.optString("realm").takeIf { it.isNotBlank() } ?: return null
                val nonce = json.optString("nonce").takeIf { it.isNotBlank() } ?: return null
                return Challenge(realm = realm, nonce = nonce, nc = json.optInt("nc", 1))
            }
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Baut das auth-Objekt fuer einen Request.
     *
     *   ha1      = SHA256("admin:" + realm + ":" + password)
     *   ha2      = SHA256("dummy_method:dummy_uri")   (fester String)
     *   response = SHA256(ha1:nonce:nc:cnonce:auth:ha2)
     *
     * [nc] muss pro Request mit derselben nonce hochgezaehlt werden und wird im
     * Objekt mitgeschickt (sonst erkennt das Geraet die Wiederverwendung nicht).
     */
    fun authObject(challenge: Challenge, nc: Int, cnonce: Long, password: String): JSONObject {
        val ha1 = sha256Hex("$USER:${challenge.realm}:$password")
        val ha2 = sha256Hex("dummy_method:dummy_uri")
        val response = sha256Hex("$ha1:${challenge.nonce}:$nc:$cnonce:auth:$ha2")
        return JSONObject()
            .put("realm", challenge.realm)
            .put("username", USER)
            .put("nonce", challenge.nonce)
            .put("cnonce", cnonce)
            .put("nc", nc)
            .put("response", response)
            .put("algorithm", "SHA-256")
    }
}
