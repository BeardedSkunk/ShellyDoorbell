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
- Zusätzlich führt das Script einen **Ringpuffer der letzten ~200
  Klingel-Zeitstempel im KVS** des Shelly. Beim (Re-)Verbinden mergt jede App
  diesen Puffer in ihre lokale Datenbank – so entsteht pro Gerät eine
  lückenlose Langzeit-History (~1 Jahr), solange das Handy gelegentlich
  verbunden ist.
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
| `shelly/doorbell.js`| mJS-Script für den Shelly (Erkennung, Log, Boot-Abgleich)      |
| `shelly/upload.ps1` | PowerShell-Helfer: lädt das Script per RPC hoch und startet es |
| `app/`              | Android-App (Kotlin, Jetpack Compose, minSdk 29)               |

## Shelly einrichten

1. Dem Shelly eine **feste IP** geben (Fritzbox: „Immer die gleiche IP
   zuweisen“). Der Standard in der App ist `192.168.178.20`, änderbar in den
   App-Einstellungen.
2. **Matter deaktivieren** (Geräte-Web-UI → Settings) – Scripting funktioniert
   auf Gen3-Geräten nur ohne Matter.
3. Prüfen, dass das Gerät **Zeit per NTP** bekommt (Web-UI zeigt die Uhrzeit).
4. Script installieren – entweder per PowerShell:

   ```powershell
   cd shelly
   .\upload.ps1 -Ip 192.168.178.20
   ```

   oder von Hand in der Web-UI (`http://<ip>` → Scripts → Add Script): Name
   **`doorbell`** (genau so, die App sucht danach), Inhalt von `doorbell.js`
   einfügen, speichern, starten und **„Run on startup“** aktivieren.
5. Test ohne Klingelknopf: in der Script-Konsole `testRing()` aufrufen – alle
   verbundenen Handys müssen Alarm schlagen.

Die Watt-Schwelle (Default 2 W) muss über der Standby-Leistung des
Klingeltrafos liegen und unter der Leistung beim Klingeln – in der App unter
„Erkennung“ einstellbar, die Live-Watt-Anzeige hilft beim Kalibrieren.

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

## Gemeinsame Einstellungen (KVS-Schema)

| Key                     | Bedeutung                                          |
| ----------------------- | -------------------------------------------------- |
| `dbell_cfg_threshold_w` | Watt-Schwelle für „es klingelt“ (Default 2.0)      |
| `dbell_cfg_debounce_s`  | Sperrzeit nach einem Klingeln in s (Default 30)    |
| `dbell_ring_ids`        | Klingelzeiten: JSON-Liste der Schedule-Job-Paare `[[einId,ausId],…]` |
| `dbell_mute_until`      | „Ruhe bis“: Unix-Zeit, bis zu der die Klingel stumm ist |
| `dbell_log_head`        | Index des aktuellen Log-Chunks                     |
| `dbell_log_0` … `_9`    | Ringpuffer: JSON-Arrays mit Unix-Timestamps        |

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
- **KVS-Limits:** max. 50 Keys à 253 Zeichen. Der Ringpuffer nutzt davon 11
  Keys für ~200 Ereignisse; ältere Ereignisse leben nur noch in den lokalen
  App-Datenbanken.
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
