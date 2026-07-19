# ShellyDoorbell – Klingelüberwachung

Android-App + Shelly-Script für eine „smarte“ Türklingel-Überwachung: Ein
**Shelly Plug M Gen3** versorgt den Klingeltrafo und misst dessen
Leistungsaufnahme. Drückt jemand den Klingelknopf, steigt die Wirkleistung –
der Shelly meldet das sofort per WebSocket an alle Handys im Haus, die einen
Dauer-Alarm spielen (über die Wecker-Lautstärke, auch über dem Sperrbildschirm).

> Privates Bastelprojekt, entstanden per Vibe-Coding. Issues und Pull Requests
> werden nicht betreut – Forken ist natürlich trotzdem erlaubt.

## Funktionsweise

- **Die Handys verbinden sich zum Shelly** (`ws://<ip>/rpc`), nicht umgekehrt.
  Jede App hält über einen Foreground-Service eine dauerhafte
  WebSocket-Verbindung (nur WLAN, nie Mobilfunk). Idle-Traffic: nur ein Ping
  alle 25 s ≈ wenige KB/h.
- Das Script `shelly/doorbell.js` läuft auf dem Shelly, erkennt Klingeln über
  die Wirkleistung (Schwelle + Sperrzeit einstellbar) und broadcastet
  `Shelly.emitEvent("doorbell", {ts, power})` an alle verbundenen Apps.
- **Klingel-Ereignisse statt Einzeltöne:** Das Script zählt jeden Tastendruck
  (Flanke über die Schwelle) und fasst Drücke, die innerhalb von 3 Minuten
  aufeinander folgen, zu einem Ereignis zusammen. In der History steht das dann
  als `3x in 3:20` (Anzahl / Dauer), ein Einzeldruck als `1x in 0:01`. Der
  Alarm selbst bleibt davon unberührt (er ist über die Sperrzeit gedrosselt).
- Zusätzlich führt das Script einen **Ringpuffer der letzten Klingel-Ereignisse
  im KVS** des Shelly (`{t, n, d}` = Start, Anzahl, Dauer). Beim (Re-)Verbinden
  mergt jede App diesen Puffer in ihre lokale Datenbank – so entsteht pro Gerät
  eine Langzeit-History, solange das Handy gelegentlich verbunden ist.
- Das Script sendet alle 30 s ein **Lebenszeichen** (`doorbell_hb` mit
  Versionsnummer). Das ist der einzige Kanal, über den auch Apps **ohne
  Passwort** erfahren, dass (und welche Version) das Script läuft.
- **Klingelzeiten** sind Erlaubnisfenster: Nur innerhalb ist die Klingel
  aktiv, außerhalb ist der Trafo stromlos; ohne Klingelzeiten ist sie immer
  aktiv. Jedes Fenster ist ein Ein/Aus-Paar ganz normaler Shelly-Schedules,
  mehrere Fenster parallel sind möglich (z. B. Werktage und Wochenende
  getrennt, max. 10). Sie sind auch in der offiziellen Shelly-App sichtbar
  und änderbar.
- **Gemeinsame Einstellungen** (Watt-Schwelle, Sperrzeit, Ruhezeiten) liegen
  auf dem Shelly (KVS bzw. Schedules) und gelten für alle Nutzer – ohne
  Konten, ohne Passwort. Nach jeder Änderung broadcastet das Script
  `doorbell_cfg`, damit alle Apps ihre Anzeige aktualisieren.

## Komponenten

| Pfad                | Inhalt                                                        |
| ------------------- | ------------------------------------------------------------- |
| `shelly/doorbell.js`| mJS-Script für den Shelly (Erkennung, Log, Boot-Abgleich). Wird als Asset in die App gebündelt; die erste Zeile trägt die Script-Version |
| `shelly/upload.ps1` | PowerShell-Helfer: lädt das Script per RPC hoch (nur Handbetrieb nötig, die App macht das selbst) |
| `app/`              | Android-App (Kotlin, Jetpack Compose, minSdk 29)               |

## Shelly einrichten

1. Dem Shelly eine **feste IP** geben (Fritzbox: „Immer die gleiche IP
   zuweisen“). Der Standard in der App ist `192.168.178.20`, änderbar in den
   App-Einstellungen.
2. **Matter deaktivieren** (Geräte-Web-UI → Settings) – Scripting funktioniert
   auf Gen3-Geräten nur ohne Matter.
3. Prüfen, dass das Gerät **Zeit per NTP** bekommt (Web-UI zeigt die Uhrzeit).
4. **Das doorbell-Script installiert die App selbst:** Beim Verbinden prüft sie
   Existenz, Version und Laufzustand des Scripts – fehlt es, ist es veraltet
   oder gestoppt, wird es automatisch eingespielt bzw. neu gestartet (bei
   aktivem Passwortschutz erst nach Eintragen des Passworts). Alternativ geht
   es weiter von Hand, per PowerShell:

   ```powershell
   cd shelly
   .\upload.ps1 -Ip 192.168.178.20
   ```

   oder in der Web-UI (`http://<ip>` → Scripts → Add Script): Name
   **`doorbell`** (genau so, die App sucht danach), Inhalt von `doorbell.js`
   einfügen, speichern, starten und **„Run on startup“** aktivieren.
5. Test ohne Klingelknopf: in der Script-Konsole `testRing()` aufrufen – alle
   verbundenen Handys müssen Alarm schlagen.

Die Watt-Schwelle (Default 2 W) muss über der Standby-Leistung des
Klingeltrafos liegen und unter der Leistung beim Klingeln – in der App unter
Einstellungen → „Erkennung“ einstellbar, die Live-Watt-Anzeige hilft beim
Kalibrieren.

### Passwortschutz (optional)

Wird in der Shelly-Web-UI ein **Passwort** gesetzt, verlangt das Gerät ab dann
für Schalt- und Einstellungsbefehle eine Authentifizierung (Benutzer ist immer
`admin`). Die Klingel-Events erreichen die Apps **auch ohne Passwort** weiter
(Broadcasts gehen an alle verbundenen Clients – am Gerät verifiziert), der
Alarm funktioniert also selbst mit fehlendem/falschem Passwort. Nur Schalten,
Einstellungen und die Script-Pflege brauchen das Passwort. Deshalb:

- **Lauschmodus** (Einstellungen → Shelly): Wer nur mithören will, setzt den
  Haken statt eines Passworts. Die App verzichtet dann auf alle Auth-Aufrufe und
  blendet Schalter, Klingelzeiten und Einstellungen aus – Klingel-Alarm und
  History laufen weiter. Am Lebenszeichen des Scripts erkennt die App auch ohne
  Passwort, ob es läuft. (Grenze: die Langzeit-History aus dem KVS lässt sich
  ohne Passwort nicht nachladen, ein Lauscher sammelt ab dem Verbinden.)

- Das Passwort in der App unter **Einstellungen → Shelly → Passwort** eintragen
  und „Übernehmen“. Es wird pro Gerät lokal gespeichert (nicht im KVS, das ja
  gerade geschützt wird).
- Der Button **„Verbindung prüfen“** testet bei jedem Druck frisch (auch eine
  Passwortänderung am Shelly selbst wird erkannt): Shelly erreichbar, Passwort
  gültig, doorbell-Script läuft – und repariert das Script dabei gleich mit.
- Schlägt ein Befehl mangels Passwort fehl, bietet die App direkt einen Dialog
  zur Passworteingabe an.
- Meldet der Shelly „zu viele Anfragen“ (429), ist das sein Brute-Force-Schutz
  nach wiederholten Fehl-Logins – kurz warten; Verbindungen „verbraucht“ ein
  Fehlversuch nicht. Die App sendet nach einem erkannten Passwortfehler von
  sich aus keine weiteren Anmeldeversuche.

Die Verbindung bleibt ein unverschlüsselter WebSocket im Heim-WLAN – das
Passwort schützt den Zugriff, nicht die Übertragung (Shelly-LAN-Design).

## App bauen & installieren

```
gradlew assembleRelease
adb install app\build\outputs\apk\release\app-release.apk
```

(Das Release-APK ist bewusst mit dem Debug-Key signiert, damit es ohne
eigenen Keystore auf die eigenen Geräte kommt. `local.properties` mit
`sdk.dir=<Pfad zum Android-SDK>` wird lokal benötigt.)

Beim ersten Start führt die App durch die nötigen Berechtigungen
(Einstellungen → „Zuverlässigkeit“):

- **Benachrichtigungen** erlauben (Alarm + Dienststatus),
- **Akku-Optimierung deaktivieren** (sonst schläfert Android den
  Lausch-Dienst ein),
- **Vollbild-Alarm** erlauben (Android 14+),
- optional: **„Nicht stören“-Zugriff**, damit der Alarm auch bei aktivem
  Nicht-stören-Modus erscheint. (Der Alarmton selbst läuft ohnehin über den
  Wecker-Kanal, den „Nicht stören“ standardmäßig durchlässt.)

## Türsprecher-Anbindung (optional)

Ist die Türsprecher-App (`de.videoapp`) installiert, zeigen Klingel-Notification
und Vollbild-Alarm zusätzlich **„🚪 Tür ansehen“**: stoppt den Alarm und öffnet
direkt die dort als Türkamera hinterlegte Station/Szene (in der
Türsprecher-Übersicht per langem Druck festlegen), auch über dem
Sperrbildschirm. Technisch: Intent-Action `de.videoapp.action.OPEN_DOOR` plus
`<queries>`-Eintrag; ohne die App fehlt der Button einfach, sonst ändert sich
nichts.

## Gemeinsame Einstellungen (KVS-Schema)

| Key                     | Bedeutung                                          |
| ----------------------- | -------------------------------------------------- |
| `dbell_cfg_threshold_w` | Watt-Schwelle für „es klingelt“ (Default 2.0)      |
| `dbell_cfg_debounce_s`  | Sperrzeit nach einem Klingeln in s (Default 30)    |
| `dbell_ring_ids`        | Klingelzeiten: JSON-Liste der Schedule-Job-Paare `[[einId,ausId],…]` |
| `dbell_mute_until`      | „Ruhe bis“: Unix-Zeit, bis zu der die Klingel stumm ist |
| `dbell_log_fmt`         | Format-Marke des Ringpuffers (aktuell 2)           |
| `dbell_log_head`        | Index des aktuellen Log-Chunks                     |
| `dbell_log_0` … `_9`    | Ringpuffer: JSON-Arrays mit Ereignis-Objekten `{t, n, d}` (Start, Anzahl, Dauer in s) |

## Verhalten von Klingel-Schalter und Klingelzeiten

- Der **Schalterzustand des Shelly ist die Wahrheit**. Der große Toggle in der
  App (und der Schalter in der Shelly-App) wirken sofort.
- Klingelzeiten schalten zeitgesteuert ein (Fensterbeginn) und aus
  (Fensterende). Ein manueller Eingriff gilt damit höchstens bis zur
  nächsten Schaltflanke.
- **„Ruhe bis HH:MM“** schaltet die Klingel sofort stumm – ohne Schedule.
  Beim Ablauf schaltet das Script auf dem Shelly gemäß Klingelzeiten zurück,
  löscht den KVS-Key und informiert alle Apps; es bleibt nichts zurück, das
  am nächsten Tag erneut zuschlagen könnte. Der laufende Ruhe-Timer ist auf
  allen Geräten sichtbar und kann überall geändert oder beendet werden.
- Nach einem Stromausfall/Neustart gleicht das Script den Zustand ab:
  laufende „Ruhe bis“ → aus; sonst innerhalb irgendeiner Klingelzeit an,
  sonst aus (ohne Klingelzeiten: an).
- Wochentage einer Klingelzeit = Tage, an denen sie **beginnt** (Fenster über
  Mitternacht laufen in den Folgetag). In der App beginnt die Woche mit
  Montag, anders als in der Shelly-Oberfläche.
- Eine neue Klingelzeit, die eine bestehende **überschneidet oder berührt**,
  lehnt die App mit einer Warnung ab: Bei Berührung würden Aus- und Ein-Job
  zur selben Sekunde feuern, die Reihenfolge wäre undefiniert.

## Grenzen & Wissenswertes

- **Max. 6 gleichzeitige Verbindungen:** Laut Shelly-Doku sind 6 simultane
  RPC-Kanäle möglich; Web-UI und Shelly-App belegen zeitweise ebenfalls
  welche. 1–4 Handys sind unkritisch, bei ~6 Geräten bitte real testen.
- **KVS-Limits:** max. 50 Keys à 253 Zeichen. Der Ringpuffer nutzt davon 12
  Keys für ~60 Klingel-Ereignisse; ältere Ereignisse leben nur noch in den
  lokalen App-Datenbanken.
- **Sehr kurze Klingeldrücke:** Der Shelly misst die Leistung ~1× pro
  Sekunde; Drücke deutlich unter einer Sekunde können durchrutschen.
- Der Dauer-Alarm stoppt zur Sicherheit automatisch nach 10 Minuten.

## Auf einem weiteren Rechner entwickeln

```
git clone https://github.com/BeardedSkunk/ShellyDoorbell.git
```

Danach `local.properties` im Projektordner anlegen (Vorlage siehe oben) und
mit Android Studio oder `gradlew assembleDebug` bauen.

## Lizenz

MIT – siehe [LICENSE](LICENSE).
