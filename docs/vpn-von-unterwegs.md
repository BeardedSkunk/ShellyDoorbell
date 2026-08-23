# Von unterwegs erreichbar sein (WireGuard)

Status: **Schritt 1 erledigt am 21.08.2026** (videoapp v1.66), Test von außen bestanden.
**Schritt 2a gebaut am 23.08.2026** (ShellyDoorbell v1.3.0, auf dem Pixel) — die App nutzt einen
stehenden Tunnel. Schritt 2b (die App schaltet den Tunnel selbst) steht aus; Plan unten.

Vorgehen ist bewusst schrittweise. Reihenfolge und Stand:

| Schritt | Stand |
|---|---|
| videoapp: VPN als erreichbares Netz | **fertig** (v1.66, nur aufs Pixel) |
| Pixel mit der FRITZ!Box-Verbindung koppeln | **fertig** (Tunnel „Home64", 21.08.) |
| Test von außen (WLAN aus, nur Mobilfunk) | **bestanden** 21.08. — FRITZ!Box und Video erreichbar |
| `AllowedIPs` auf das Heimnetz eingrenzen | offen — macht der Nutzer in der WireGuard-App |
| Klingel-App: Schalter „auch unterwegs" (2a) | **gebaut** (v1.3.0, 23.08.) — Test unterwegs durch den Nutzer offen |
| Klingel-App schaltet den Tunnel selbst (2b) | geplant, nach dem Test von 2a |

**Die Klingel bekommt einen Schalter, die videoapp nicht.** So hat es der Nutzer entschieden, und
es ist richtig: Die Videoübertragung weckt niemanden — wenn sie von unterwegs kann, soll sie es
einfach können. Die Klingel dagegen entscheidet darüber, ob nachts das Telefon losgeht und ob
dauerhaft Funkverkehr läuft; das gehört unter seine Kontrolle. Ein installiertes WireGuard heißt
nicht, dass er unterwegs geweckt werden will.

## Was erreicht werden soll

Nur zwei Dinge, ausdrücklich nicht mehr:

1. **Gesagt bekommen, dass es klingelt**, auch wenn er nicht zu Hause ist.
2. **Hingucken und sprechen** — der typische Fall ist der Postbote, den er bitten möchte, das
   Paket abzulegen.

Ausdrücklich **nicht** dabei: die Tür öffnen. Es gibt keinen elektrischen Türöffner, und es ist
auch keiner vorgesehen. Damit ist über den Tunnel nichts sicherheitskritisch erreichbar — das
vereinfacht die ganze Abwägung.

## Ausgangslage

Auf der FRITZ!Box ist eine WireGuard-Verbindung eingerichtet (vereinfachte Einrichtung: sie fragt
nur nach einem Namen und entscheidet den Rest selbst). Die Verbindung fürs Pixel ist noch nicht
importiert. Zwei Angaben fehlen noch und sind die Voraussetzung für alles Weitere:

- **Der Test von außen:** WLAN am Handy aus, nur Mobilfunk, Tunnel starten, `http://192.168.178.1`
  aufrufen. Von zu Hause getestet beweist nichts. Scheitert es, ist der Anschluss vermutlich
  DS-Lite (keine eigene IPv4) — dann geht es nur über IPv6, und nur dort, wo das Mobilfunknetz
  das kann.
- **`AllowedIPs` aus der Konfigurationsdatei.** Das ist die Zeile, die entscheidet, was in den
  Tunnel geht. Steht dort das Heimnetz (`/24`), ist es ein Split-Tunnel und der übrige Verkehr
  läuft weiter direkt — das ist gewünscht. Stünde dort `0.0.0.0/0`, ginge alles nach Hause.
  Die Zeile gehört dem **Client**: In der WireGuard-App auf dem Handy ist sie änderbar, auch wenn
  die FRITZ!Box ihre Kopie nicht mehr herausgibt.
  (Was die FRITZ!Box unter „entfernte Netzwerke" mit `/32` anzeigt, ist etwas anderes: die
  Adresse, die das Handy im Tunnel bekommt.)

## Was dazugehört

| Teil | Aufwand | Was zu tun ist |
|---|---|---|
| FRITZ!Box | erledigt | Verbindung fürs Pixel anlegen — je Gerät eine eigene, nie dieselbe Konfiguration doppelt |
| Pixel | klein | WireGuard-App, Konfiguration importieren, **„im Heim-WLAN automatisch aus"** |
| videoapp | **erledigt** | VPN zählt als erreichbar (`Net.kt`); Stationen kommen aus dem Speicher |
| ShellyDoorbell | mittel | feste WLAN-Bindung lösen |
| Takte | klein | Wächter auf Minuten statt Sekunden, WebSocket-Ping raus |

### videoapp — erledigt (v1.66, 21.08.2026)

Es war weniger als gedacht: **An der Übertragung musste gar nichts geändert werden**, und das
ist kein Zufall.

- Die App bindet ihre Sockets nicht an ein bestimmtes Netz, sondern nutzt die Standardroute.
  Mit aktivem Tunnel ist das der Tunnel.
- Die Station nimmt die Adresse des Betrachters **aus der eingehenden Verbindung**
  (`serveWatch(out, query, socket.inetAddress)` in `slave/HttpServer.kt`), statt sie sich sagen
  zu lassen. Der Betrachter schickt nur seinen Port (`/watch?vid=…&port=…`). Über den Tunnel
  trifft das automatisch die Tunneladresse, und die FRITZ!Box routet das UDP-Bild zurück.

Geändert wurde nur die Frage „habe ich überhaupt Netz?": Die drei gleichlautenden Prüfungen in
`ViewerActivity`, `MasterActivity` und `SlaveActivity` sind jetzt eine gemeinsame Funktion
(`Net.kt`, `canReachStations`) und lassen `TRANSPORT_VPN` zu. Ohne das hätte der Betrachter
dauerhaft „kein Netz" angezeigt **und** `rescueVideoHost()` übersprungen — das läuft absichtlich
nur, wenn Netz da ist.

**Die Stationssuche geht über den Tunnel nicht** (Kurzruf per UDP-Broadcast auf 8802, mDNS). Das
ist verkraftbar, weil die Übersicht bekannte Stationen ohnehin direkt per HTTP abfragt
(`MasterActivity.loadKnownStations`, gespeist aus `Prefs.knownStations`). Von unterwegs ist der
Stationsspeicher dann eben die einzige Quelle statt nur die zuverlässigere. **Praktische Folge:**
Die Übersicht muss zu Hause einmal offen gewesen sein, damit der Speicher aktuell ist.

### ShellyDoorbell — der größere Brocken

Die App ist **bewusst** so gebaut, dass sie Mobilfunk nie anfasst:

- `ShellyClient.buildClient()` bindet Socket **und** DNS fest ans WLAN-`Network`
  (`socketFactory(net.socketFactory)`, eigener `Dns`-Resolver über `net.getAllByName`).
- `DoorbellService.requestWifi()` fordert ausdrücklich `TRANSPORT_WIFI` an.
- Das Subnetz-Tor in `WifiGate` vergleicht die **eigene** IPv4 mit der des Shelly. Im Tunnel hat
  das Handy eine andere Adresse.

Ein VPN ist ein eigenes `Network` mit `TRANSPORT_VPN`. Heute meldet die App unterwegs deshalb
schlicht „kein WLAN" und versucht gar nichts — auch bei laufendem Tunnel.

**Der Trost:** Das ganze Tor beantwortet die Frage „bin ich zu Hause?". Mit Tunnel lautet die
richtige Frage „ist der Tunnel oben?", und die ist einfacher. Der WLAN-Zweig bleibt, wie er ist;
daneben käme ein deutlich schlichterer Tunnel-Zweig.

## Der Preis: alle 25 Sekunden

Das ist der Punkt, an dem der Nutzer zu Recht hakt. Es gibt **drei** Herzschläge, und nur einer
ist unvermeidlich:

| | Takt | Wozu | Verhandelbar? |
|---|---|---|---|
| WireGuard-Keepalive | 25 s | hält die NAT-Öffnung beim Mobilfunkanbieter auf | **nein** |
| WebSocket-Ping (OkHttp `pingInterval`) | 25 s | merkt, dass die Leitung tot ist | ja |
| Skript-Heartbeat vom Shelly | 30 s | Wächter: läuft das Skript noch? | ja, deutlich träger |

Der Keepalive ist **nicht** eine Folge unserer Bauweise, sondern des NAT: Ein Handy im Mobilfunk
hat keine von außen erreichbare Adresse. Damit der Shelly es erreichen kann, muss regelmäßig
geklopft werden, sonst fällt die Öffnung nach etwa einer halben bis anderthalb Minuten zu.

Die einzige Technik, die das umgeht, ist der Push-Dienst von Google: Er hält **eine** Verbindung
für alle Apps des Geräts und darf sie deshalb auf ~28 Minuten strecken. Preis ist die Cloud im
Weg — kommt für dieses Projekt nicht in Frage (siehe die Grundhaltung „rein lokal").

**Der praktikable Ausweg:** Der Tunnel muss gar nicht immer stehen.

- Zu Hause wird er nicht gebraucht — dort läuft alles über WLAN. Die WireGuard-App kann sich in
  bekannten WLANs selbst abschalten. Das ist kein Kompromiss, sondern schlicht richtig.
- Wenn das noch zu viel ist: Der Postbote kommt werktags tagsüber. Ein Schalter „ich erwarte ein
  Paket" oder ein Zeitfenster wäre wenig Arbeit und würde den Rest des Tages ruhig stellen.

## Der Heartbeat der Klingel — warum es ihn gibt

Er wurde eingebaut, weil ein **stillschweigend abgestürztes Skript auf dem Shelly im Protokoll
exakt so aussieht wie „es hat niemand geklingelt"**. Ohne Lebenszeichen bliebe eine tote Klingel
tagelang unbemerkt. Der Grund ist gut, der Takt ist es nicht: Für einen Wächter genügen Minuten.
`STALE_MS` (95 s) müsste dann mitwachsen.

## Später vielleicht: die Richtung umdrehen

Mit stehendem Tunnel hat das Handy eine Adresse, die das Heimnetz erreichen kann. Dann bräuchte
die App gar keine dauerhafte ausgehende Verbindung mehr — der Shelly könnte beim Klingeln einfach
an die Tunnel-Adresse des Handys schicken. Konzeptionell die sparsamste Variante, zugleich der
größte Umbau. Nur als Richtungsangabe notiert.

## Ein Haken, der ausgerechnet hier zutrifft

Das Heimnetz ist `192.168.178.0/24` — die FRITZ!Box-Voreinstellung. Das Netz beim Vater ist
**sehr wahrscheinlich dasselbe**: Am 20.08. hat das Subnetz-Tor dort nicht blockiert, was genau
dann passiert, wenn die Adressen zusammenpassen (ganz sicher ist es nicht, das Tor lässt auch
durch, wenn es die eigene IP nicht kennt).

Bei laufendem Tunnel kollidieren dort zwei Wege zu `192.168.178.20`. In der Regel gewinnt der
Tunnel — für die Klingel das gewünschte Ergebnis, aber dort käme man dann an keine lokale
Geräteoberfläche mehr heran. Sauber wäre eine Umnummerierung des Heimnetzes auf etwas
Ungewöhnliches (z. B. `192.168.44.0/24`). **Nicht empfohlen**, solange Shelly, beide Stationen,
die Ladesteckdosen und die Balkonkraftwerk-Dose feste Adressen haben.

## Reihenfolge, wenn es losgeht

1. Test von außen + `AllowedIPs` ablesen.
2. videoapp (klein, sofort vorzeigbar).
3. ShellyDoorbell (größer).
4. Takte neu setzen.

## Schritt 2 — der Plan (23.08.2026, abgesprochen, nicht gebaut)

Was der Nutzer will, in seinen Worten zusammengefasst: Für alle, die die App ohne WireGuard-Zugang
ins Heimnetz benutzen, **ändert sich nichts** — dieselbe Dauer-Notification, dieselben Hinweise.
Wer einen Tunnel nach Hause hat, bekommt ein neues Verhalten: unterwegs informiert werden, die
App schaltet den Tunnel **selbst ein, wenn das Heim-WLAN fehlt, und wieder aus, wenn es da ist**.
Ein Schalter in der App stellt das Ganze ab. Und: Ist am Handy „Nicht stören" an, klingelt es
unterwegs **leise**.

### Was die App erkennen kann — und was nicht

| Frage | Antwort | Wie |
|---|---|---|
| Ist WireGuard installiert? | **ja** | `PackageManager` + `<queries><package android:name="com.wireguard.android"/>` im Manifest (dieselbe Mechanik wie die Türsprecher-Erkennung) |
| Steht gerade ein Tunnel? | **ja, ohne Berechtigung** | `NetworkCallback` auf `TRANSPORT_VPN`. **Falle:** Ein `NetworkRequest` hat `NET_CAPABILITY_NOT_VPN` voreingestellt — ohne `removeCapability(NOT_VPN)` sieht man VPN-Netze nie. |
| Hat WireGuard einen Tunnel **in mein Heimnetz**? | **nicht lesbar** | Die App gibt ihre Tunnelliste nicht heraus (kein Provider, kein Abfrage-Intent). |
| Führt der Tunnel wirklich zur Klingel? | **beweisbar statt lesbar** | Der Shelly antwortet durch den Tunnel — einmal gelungen, merkt sich die App das (wie die WLAN-Whitelist). |
| Wie heißt der Tunnel (fürs Schalten)? | **muss eingetippt werden** | Ein Textfeld, z. B. `Home64`. Die App prüft den Namen: Nach `SET_TUNNEL_UP` muss binnen Sekunden ein VPN-Netz auftauchen, sonst stimmt der Name nicht oder die Fernsteuerung ist nicht erlaubt. |

Daraus folgt die Bedienung: Der Schalter **„Auch unterwegs erreichbar"** ist ausgegraut, solange
WireGuard fehlt oder kein Tunnelname eingetragen ist. Genau der Vorschlag des Nutzers („ein Setting
vorher, in dem man die Verbindung benennt") — er ist nicht der Notnagel, sondern der einzige Weg.

**Drei Bedingungen, alle billig:** WireGuard installiert → Schalter an → Tunnel steht. Fehlt eine,
verhält sich die App exakt wie heute. Die Voreinstellung ist „aus".

### Die Falle, die der Nutzer am 23.08. selbst gefunden hat

**Tunnel an + Heim-WLAN ⇒ die App erreicht den Shelly nicht.** Das ist nicht bloß „die App kennt
VPN noch nicht": Die Verbindung ist ans WLAN-`Network` gebunden, und trotzdem kam nichts an. Der
Grund liegt bei Android: Ein VPN ist standardmäßig **nicht umgehbar** — solange es steht, wandert
der Verkehr *aller* Apps hinein, **auch der, den eine App ausdrücklich an ein anderes Netz gebunden
hat** (`VpnService.Builder.allowBypass()` würde das erlauben; WireGuard ruft es nicht auf). Die
Pakete an `192.168.178.20` gingen also in den Tunnel, von dort zur öffentlichen Adresse der
FRITZ!Box — von innen. Ob die Box das nicht annimmt oder schon der Handshake von innen scheitert,
ist nicht gemessen und für die Bauweise egal. **Folge: „zu Hause angekommen ⇒ Tunnel aus" ist
keine Bequemlichkeit, sondern Pflicht.** Ohne das ist die Klingel daheim tot, sobald der Tunnel
einmal an war.

### 2a — die App nutzt einen stehenden Tunnel (gebaut, v1.3.0)

Der WLAN-Pfad bleibt **unverändert**. Daneben ein zweiter, schlichter Pfad — mit einer Abweichung
vom ursprünglichen Plan: **Steht der Tunnel, hat er Vorrang**, nicht das WLAN. Nicht aus Vorliebe,
sondern weil Android es erzwingt (siehe die Falle oben): Ein stehender Tunnel fängt den
WLAN-Verkehr ohnehin ein, also ist er der Weg, ob man will oder nicht — ehrlicher ist, ihn dann
auch so zu benutzen und zu benennen. Ohne den Schalter zählt ein VPN nicht (es könnte ein fremdes
sein, Firmen-VPN oder Privatsphäre-Dienst); dann verhält sich die App wie vor v1.3.

Umgesetzt in `DoorbellService` (`link`, `requestVpn`, `NetCtx`, `postQuietRingNotification`),
`ShellyClient` (`Link`, kein Tor über den Tunnel), `SettingsScreen` (`AwayCard`), `WireGuard.kt`.

- **Ein zweiter Wächter** neben `WifiWatcher`: `registerNetworkCallback` auf `TRANSPORT_VPN`
  (mit `removeCapability(NOT_VPN)`), liefert `vpn: StateFlow<Network?>`. Keine Berechtigung,
  keine Ortung — ein VPN-Netz hat keinen WLAN-Namen.
- **Netzwahl:** Steht der WLAN-Pfad auf `NoWifi` oder `OtherNetwork`, ist der Schalter an und gibt
  es ein VPN-Netz, dann baut `ShellyClient` den Client **an das VPN-`Network` gebunden** — dieselbe
  Bindung wie beim WLAN (`socketFactory` + `Dns` über `net.getAllByName`). Das hält das Versprechen
  „Mobilfunk wird nie benutzt" wörtlich: Ohne Tunnel gibt es kein Netz, an das gebunden werden
  könnte, also keinen Versuch.
- **Kein Tor für den Tunnel.** Subnetz, Whitelist, Greylist, Homezone beantworten „bin ich zu
  Hause?" — ein Tunnel ist *absichtlich* zu Hause. Es bleibt der normale Backoff, Deckel 60 s
  (Aussetzer im Mobilfunk sind kurz).
- **Texte:** verbunden über den Tunnel → „Verbunden übers VPN – lausche auf die Klingel" (blau,
  mit DND-Zeichen bei Ruhe wie daheim), „Verbinde übers VPN …" (grau). Schalter an, unterwegs,
  kein Tunnel → „Unterwegs – VPN ist aus" (grau). **Tunnel an und Heim-WLAN (Whitelist) liegt an,
  ohne Verbindung → „Zu Hause – VPN abschalten" (rot)** — das ist die Falle von oben, und der
  Nutzer kann sie beheben, deshalb rot mit Handlungsanweisung. Alles andere bleibt; mit Schalter
  aus bleiben **alle** Texte wie heute.
- **Klingeln unterwegs:** Ist „Nicht stören" am Handy aktiv (`currentInterruptionFilter != ALL`,
  ohne Berechtigung lesbar), kommt das Klingeln **leise**: eine Benachrichtigung auf einem
  zweiten Kanal ohne `setBypassDnd`, ohne Wecker-Stream, ohne Vollbild — mit „Tür ansehen", und
  eine Zeile im Ereignisprotokoll. Ist „Nicht stören" aus, klingelt es wie daheim (Wecker-Stream,
  Anruf-Darstellung). Daheim ändert sich nichts: Dort durchbricht der Alarm „Nicht stören"
  weiterhin absichtlich (Einstellungen → Zuverlässigkeit).
- **Einstellungen-Karte „Unterwegs":** Textfeld *WireGuard-Tunnel*, Schalter *Auch unterwegs
  erreichbar* (ausgegraut ohne WireGuard / ohne Namen), darunter Zustandszeilen: *WireGuard:
  installiert / fehlt* · *Tunnel: aktiv / aus* · *Klingel über den Tunnel erreicht: zuletzt … /
  noch nie*.

### 2b — die App schaltet den Tunnel

**Am Pixel nachgeprüft (23.08.2026, `dumpsys package com.wireguard.android`, Version
1.0.20260315):** Es gibt den Empfänger `com.wireguard.android/.model.TunnelManager$IntentReceiver`
für die Aktionen `com.wireguard.android.action.SET_TUNNEL_UP`, `SET_TUNNEL_DOWN` und
`REFRESH_TUNNEL_STATES`, und die App deklariert eine eigene Berechtigung
`com.wireguard.android.permission.CONTROL_TUNNELS` mit Schutzstufe `dangerous`.

**Aus dem Quelltext der WireGuard-App, am Gerät noch nicht ausprobiert:** Der Tunnelname geht als
Extra `tunnel` mit; der Empfänger verlangt die Berechtigung oben (unsere App muss sie im Manifest
anfordern und zur Laufzeit erfragen — sie ist `dangerous`, Android zeigt dafür einen Dialog); und
in den WireGuard-Einstellungen muss die Fernsteuerung durch andere Apps erlaubt sein, sonst wirft
der Empfänger den Intent still weg. **Erster Schritt von 2b ist deshalb ein Probelauf per
`adb shell am broadcast …` auf dem Pixel** — der schaltet den Tunnel dort tatsächlich, also nur
mit Ansage.

**Die Schaltregeln, bewusst konservativ** (das WLAN des Pixel zuckt im Minutentakt — jede Regel
muss das aushalten):

| Richtung | Auslöser | Schranke |
|---|---|---|
| **AN** | WLAN-Pfad steht auf `NoWifi` **oder** `OtherNetwork` — egal ob unterwegs oder daheim mit WLAN aus, Mobilfunk an (ausdrücklicher Wunsch) | erst nach **2 min** am Stück, und nur, wenn kein VPN-Netz da ist |
| **AUS** | ein WLAN mit Namen aus der **Whitelist** ist beigetreten | sofort; danach auf `onLost` des VPN-Netzes warten, dann `reconnectNow()` |
| **AUS** | der Nutzer stellt den Schalter ab | sofort |

Die 2-Minuten-Schranke kostet höchstens ein Klingeln in den ersten zwei Minuten nach dem
Verlassen des Hauses — das ist der Preis dafür, dass ein WLAN-Zucker daheim den Tunnel nicht
hochreißt (und damit, siehe Falle oben, die Klingel abwürgt). **Nie AN**, solange ein WLAN da ist,
dessen Subnetz passt und dessen Versuche noch nicht als gescheitert gelten (Greylist-Logik bleibt
zuständig). **AUS nur über die Whitelist**, nicht übers Subnetz — beim Vater passt das Subnetz
auch, und dort soll der Tunnel stehen bleiben.

Bekannte Grenze: Auf einem frischen Handy ist die Whitelist leer, die App kann den Tunnel daheim
also nicht selbst abschalten, bevor sie dort einmal direkt verbunden war. Einmal von Hand aus,
dann trägt sich das Heim-WLAN ein, ab da läuft es. Das gehört in die Zustandszeile der Karte.

### 2c — Takte (unverändert später)

WebSocket-Ping 25 s und Script-Heartbeat 30 s bleiben vorerst. Solange der Tunnel steht, klopft
WireGuard ohnehin alle 25 s; unser Ping kostet dann nichts zusätzlich. Erst wenn 2b läuft, lohnt
es sich zu messen, was der Tunnel am Tag an Akku kostet.

### Reihenfolge und Abnahme

1. **2a gebaut (v1.3.0).** Einrichten: Einstellungen → „Unterwegs" → Tunnelname `Home64`
   eintragen, Übernehmen, Schalter an. Nutzer testet unterwegs mit von Hand geschaltetem Tunnel:
   Klingeln kommt an, „Tür ansehen" öffnet die videoapp über den Tunnel, mit „Nicht stören" kommt
   es leise. Prüfstein: Ereignisprotokoll zeigt `VPN-Tunnel steht`, `verbunden (…) ueber Tunnel`,
   `Klingel ueber den Tunnel erreicht`; die Karte zeigt „zuletzt …" statt „noch nie".
2. **Probelauf Fernsteuerung** per adb (mit Ansage), dann **2b bauen**. Abnahme: Haus verlassen →
   nach 2 min steht der Tunnel; nach Hause kommen → Tunnel aus, Klingel direkt verbunden.
   Prüfstein am Gerät: Ereignisprotokoll der App (`files/log/events.log`) zeigt beide Schaltungen
   mit Grund.
3. Takte messen (2c).
