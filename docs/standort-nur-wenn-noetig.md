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
