# ShellyDoorbell — Entwickler-Einstieg für Claude-Sessions

Zwei Deploy-Artefakte aus einem Repo: eine **Android-App** (`app/`) und ein **mJS-Script fürs
Shelly-Gerät** (`shelly/doorbell.js`). Ein Shelly Plug M Gen3 versorgt den Klingeltrafo und misst
dessen Wirkleistung; steigt sie, meldet das Script das per WebSocket an alle Handys im Haus, die
einen Dauer-Alarm über den Wecker-Stream spielen. Funktionsweise aus Nutzersicht: `README.md`.

Diese Datei ist eine **Landkarte**: wo etwas steht, warum es so gebaut ist, und was einen sonst in
die Falle laufen lässt. Der Code ist dicht kommentiert — er erklärt das Wie. **Gehen Datei und Code
auseinander, gilt der Code**, und die Datei gehört korrigiert.

## Bauen, Testen, Deployen

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ./gradlew :app:assembleDebug
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

- **Der JDK-Pfad in `gradle.properties` ist falsch für diesen Rechner** (`Android Studio` ohne `1`).
  Die eingecheckte Datei **nicht** anpassen — pro Aufruf übersteuern, notfalls zusätzlich mit
  `-Dorg.gradle.java.home=…`. Das Repo liegt auf mehreren Maschinen.
- Toolchain: AGP 9.0.1, Gradle 9.1.0, Kotlin 2.3.20 (+ KSP für Room), compileSdk/targetSdk 36,
  minSdk 29, JVM-Target 17. Klassische DSL (`android.builtInKotlin=false`, `android.newDsl=false`),
  `kotlin.incremental=false` wegen des Virenscanners.
- **Es gibt keine Tests.** `app/src/test/` existiert nicht, `junit` ist nur deklariert. Kein Lint-
  oder Format-Gate. Verifikation heißt hier: kompilieren, aufs Gerät, hinschauen.
- **Das Release-APK ist bewusst mit dem Debug-Key signiert**, damit es ohne eigenen Keystore per USB
  auf die Haushaltsgeräte kommt. `local.properties` mit `sdk.dir=…` wird lokal gebraucht.
- **`.serena/` ist hier gitignored** (anders als in HomeShare). Die Serena-Memories
  (`mem:core`, `tech_stack`, `suggested_commands`, `conventions`, `task_completion`) existieren nur
  lokal auf diesem Rechner — sie sind eine zusätzliche Quelle, aber kein Backup.

## Geräte

- **Shelly Plug M Gen3** am Klingeltrafo, Default-IP `192.168.178.20` (in den App-Einstellungen
  änderbar). Nicht zu verwechseln mit dem Plug am Balkonkraftwerk aus dem ShellyPower-Projekt.
- **F101** (`192.168.178.2:5555`) = Standard-Testgerät.
- **Pixel 8 Pro** = Alltagsgerät des Nutzers — und zugleich das Gerät mit der flakigen Erst-Auth
  (siehe unten). Nur fertige Stände draufspielen.
- **Armor 8** ist gerootet und war das Messgerät für die `tcpdump`-Analyse der Digest-Auth.
- Matter muss auf dem Shelly **aus** sein, sonst läuft auf Gen3 kein Scripting. NTP muss gehen.

## Die zwei Hälften

**Das Script ist die Erkennungs-Wahrheit.** Es misst `switch:0 / apower`, erkennt Klingeln über
Schwelle + Sperrzeit und broadcastet an alle verbundenen Clients. Die App entscheidet nichts über
das Klingeln — sie hört zu und schlägt Alarm.

**Die Handys verbinden sich ZUM Shelly** (`ws://<ip>/rpc`), nie umgekehrt. Jede App hält per
Foreground-Service eine dauerhafte WebSocket-Verbindung, gebunden an ein `Network` (SocketFactory
+ DNS): das WLAN oder — seit v1.3.0, nur mit dem Schalter „Auch unterwegs erreichbar" — den
WireGuard-Tunnel (`Link` in `ShellyClient`). **Nacktes Mobilfunk wird nie benutzt**: Ohne Tunnel
gibt es unterwegs kein Netz, an das gebunden werden könnte. Idle sind das ~ein Ping alle 25 s.

**Script-Single-Source:** `shelly/doorbell.js` wird von `CopyDoorbellScriptTask`
(`app/build.gradle.kts`) als Asset gebündelt — **nie ein Duplikat unter `app/src/main/assets/`
anlegen**. Die App vergleicht beim Verbinden die Version am Gerät und spielt das Script bei Bedarf
selbst ein oder startet es neu. **Die Version steht in Zeile 1 von `doorbell.js` und MUSS bei jeder
inhaltlichen Änderung hochgezählt werden** — sonst merkt keine App das Update.

**Drei Broadcast-Kanäle** vom Script an alle Clients, alle **ohne Passwort** empfangbar:
`doorbell` (Alarm, debounced), `doorbell_log` (abgeschlossenes Klingel-Ereignis), `doorbell_hb`
(Lebenszeichen alle 30 s mit Versionsnummer). Der Heartbeat ist der einzige Weg, auf dem eine App
ohne Passwort erfährt, dass und welches Script läuft — daran hängt der **Lauschmodus**.

**mJS ist eine JS-Teilmenge**: kein `try/catch`, kein `split()`, kein `parseInt()`. Das Script bringt
seine eigenen Helfer (`splitStr`, `parseDec`, `toNum`) mit. Nicht aus Vorliebe, sondern aus Not.

### Gemeinsame Config = KVS auf dem Gerät

Es gibt keine Konten und keine Server-Wahrheit: **der Schalterzustand des Shelly ist die Wahrheit**,
die Einstellungen liegen im KVS des Geräts und gelten für alle. Keys einheitlich `dbell_*`
(vollständige Tabelle im `README.md`). Nach jeder Änderung ruft die App
`Script.Eval {code:"cfgChanged()"}`, das Script lädt neu und broadcastet `doorbell_cfg`.

**KVS-Limits sind eng: max. 50 Keys à 253 Zeichen.** Der Ringpuffer nimmt davon 12 (10 Chunks à
6 Ereignisse ≈ 60 Ereignisse). Ältere Ereignisse leben nur noch in den lokalen App-Datenbanken.
Wer neue Keys hinzufügt, rechnet gegen dieses Budget.

**Klingel-Ereignisse statt Einzeltöne**: das Script zählt jede steigende Flanke und fasst Drücke
innerhalb von 3 Minuten zu EINEM Ereignis `{t, n, d}` zusammen — unabhängig vom Alarm-Debounce.
Lokal in Room ist `ts` (Gruppen-Start) der Primärschlüssel, dadurch dedupliziert der KVS-Merge beim
Reconnect von selbst. `authoritative = false` markiert einen nur aus Alarm-Events geschätzten
Vorläufer, den der exakte Datensatz des Scripts später ersetzt.

## Die Shelly-Auth — das größte Zeitgrab des Projekts

**Lies `docs/shelly-429-nonce-puffer.md`, bevor du irgendetwas an `ShellyClient`/`ShellyAuth`
anfasst.** Das Dokument ist am Gerät gemessen (eigene Python-Skripte, `tcpdump` auf einem gerooteten
Handy) und gilt für alle Shelly-Gen2/Gen3-Projekte, nicht nur für dieses. Kurzfassung:

- Der **429 hat nichts mit dem Anfrage-Volumen** und nichts mit dem oft zitierten
  „6-Verbindungen-Limit" zu tun. Er kommt aus dem **Nonce-Ringpuffer (32 Einträge)**: eine per 401
  ausgegebene, nie zu Ende authentifizierte Challenge bleibt als „pending" liegen und ist vor
  Verdrängung geschützt. Läuft der Puffer voll, öffnet das Gerät ein 2-s-Throttle — und **jede
  weitere Neuanfrage verlängert es**, man hält sich die Sperre selbst am Leben.
- **Goldene Regel:** eine Nonce besorgen und mit `nc++` wiederverwenden. Eine neue nur bei
  `401 stale=true`, beim ersten Connect oder bei Passwortwechsel — und die dann **sofort** zu Ende
  authentifizieren. Deshalb ruft `runSession()` **kein** `resetAuth()` mehr; das tut nur
  `credentialsChanged()`.
- **Nonces sind an die WS-Verbindung gebunden.** Ein Persistieren über den Prozess-Neustart wurde
  gebaut, gemessen und wieder verworfen — es bringt nichts, der erste Call zieht ohnehin einen 401.
- **`MAX_AUTH_SENDS = 5` ist kein Fantasiewert.** Dieser Pixel 8 lehnt die *Erst*-Nutzung (nc=1)
  einer frischen Nonce zu ~55 % ab — byte-korrekte Antwort, zeitunabhängig, also geräteseitig
  unterhalb der App und **dort nicht behebbar**. Vier schnelle Wiederholungen bringen sie mit ~91 %
  pro Verbindung durch. Wer den Wert senkt, holt sich die 30-Sekunden-Hänger zurück.
- **Zweigeteilter Sendeabstand**: `MIN_CALL_GAP_MS = 250` solange die Nonce nicht bestätigt ist
  (der Handshake braucht Luft, enger getaktet scheitert er häufiger), danach `POST_AUTH_GAP_MS = 120`.
  Alle RPCs laufen strikt seriell über `callMutex`; `sendLock` hält nc-Vergabe und Sendereihenfolge
  zusammen, sonst überholt `nc=2` die `nc=1` und das Gerät wertet das als Replay.
- **Messfalle:** ein `Shelly.Reboot` per RPC **scheitert still, wenn das Gerät gesättigt ist** (die
  Reboot-RPC bekommt selbst 429). „Frisches Gerät"-Messungen immer über die Uptime verifizieren.
- Aus einem 429 erholt man sich durch **Stille** und Weiterverwenden der vorhandenen Nonce, nicht
  durch neue Versuche. Ein Shelly-Reboot leert den Puffer sofort.

## Drei Tore vor einem Verbindungsversuch

Damit die App im Supermarkt-WLAN nicht sinnlos verbindet, fragt `ShellyClient` vor **jedem** Versuch
ein Gate. Reihenfolge in `DoorbellService.onCreate` — **billig nach teuer, die Ortung ganz zuletzt**
(seit v1.2.0 umgedreht, Begründung in `docs/standort-nur-wenn-noetig.md`):

1. **WifiGate** (`service/WifiGate.kt`): Subnetz-Tor ohne Berechtigung → SSID-Whitelist (dort
   gemütlich alle 60 s) → Greylist (eine Probe je WLAN-Beitritt, dann alle 30 min). Unbekannte SSIDs
   eskalieren 5 s → 30 min und landen nach 10 min erfolglos auf der Greylist.
2. **Ist die SSID in der Whitelist, wird die HomeZone gar nicht erst gefragt.** Das ist die
   Heimnetz-Erkennung, und sie kostet nichts: `WifiGate` trägt jedes Netz automatisch ein, sobald
   die Klingel darüber erreichbar war (`onConnected`).
3. **HomeZone** (`service/HomeZone.kt`) nur im Rest — fremdes WLAN, dessen Subnetz zufällig passt
   (`192.168.178.x` ist FRITZ!Box-Voreinstellung, kommt bei Nachbarn durchaus vor). Steht dort
   `OUTSIDE`, wird nicht versucht.
4. `forced = true` („Verbindung prüfen" / Reconnect) **überspringt alles**.

**Die Reihenfolge ist der Kern.** Früher stand die HomeZone an erster Stelle. Seit nicht mehr
dauernd gemessen wird, wäre das eine Falle: Ein stehengebliebenes „unterwegs" hätte auch im eigenen
Heim-WLAN blockiert.

**Gemessen wird höchstens einmal je WLAN-Beitritt, nie periodisch.** Der Netzwechsel ist der bessere
Auslöser als jede Uhr — solange dasselbe Netz steht, hat das Gerät nicht gewechselt, und nach Hause
zu kommen heißt zwangsläufig, dass es wechselt. Jeder Netzwechsel verwirft das Urteil;
`invalidate()` tut dasselbe, wenn der Nutzer die App öffnet oder „Neu verbinden" drückt. **Kein
`requestLocationUpdates` mehr** — `getCurrentLocation`, Netz-Provider statt GPS. Vorher liefen zwei
Dauerabos alle zwei Minuten, sechseinhalb Tage am Stück, 6861 Ortungen; daher der dauerhafte blaue
Punkt. Kontrolle: `dumpsys location | grep shellydoorbell` — eine Zeile mit `Request[` heißt, es
läuft wieder ein Abo (die Zahlen unter *Historical Aggregate* sind Summen von früher, kein Beleg).

> **Der WLAN-Name ist geschwärzt, wenn man ihn nicht ausdrücklich anfordert.** Ab Android 12 liefert
> `NetworkCapabilities.transportInfo` nur mit `FLAG_INCLUDE_LOCATION_INFO` einen echten Namen, sonst
> dauerhaft `<unknown ssid>`. Ohne die Flagge waren Whitelist, Greylist **und** (nach dem Umbau vom
> 19.08.) der Standort-Auslöser tot — die App stand am 20.08. zwei Stunden 41 Minuten auf „Verbinde
> …" im WLAN eines Fremden. Seit v1.2.1 ist die Flagge gesetzt, und der Auslöser hängt am
> `Network`-Objekt statt am Namen: Das gibt es bei jedem Beitritt, ohne jede Berechtigung.
> Ganze Geschichte in `docs/standort-nur-wenn-noetig.md`, Abschnitt „Der Rückfall vom 20.08.".
>
> **Prüfstein am Gerät** (beweist, dass der Name ankommt — der Schlüssel fehlt sonst ganz):
> ```
> adb shell run-as de.beardedskunk.shellydoorbell cat files/datastore/settings.preferences_pb
> ```
> `wifi_whitelist` muss nach einer Verbindung daheim die eigene SSID enthalten.

**`verdict()` rechnet immer neu.** `_status` ist nur ein Zwischenspeicher; die Altersfenster in
`computeStatus()` wirken erst, wenn jemand rechnet. Dadurch **verfällt jede Fehlentscheidung von
selbst** — das Schlimmste ist eine Verzögerung, kein Dauerzustand. Das ist die eigentliche Lehre aus
dem 20.08.: nicht den Auslöser perfekt bauen, sondern dafür sorgen, dass ein ausgefallener Auslöser
die App nicht dauerhaft blind macht.

Bei fehlendem Urteil wird **nicht** blockiert, sondern versucht: Ein Versuch ist billig, eine
verpasste Klingel nicht.

> **Alle Fremdnetz-Fälle heißen auf dem Bildschirm gleich:** „Unterwegs – warte aufs Heimnetz",
> grau. Egal ob falsches Subnetz, Greylist oder Homezone — *woran* die App gemerkt hat, dass sie
> nicht daheim ist, ändert für den Nutzer nichts und stand früher nur als Rauschen in der Leiste
> (samt Shelly-IP). Der Grund lebt in `ConnectionState.OtherNetwork.reason` und landet **nur** im
> Ereignisprotokoll (`Verbindung: anderes Netz (…)`). Das Feld heißt bewusst `reason` und nicht
> `detail`, damit es niemand versehentlich wieder auf den Bildschirm holt. Entschieden am
> 21.08.2026.

**Die Ortung kommt erst dran, wenn die Versuche in diesem Netz schon `HOME_ASK_AFTER_MS` (45 s)
scheitern.** Das WLAN des Pixel zuckt häufig („verbinde / kein WLAN / verbinde" binnen Sekunden,
im Ereignisprotokoll gut zu sehen), und jedes Zucken liefert ein neues `Network` — ohne diese
Schranke würde bei jedem davon gemessen. Die Whitelist-Abkürzung allein genügt nicht: Beim Beitritt
kommt zuerst `onAvailable`, der WLAN-Name erst mit `onCapabilitiesChanged`, und in dieser Lücke ist
`isKnownGood()` blind. Deshalb zwei Schranken statt einer. Aus demselben Grund reicht `available()`
**keinen** WLAN-Namen ans `WifiGate` durch — ein durchgereichtes `null` würde den bekannten Namen
für Millisekunden löschen.

Steht die Verbindung, kann die Ortung ohnehin nichts beitragen — die laufende Verbindung ist der
bessere Beweis. Zu Hause wird deshalb **nie** gemessen.

> **Der FGS-Typ `location` war der eigentliche blaue Punkt.** Am Gerät gemessen (21.08.2026): Bei
> laufendem location-Vordergrunddienst notiert Android `FINE_LOCATION` im Zustand `fgsvc` alle paar
> Sekunden weiter — im Logcat desselben Prozesses steht in denselben Minuten **keine einzige
> Zeile**. Es war also nie unser Code. Deshalb setzt der Dienst den Typ nur noch, wenn er ihn
> braucht: **mit `ACCESS_BACKGROUND_LOCATION` gar nicht** (siehe `needsLocationFgsType()`), ohne
> die Berechtigung weiterhin. Nachgeprüft: Der Startzugriff gelingt danach weiterhin im Zustand
> `fgsvc`, ohne neue `Reject`-Zeile.
>
> **Prüfstein:** `dumpsys activity services de.beardedskunk.shellydoorbell` → `types=0x40000000`
> ist nur `specialUse` (gut), `0x40000008` hätte `location` dabei.

> **Der Tunnel hat Vorrang, sobald er steht** (`DoorbellService.link`). Das ist keine Wahl: Ein
> Android-VPN ist ohne `allowBypass()` nicht umgehbar und fängt auch Sockets ein, die ausdrücklich
> ans WLAN gebunden sind — am 23.08.2026 vom Nutzer beobachtet (Tunnel an im Heim-WLAN, Klingel
> tot). Über den Tunnel gibt es **kein Tor und kein Lernen**: keine Whitelist, keine Homezone —
> beim Vater steht das Handy in fremdem WLAN an fremdem Ort, während es mit der Klingel spricht.
> Liegt das Heim-WLAN (Whitelist) an und der Tunnel steht, sagt die Notification rot „Zu Hause –
> VPN abschalten" — und seit v1.4.0 schaltet ihn die **Tunnel-Automatik** selbst
> (`tunnelAutomation`): AN nach 20 s am Stück ohne Heimnetz, AUS ereignisgesteuert sofort, wenn
> der Heim-WLAN-Name erkannt ist. Braucht die WireGuard-Berechtigung `CONTROL_TUNNELS` (Laufzeit-
> Dialog) **und** in den WireGuard-Einstellungen (Advanced) die erlaubte Fernsteuerung — Letzteres
> ist nicht abfragbar; ein AN ohne folgendes VPN-Netz landet als Hinweis in Karte und Protokoll,
> wirkungslose Befehle werden erst nach 5 min wiederholt. Geschichte und Regeln in
> `docs/vpn-von-unterwegs.md`.
>
> Zwei Nebenwirkungen des Tunnels, die man kennen muss: **Bei stehendem Tunnel ist das Pixel im
> eigenen LAN unter seiner Tunnel-Adresse `192.168.178.203` per adb erreichbar, nicht unter der
> WLAN-Adresse.** Und die AllowedIPs `192.168.176.0/22` fangen in fremden Netzen mit
> 178.x-Adressen (z. B. beim Vater — FRITZ!Box-Voreinstellung) auch das dortige lokale Netz ab:
> für die Klingel gewollt, lokale Geräte dort sind dann aber nicht erreichbar.
>
> **„Nicht stören" gilt auch für die Klingel** (23.08.2026): an + „durchbrechen" aus ⇒ stille
> Benachrichtigung (Kanal `ring_quiet`) statt Alarm — daheim wie unterwegs. Die Einstellungs-Zeile
> „Nicht stören durchbrechen" bleibt; mit ihr klingelt es weiterhin durch.

**Die HomeZone-Zahlen sind bewusst großzügig**, und zwar asymmetrisch: der Radius ist 80 m statt der
gewünschten 15 m, weil stehende GPS-Fixes real 30–50 m driften. Ein Fix mit schlechterer Genauigkeit
als 200 m darf **niemals** „sicher unterwegs" behaupten — eine Fehlblockade kostet die Klingel,
während ein falsches „zu Hause" nur einen nutzlosen Verbindungsversuch kostet. Aus demselben Grund
hat `computeStatus()` **drei** Altersfenster (5 min entscheidet allein, 15 min darf noch blockieren,
12 h darf nur noch „zu Hause" bestätigen): sie beantworten verschiedene Fragen.

**FGS-Typ ist `specialUse|location`.** Den Typ `location` darf der Dienst nur setzen, wenn
`ACCESS_BACKGROUND_LOCATION` erteilt ist — sonst lehnt das System den Start aus dem Hintergrund
(Boot, App-Update) ab und der Dienst stürzt ab. `locationFgsActive` merkt sich das und holt es nach,
sobald die UI sichtbar wird.

## Klingelzeiten, „Ruhe bis", „Einschalten um"

- **Klingelzeiten sind Erlaubnisfenster**, keine Ruhezeiten: innerhalb ist die Klingel aktiv,
  außerhalb ist der Trafo stromlos. Ohne Klingelzeiten ist sie immer an.
- Jedes Fenster ist ein **Paar ganz normaler Shelly-Schedules** (ein/aus) — auch in der offiziellen
  Shelly-App sichtbar und änderbar. Max. 20 Jobs am Gerät ⇒ `MAX_WINDOWS = 10`.
- **Wochentage = Tage, an denen das Fenster BEGINNT** (Fenster über Mitternacht laufen in den
  Folgetag). App-intern 0 = Montag, die Shelly-Oberfläche fängt beim Sonntag an — `BellTimes`
  rechnet um (`isoToCron`/`cronToIso`).
- Eine neue Klingelzeit, die eine bestehende **überschneidet oder auch nur berührt**, lehnt die App
  ab: bei Berührung feuerten Aus- und Ein-Job in derselben Sekunde, die Reihenfolge wäre undefiniert.
- **„Ruhe bis" und „Einschalten um" sind Timer im Script, keine Schedules** (`dbell_mute_until` /
  `dbell_on_at`). Beim Ablauf schaltet das Script gemäß Klingelzeiten zurück, löscht den KVS-Key und
  informiert alle Apps — es bleibt nichts liegen, das am nächsten Tag erneut zuschlägt. Es ist immer
  **höchstens eines von beiden** gesetzt; ein manueller Eingriff am Gerät löscht das aktive.
- `BellTimes.nextStart()` taugt **nur außerhalb** aller Fenster als „wann geht sie wieder an" —
  innerhalb überspringt es den bereits begonnenen Beginn. Für „welche Klingelzeit ist gerade
  gemeint" gibt es stattdessen `nearness()` mit seinen Rangstufen.

## Source-Map (`de.beardedskunk.shellydoorbell`)

| Pfad | Inhalt |
|---|---|
| `service/DoorbellService.kt` | Der Lausch-Service (~1650 Zeilen): hält die Verbindung, verteilt alle Zustände als Flows an die UI, spielt das Script ein, verwaltet Schedules/KVS, baut die Dauer-Notification |
| `service/AlarmController.kt` | Dauerton auf dem Wecker-Stream + Vibration, Sicherheitsabschaltung nach 10 min |
| `service/HomeZone.kt` / `WifiGate.kt` | die beiden Tore (siehe oben) |
| `shelly/ShellyClient.kt` | WebSocket-RPC: Backoff, Auth-Lebensdauer, Rate-Limit, Serialisierung |
| `shelly/ShellyAuth.kt` | Digest-Formel (`ha2` ist der feste String `dummy_method:dummy_uri`) |
| `shelly/BellTimes.kt` | Klingelzeiten ↔ Cron-Timespecs, Überschneidungsprüfung, Rangfolge |
| `data/Db.kt` / `Prefs.kt` | Room (lokale History) / DataStore (lokale Einstellungen, WLAN-Listen, Homezone) |
| `ui/MainScreen.kt` | Verbindungs-, Klingel-, Klingelzeiten- und Ereignis-Karten |
| `ui/SettingsScreen.kt` | Shelly-Zugang, Erkennung, Zuverlässigkeits-Checkliste (Berechtigungen) |
| root | `MainActivity`, `AlarmActivity` (Vollbild über Sperrbildschirm), `OpenDoorActivity` (Trampolin), `DoorIntents`, `WireGuard` (installiert? — mehr kann man nicht wissen), `BootReceiver` |

> **Der Vollbild-Alarm bleibt bei eingeschaltetem Bildschirm absichtlich aus.** Android startet die
> Activity aus einem `setFullScreenIntent` nur, wenn der Bildschirm **gesperrt oder aus** ist; sonst
> gibt es die Heads-up-Benachrichtigung, und das ist kein Fehler. Am 19.08. habe ich daraus
> fälschlich geschlossen, die Berechtigung sei abgelehnt — der Schalter in den Einstellungen war
> längst umgelegt, mein `uiautomator`-Auszug hatte nur den falschen Knoten getroffen (die
> `checked`-Angabe kam von einem umgebenden `FrameLayout`, nicht vom `Switch`). **Zustand einer
> Oberfläche nie aus einem gegrepten Dump behaupten — Screenshot ansehen.**

**Türsprecher-Anbindung** (`DoorIntents.kt`): ist `de.videoapp` installiert, zeigen Notification und
Vollbild-Alarm zusätzlich „Tür ansehen" (Action `de.videoapp.action.OPEN_DOOR`). Braucht den
`<queries>`-Eintrag im Manifest, sonst ist die Action ab Android 11 unsichtbar. Fehlt die App, fehlt
einfach der Button.

## Konventionen

- **Deutsch** in UI-Strings und Kommentaren; Kommentarstil des Umfelds übernehmen.
- **Kommentare in `build.gradle.kts` bewusst ASCII** (keine Umlaute) — im übrigen Kotlin-Code sind
  Umlaute in Ordnung, im `doorbell.js` bewusst nicht.
- Neue Dependencies über den Version-Catalog (`gradle/libs.versions.toml`), nie inline.
- **Erst reden, dann bauen**: bei Meldungen erst Ursache + Vorschlag, Umbauten nach Absprache.
  Rückfragen im Fließtext mit Empfehlung, nicht als Auswahl-Dialog.
- **Fertige, verifizierte Schritte selbständig committen** — pro logischer Einheit einer, nicht alles
  in einen Sammel-Commit. Message auf Deutsch im Stil der History: Betreff = Problem/Bereich, dann
  Ursache und Wirkung. Mehrzeilig über `git commit -F` (PowerShell zerlegt Here-Strings).
  **Nicht pushen** ohne Auftrag.

## Fallstricke

- **Der Shelly liefert Notifications nur an den ERSTEN Kanal einer `src`-Kennung.** Am Gerät
  nachgewiesen (23.08.2026, zwei PC-Verbindungen mit derselben Kennung): Die zweite bekommt
  Antworten auf eigene Aufrufe, aber keinen einzigen Broadcast — sie ist taub, und zwar ohne jedes
  Fehlzeichen. Mit einer festen Kennung je Client war jede Neuverbindung nach einem Abriss ohne
  FIN (IP-Wechsel, Tunnel weg, WLAN aus) minutenlang taub: „verbunden", Watt-Anzeige lief über
  NotifyStatus an den Zombie … nein — gar nichts kam, ein Klingeln ging verloren. Deshalb erzeugt
  `runSession()` **je Verbindung eine frische `src`**, und der Heartbeat-Wächter zählt auch
  Verbindungen, die seit dem Aufbau NIE ein Lebenszeichen sahen (95 s → einmal nachfragen → weiter
  stumm → Neuaufbau mit wachsendem Abstand). Gilt für alle Shelly-Gen2/3-Projekte.
- **Script-Version vergessen hochzuzählen** = das Update kommt auf keinem Handy an. Erste Zeile.
- **Kein Duplikat des Scripts** unter `app/src/main/assets/` anlegen — der Gradle-Task erzeugt es.
- **Nicht parallel RPCs feuern.** Der Shelly ist schwach: parallele Calls quittiert er mit 429 und
  sein Script bricht ab. `initialLoadDone` gated deshalb den Live-Watt-Poll, bis `onConnected`
  komplett durch ist — sonst rennen beide gleichzeitig in die erste authentifizierte Anfrage.
- **Max. 6 gleichzeitige RPC-Kanäle** laut Doku, aber das betrifft nur nicht-persistente HTTP-Kanäle;
  12 gleichzeitige WebSockets liefen im Test problemlos. Web-UI und Shelly-App belegen zeitweise auch
  welche.
- Der Shelly misst die Leistung nur ~1× pro Sekunde — **Klingeldrücke deutlich unter einer Sekunde
  können durchrutschen**.
- `*.lmx` ist ein lokaler Hardlink-Workaround auf das Manifest und gitignored. Nie committen.
- Die Verbindung ist ein **unverschlüsselter WebSocket** im Heim-WLAN (`usesCleartextTraffic`). Das
  Shelly-Passwort schützt den Zugriff, nicht die Übertragung — so ist das Gerät gebaut.

## Aktueller Stand

Branch `main`, **v1.4.1**, Script-Version 6. Auf dem Pixel installiert (23.08.2026); Unterwegs-
Modus samt Tunnel-Automatik dort aktiv (Fernsteuerung in WireGuard freigegeben am 24.08., erster
Ab-Schaltvorgang beobachtet). Offen: Test des Auto-AN unterwegs; `AllowedIPs` auf dem Pixel ist
noch Voll-Tunnel.

Zuletzt: Tunnel-Automatik 2b (20 s / ereignisgesteuert, v1.4.0–1.4.1), die Nicht-stören-Regel,
davor der Taubheits-Fix (frische `src` je Verbindung + stummer-Kanal-Wächter, v1.3.2, siehe
Fallstricke) und Unterwegs-Modus 2a (Tunnel-Pfad, leises Klingeln, v1.3.0–1.3.1). Davor v1.2.2–1.2.5:
der blaue Punkt (FGS-Typ `location` nur ohne Hintergrund-Berechtigung, Ortung erst nach 45 s
Fehlversuchen), einheitliche Fremdnetz-Texte. Davor der Rueckfall vom 20.08. behoben
(geschwaerzter WLAN-Name, siehe oben). Davor: das
Klingeln meldet sich als eingehender Anruf, dazu ein dauerhaftes Ereignis-Protokoll
(neu: `data/EventLog.kt`), und der Alarmton kommt sofort statt nach viereinhalb Sekunden. Davor
Homezone (Lernen, drei Altersfenster, Hintergrund-Berechtigung), die Dauer-Notification mit
Zustandsfarben und Minuten-Ticker, „Einschalten um" als Gegenstück zu „Ruhe bis", die
Anmelde-Beschleunigung aus der 429-Analyse und die Türsprecher-Anbindung.

Bekannte Grenze ohne Lösung in der App: die flakige Erst-Auth dieses Pixel 8 (siehe oben) — nur
abgemildert, nicht behoben. Die ausführlichen `AUTHDBG`-Logs sind für die Feld-Diagnose absichtlich
noch drin.
