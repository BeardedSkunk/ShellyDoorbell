# Von unterwegs erreichbar sein (WireGuard)

Status: **Schritt 1 erledigt am 21.08.2026** (videoapp v1.66). Schritt 2 (Klingel-App) steht aus.

Vorgehen ist bewusst schrittweise. Reihenfolge und Stand:

| Schritt | Stand |
|---|---|
| videoapp: VPN als erreichbares Netz | **fertig** (v1.66, nur aufs Pixel) |
| Pixel mit der FRITZ!Box-Verbindung koppeln | offen — macht der Nutzer |
| Test von außen (WLAN aus, nur Mobilfunk) | offen |
| Klingel-App: Schalter „auch unterwegs" | offen |

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
