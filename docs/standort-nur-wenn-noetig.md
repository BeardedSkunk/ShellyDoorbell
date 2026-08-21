# Wann die App den Standort benutzen darf

Status: **entschieden am 19.08.2026, umgesetzt in derselben Runde.**

## Was schieflief

Auf dem Pixel gemessen (`dumpsys location`):

```
de.beardedskunk.shellydoorbell: min/max interval = 0s/120s,
  active duration = 6d 12h 49m,  locations = 4185
de.beardedskunk.shellydoorbell: min/max interval = 0s/120s,
  active duration = 6d 12h 49m,  locations = 2676
```

**Zwei Dauerregistrierungen, eine davon `HIGH_ACCURACY` (also GPS), beide alle zwei Minuten,
sechseinhalb Tage ununterbrochen aktiv, zusammen 6861 Ortungen.** Daher stand der blaue
Standort-Punkt dauerhaft in der Statusleiste.

Strukturelle Ursache: `homeZone.start()` lief einmal in `onCreate` und registrierte
`requestLocationUpdates` auf allen Providern; `stop()` gab es nur in `onDestroy`. Die Ortung lief
also durchgehend — unabhängig davon, ob jemand die Antwort brauchte.

## Der Leitsatz

> **Standort nur messen, wenn die Antwort eine Entscheidung ändern kann.**

Es gibt genau einen Abnehmer, auf den es ankommt: das Tor vor einem Verbindungsversuch. Und das
wird nur befragt, wenn gerade **nicht** verbunden ist. Alles andere (der Text der Dauer-
Benachrichtigung) ist Kosmetik.

## Die vier Stufen, von billig nach teuer

Reihenfolge im Tor — die Ortung steht ganz am Ende und wird meist gar nicht erreicht:

1. **Kein WLAN** → blockieren. Ohne WLAN wird ohnehin nie verbunden (`ShellyClient` bindet sich
   ans WLAN-`Network`), der Standort könnte an der Entscheidung nichts ändern. Kostenlos.
2. **SSID in der Whitelist** → sofort versuchen, **Homezone gar nicht erst fragen**. `WifiGate`
   trägt jede Netzkennung automatisch ein, sobald die Klingel darüber erreichbar war
   (`onConnected`). Das ist die Heimnetz-Erkennung, und sie kostet nichts.
3. **Subnetz passt nicht** → blockieren. Liegt die Shelly-IP nicht im eigenen Subnetz, ist sie hier
   unerreichbar. Kostenlos, braucht keine Berechtigung.
4. **Rest** → fremdes WLAN, dessen Subnetz zufällig passt. Realistisch, weil `192.168.178.x` die
   FRITZ!Box-Voreinstellung ist und bei Nachbarn oder Freunden also durchaus passt. **Nur hier**
   wird gemessen.

## Der WLAN-Wechsel ersetzt jeden Timer

Die ursprüngliche Idee war, nach einem „unterwegs" aus der Entfernung zurückzurechnen, wann sich
frühestens etwas geändert haben kann. **Der Nutzer hat das verworfen, und zu Recht:**

> „Wenn ich irgendwann zu Hause ankomme, dann logge ich mich automatisch ins WLAN ein, dann kriegt
> die App durch den WLAN-Wechsel mit, dass sie aufwachen muss."

Das ist ein **strikt besserer** Auslöser als jede Uhr:

- Solange die SSID gleich bleibt, hat das Gerät das Netz nicht gewechselt.
- Nach Hause zu kommen heißt **zwangsläufig**, dass sie wechselt.
- Ein Timer könnte dagegen nur zu früh feuern (unnötige Ortung) oder zu spät (verpasste Klingel).

Deshalb: **eine Messung je WLAN-Beitritt, kein periodisches Nachschauen.** Jeder Netzwechsel
verwirft das alte Urteil; dadurch bleibt kein „unterwegs" hängen, wenn die Whitelist einmal leer
ist (frische Installation) und sonst auch im eigenen Heim-WLAN blockieren würde.

Fällt eine Zeitschranke doch irgendwo an, gilt **20 km/h**, nicht 80 — begründet aus dem
tatsächlichen Bewegungsprofil: selten mehr als drei Kilometer von zu Hause, zu Fuß oder mit dem Rad.

## Die Falle, die dabei mit weg musste

Die Homezone wurde **vor** dem `WifiGate` befragt:

```kotlin
if (!forced && homeZone.status.value == HomeStatus.OUTSIDE) Block(...)
else wifiGate.decide(ipStr, forced)
```

Hört man auf zu messen, bleibt das Urteil „unterwegs" stehen — und hätte dann **auch im eigenen
Heim-WLAN blockiert**. Die Reihenfolge ist deshalb umgedreht: erst die SSID, dann alles andere.

## Weitere Festlegungen

- **Netz-Provider statt GPS für die Torentscheidung.** Für „bin ich Kilometer weg?" genügt der
  Netzfix. GPS mit `HIGH_ACCURACY` lief bisher dauerhaft und war dafür nie nötig.
- **Einzelmessung statt Abo:** `getCurrentLocation` statt `requestLocationUpdates`. Der Punkt
  blinkt kurz auf, statt zu stehen.
- **Die Homezone wird nur gelernt, wenn sie noch nicht gelernt ist** — ein Haus bewegt sich nicht.
  Zusätzlich neu gelernt wird sie, wenn eine Verbindung zustande kommt, während das Urteil
  „unterwegs" lautet: Die Verbindung ist die Wahrheit, also war die gespeicherte Zone falsch.
- **Bei fehlendem Urteil wird nicht blockiert, sondern versucht.** Ein Versuch ist billig, eine
  verpasste Klingel nicht.

## Was das im Alltag heißt

| Lage | Ortung |
|---|---|
| Zu Hause, mit der Klingel verbunden | **keine** |
| Zu Hause, WLAN weg | **keine** (kein WLAN → kein Versuch) |
| Bekanntes WLAN, Klingel gerade nicht erreichbar | **keine** (SSID genügt) |
| Fremdes WLAN, Subnetz passt nicht | **keine** |
| Fremdes WLAN, Subnetz passt zufällig | **eine** Messung je Beitritt |

---

# Der Rückfall vom 20.08.

Status: **gefunden und behoben am 20.08.2026 (v1.2.1).**

Der Umbau oben war am 19.08. um 23:59 auf dem Pixel. Am nächsten Tag war der Nutzer im WLAN
seines Vaters — und die App zeigte zwei Stunden 41 Minuten am Stück „Verbinde mit dem Shelly …",
statt „Unterwegs" zu melden und Ruhe zu geben.

## Der Beweis stand im Ereignisprotokoll

`files/log/events.log` auf dem Gerät:

```
08-20 09:57:05  Verbindung: kein WLAN          ← losgefahren
08-20 10:01:16  Verbindung: verbinde           ← WLAN des Vaters
08-20 10:17:01  laeuft (verbindung=Connecting …)
   … unverändert bis …
08-20 12:42:18  Verbindung: kein WLAN
```

Am 18. und 19.08. hatte dasselbe Tor **neunmal** auf „anderes Netz" geschaltet. Am 20.08., mit dem
neuen Stand: **kein einziges Mal.** Der Umbau war die Ursache, nicht der Zufall.

## Das Signal, auf das ich gebaut hatte, gab es nicht

Der neue Auslöser war „einmal messen je WLAN-Beitritt", erkannt am **Netznamen**. Seit Android 12
schwärzt das System `WifiInfo` in `NetworkCapabilities.transportInfo`; der Name kommt nur durch,
wenn der Callback mit `FLAG_INCLUDE_LOCATION_INFO` registriert wurde. Der fehlte. Also war der
Name auf dem Pixel **dauerhaft `null`** — und damit:

| Stelle | Wirkung bei `ssid == null` |
|---|---|
| `verdict(null)`, `judgedSsid` startet auf `null` | `null != null` ist falsch → **nie gemessen** |
| `onNetworkChanged(null)` | `judgedSsid == ssid` → kehrt sofort um → Urteil nie verworfen |
| `_status` | eingefroren auf `INSIDE` vom letzten `recordConnected()` daheim |
| `WifiGate.decide` | ohne Namen keine Greylist → `Attempt` für immer → Zustand bleibt `Connecting` |

Das Alte funktionierte trotzdem, weil das Dauerabo den Standort unabhängig vom Netznamen aktuell
hielt. Beim Entfernen des Abos ist die stille Abhängigkeit sichtbar geworden.

## Der Beleg, den ich vorher hätte sehen können

In `files/datastore/settings.preferences_pb` standen `home_lat` und `home_lon` — aber **weder
`wifi_whitelist` noch `wifi_greylist`**. Nach Wochen täglicher Verbindungen zu Hause wäre das
Heim-WLAN längst eingetragen gewesen, wenn die App den Namen je gesehen hätte. **Die SSID-Ebene
war nie funktionsfähig**, lange vor dem Umbau — sie fiel nur nicht auf, weil die Ortung sie deckte.

> Ein Signal, auf dem eine Entscheidung ruht, muss am Gerät nachgewiesen sein — nicht im Code
> plausibel aussehen. Der leere Whitelist-Schlüssel war der Nachweis, und er lag die ganze Zeit da.

## Was jetzt anders ist

1. **Der Netzname kommt an.** Der Callback wird ab Android 12 mit `FLAG_INCLUDE_LOCATION_INFO`
   registriert (zwei Klassen, weil es den Konstruktor mit Flagge erst ab API 31 gibt). Damit leben
   Whitelist und Greylist überhaupt zum ersten Mal — und die **kostenlose** Heimnetz-Erkennung aus
   Stufe 2 greift wirklich.
2. **Der Auslöser hängt nicht mehr am Namen, sondern am `Network`-Objekt.** Jeder WLAN-Beitritt
   liefert ein neues; das braucht keine Berechtigung und kann nicht geschwärzt werden. Derselbe
   Gedanke wie vorher, nur auf einem Signal, das es wirklich gibt.
3. **„Noch nie gemessen" ist von „für null gemessen" getrennt** (`judged`-Flag). Genau diese
   Vermengung war die Falle.
4. **`verdict()` rechnet immer neu.** `_status` ist nur ein Zwischenspeicher; die Altersfenster in
   `computeStatus()` wirken erst, wenn jemand rechnet. Damit **verfällt jede Fehlentscheidung von
   selbst**: Das Schlimmste ist eine Verzögerung, kein Dauerzustand.
5. **Ohne Netznamen wird nicht mehr endlos „Verbinde …" gezeigt.** Nach denselben zehn Minuten wie
   bei der Greylist wird der Zustand ehrlich benannt. Die Wiedervorlage entspricht dem
   Backoff-Deckel — es geht keine Probe verloren, nur der Text stimmt jetzt.

Punkt 4 ist die eigentliche Lehre: Nicht „diesen Auslöser richtig bauen", sondern **dafür sorgen,
dass ein ausgefallener Auslöser die App nicht dauerhaft blind macht.**

---

# Der blaue Punkt, zweiter Anlauf (21.08.2026, v1.2.2/v1.2.3)

Nach dem Umbau vom 19.08. stand der blaue Punkt **weiter dauerhaft** in der Statusleiste, obwohl
`dumpsys location` null aktive Registrierungen der App zeigte. Es waren zwei verschiedene
Ursachen — die zweite hatte mit der Homezone gar nichts zu tun.

## Ursache 1: jedes WLAN-Zucken löste eine Messung aus (v1.2.2)

Das WLAN des Pixel zuckt im Minutentakt:

```
05:03:30.326  HomeZone: WLAN gewechselt -> Ortsurteil verworfen
05:03:30.362  Verbindungszustand: kein WLAN
05:03:31.394  WifiGate: WLAN gewechselt: ssid=LinkBash5
05:03:31.806  ShellyClient: Verbunden mit 'Klingelueberwachung'
```

Jedes Zucken liefert ein neues `Network`, und seit v1.2.1 hängt der Auslöser genau daran. Aus
„es wird nie gemessen" war „es wird bei jedem Zucken gemessen" geworden — beides falsch.

Die Whitelist-Abkürzung fing das nicht ab: Beim Beitritt kommt zuerst `onAvailable`, der WLAN-Name
erst mit `onCapabilitiesChanged`. **In dieser Lücke ist `isKnownGood()` blind** — und `available()`
reichte obendrein ein `null` als Namen ans `WifiGate` durch und löschte damit kurz den bekannten.

Zwei Schranken statt einer:

- **`HOME_ASK_AFTER_MS` (45 s):** Die Ortung wird erst gefragt, wenn die Versuche in diesem Netz
  schon eine Weile scheitern. Ein Reconnect daheim dauert Sekunden und kommt dort nie an. Der
  Leitsatz dahinter: *Steht die Verbindung, kann der Standort nichts beitragen — die laufende
  Verbindung ist der bessere Beweis.*
- **`available()` reicht keinen Namen mehr durch.** Netz und Name kommen gemeinsam mit den beiden
  folgenden Rückrufen.

Nebenbei: `computeStatus()` fragte die Provider **dreimal** ab (bis zu sechs
`getLastKnownLocation` je Bewertung, jedes ein Standortzugriff). Kandidaten werden jetzt einmal
gesammelt und dreimal ausgewertet.

## Ursache 2: der FGS-Typ `location` selbst (v1.2.3)

Das war die eigentliche. Die Messung, die es zeigte:

```
>>> 05:05:22  ORTUNG_VOR=747ms
>>> 05:05:32  ORTUNG_VOR=7s823ms
>>> 05:05:42  ORTUNG_VOR=2s372ms
>>> 05:05:52  ORTUNG_VOR=6s265ms
```

Zugriffe alle paar Sekunden — und im **Logcat desselben Prozesses in denselben Minuten keine
einzige Zeile**. `recompute()` lief also nicht; es war nie unser Code. `dumpsys appops` bestätigte
den Zustand: `Access: [fgsvc-s]`, also aus dem Vordergrunddienst.

**Android hält für einen Vordergrunddienst mit Typ `location` die Standort-Zurechnung die ganze
Laufzeit über wach.** Genau das ist der dauerhafte Punkt, und kein Abschalten von Abos bekommt ihn
weg, solange der Typ gesetzt ist.

Gebraucht wird der Typ nur, wenn der Standort **nicht** „immer zulassen" ist — dann käme der
Dienst aus dem Hintergrund sonst nicht an den Ort. Mit `ACCESS_BACKGROUND_LOCATION` bringt er
nichts und kostet nur die Anzeige. Deshalb `needsLocationFgsType()`:

```kotlin
homeZone.hasPermission() && !homeZone.hasBackgroundPermission()
```

Nachgeprüft am Gerät: `types=0x40000000` (nur `specialUse`), und der Startzugriff auf den Ort
gelingt weiterhin im Zustand `fgsvc` — keine neue `Reject`-Zeile in `dumpsys appops`.

## Was daraus zu lernen ist

`dumpsys location` beantwortet **nur** die Frage „läuft ein Abo?". Für „wird der Standort
angefasst?" ist `cmd appops get <paket>` die richtige Quelle: Der Zeitstempel `time=+Xs ago` altert,
solange nichts passiert, und springt bei jedem Zugriff zurück. Und die Gegenprobe, die den Fall
gelöst hat, war das **Fehlen** von Logzeilen: Wenn der Standort angefasst wird und der eigene
Prozess dazu schweigt, war es das System.

## Ursache 3: die Standort-Flagge am Netzwerk-Callback (v1.2.4)

Auch ohne FGS-Typ blieben Zugriffe im Minutentakt. Die Messung mit einem Zähler in
`onCapabilitiesChanged` löste es — **eins zu eins**:

```
05:16:02  onCapabilitiesChanged #1 (ssid=LinkBash5)   → Ortungszugriff
05:16:24–05:16:55  keine Zustellung → Zeitstempel wächst 22s → 53s
05:16:59  onCapabilitiesChanged #2                     → nächster Zugriff
```

**Jede Zustellung an einen Callback mit `FLAG_INCLUDE_LOCATION_INFO` ist ein Standortzugriff** —
der WLAN-Name *ist* eine Ortsangabe, das ist genau der Grund, warum die Flagge existiert. Und
zugestellt wird nicht nur bei Netzwechseln, sondern bei jeder Änderung der Capabilities, also etwa
jede Minute.

Am 20.08. hatte ich die Flagge eingebaut, weil ohne sie der Name geschwärzt bleibt (Ursache des
Rückfalls vom 20.08.). Beides stimmt — der Name wird gebraucht, aber nicht dauernd.

**Lösung: zwei Callbacks.**

- Der **Dauer-Callback** läuft ohne Flagge. Er liefert Netz, IP und Verbindungszustand; der Name
  kommt dort geschwärzt an. Wichtig: Ein `null` darf den bekannten Namen **nicht** überschreiben.
- Ein **kurzlebiger zweiter Callback** mit Flagge wird bei jedem WLAN-Beitritt angemeldet und beim
  ersten Namen sofort wieder abgemeldet (`startSsidProbe`/`stopSsidProbe`). Notbremse nach
  `SSID_PROBE_MAX_MS` (20 s), falls gar kein Name kommt — sonst hätte man den Dauerzugriff durch
  die Hintertür zurück.

Damit kostet der Name einen Standortzugriff **je WLAN-Beitritt** statt einen je Minute.

## Die drei Ursachen nebeneinander

| # | Ursache | Zugriffe | behoben in |
|---|---|---|---|
| 1 | Messung bei jedem WLAN-Zucken | Bündel à ~30 s | v1.2.2 |
| 2 | FGS-Typ `location` | alle paar Sekunden | v1.2.3 |
| 3 | Standort-Flagge am Dauer-Callback | ~jede Minute | v1.2.4 |

Keine davon war die Homezone selbst. Sie misst nur noch in einem fremden WLAN, dessen Subnetz
zufällig passt, und auch dort nur einmal je Beitritt.
