# ADR-003: ATF-Byte korrigieren und die Antwort `61 30` nach EGS52-Layout auswerten

**Status:** Angenommen (21.11.2026, Fahrzeugtest am C350)
**Datum:** 20.11.2026
**Entscheidet:** Betreiber (mason)
**Bezug:** [ADR-001](adr-001-atf-temperatur.md) (Freigabe `ATSH7E1` → `2130` → `ATSH7DF`), Recherche
„Mercedes C350 Codierung und Stellglieder“ (20.11.2026)

## Kontext

ADR-001 übernahm aus Foren „°C = Byte[11] − 50“, legte aber nicht fest, ab welchem Byte gezählt wird. Umgesetzt
wurde Index 11 **ab dem Antwortservice 0x61**. Drei unabhängige Quellen zählen anders:

| Quelle | Angabe | Index ab 0x61 |
|---|---|---|
| Torque-PID (drive2.ru, W164, gegen Delphi geprüft) | `L-50`; A = erstes Byte **nach** `61 30` → L = Datenbyte 11 | **13** |
| OBDScanner `mercedes.json` + `tools/cars/SCHEMA.md` | `bix: 88, len: 8, add: -50`; `bix` zählt „data after the echo (after `61 ID`)“, Note „12th data byte“ | **13** |
| ultimate-nag52-fw `egs_emulation.h` (EGS52/722.6, RLI 0x30) | `atf_temp` Bit 104–111 „ab Antwortbeginn“ (Bit 0–15 = `61 30`) | **13** |

Index 11 ab 0x61 ist im EGS52-Layout `gear_recognised` (erkannter Gang). Die App hätte am C350 also
wahrscheinlich den Gang als Temperatur gezeigt (z. B. Gang 1 → −49 °C, „unplausibel“ ab Rohwert 0–9). Die Tests
bildeten die falsche Annahme mit 12-Byte-Antworten ab; eine echte Antwort ist nach dem Layout mindestens 20 Bytes
lang. Am C350 wurde `21 30` noch nie gelesen (ADR-001 Schritt 6 offen).

Dieselbe Antwort enthält nach dem EGS52-Layout weitere Felder (Wandler, Wählhebel, Gang, Momente,
Abtriebsdrehzahl). Belegt ist nur ihre **Lage**: 16-Bit-Werte big-endian (die Emulation dreht in RLI 0x31 jedes
Wort mit `flip_uint16_t`). **Skalierungen sind nicht belegt** – die Emulation füllt RLI 0x30 nur mit Nullen.
Ob das VGS (722.9, 7 Gänge) das Layout des EGS52 (722.6, 5 Gänge) übernommen hat, ist eine Hypothese; dafür spricht
nur, dass die Temperatur an derselben Stelle liegt.

## Entscheidung

**Option B:** Temperatur aus Index 13 ab 0x61 (Datenbyte 11 nach `61 30`). Die übrigen Felder erscheinen als
**Rohwerte** an ihren EGS52-Positionen, deutlich als ungeprüfte Hypothese gekennzeichnet, ohne Einheiten außer der
Temperatur. Damit ist C mit abgedeckt: der bisher gelesene Index 11 steht als „erkannter Gang (EGS52)“ im Bericht.

- Kein neuer Befehl, keine Änderung an `CommandSafety`: ausgewertet wird nur die schon freigegebene Antwort.
- Temperatur nur aus einer Antwort mit mindestens 14 Bytes (`61 30` + 12 Datenbytes); kürzere gelten als „zu kurz“.
- Felder, die die Antwort nicht enthält, fehlen im Bericht (kein Raten).
- Bekannte EGS52-Aufzählungen (Gang, Wandlerzustand) werden als „EGS52: …“ mit Rohwert gezeigt, nie allein.

## Betrachtete Optionen

### A: Nur den Index korrigieren
| Dimension | Bewertung |
|---|---|
| Aufwand | sehr gering |
| Risiko | gering |
| Nutzen für die Fahrzeugprüfung | gering: die übrigen Bytes muss man von Hand aus der Rohantwort lesen |

### B: Index korrigieren und Rohfelder nach EGS52-Layout zeigen (gewählt)
| Dimension | Bewertung |
|---|---|
| Aufwand | gering bis mittel (eine Android-freie Klasse, Tests) |
| Risiko | gering: nur Anzeige; Fehldeutung durch Kennzeichnung „Hypothese, Rohwert“ begrenzt |
| Nutzen | hoch: bei der Fahrzeugprüfung (P/R/N/D, Gänge) sieht man direkt, ob Wählhebel und Gang an den erwarteten Stellen stehen |

### C: Beide Temperatur-Lesarten parallel zeigen
**Vorteile:** keine Vorentscheidung. **Nachteile:** zwei „Temperaturen“ verwirren; drei Quellen sprechen gegen Index 11.
In B enthalten, weil Index 11 als Gangfeld sichtbar bleibt.

### D: Nichts ändern bis zum Fahrzeugtest
**Nachteile:** der Fahrzeugtest liefe mit der wahrscheinlich falschen Formel; die Anzeige wäre irreführend. Abgelehnt.

## Konsequenzen

- Einfacher: Die Prüfung aus ADR-001 Schritt 6 beantwortet mit einer Lesung zugleich, ob das Layout passt.
- Schwerer: Der Bericht wird länger; die Kennzeichnung muss bei jeder Änderung erhalten bleiben.
- Neu bewerten: Nach der Fahrzeugprüfung bestätigte Felder bekommen eine eigene Anzeige (eigene Entscheidung, sobald
  eine Skalierung durch Vergleich belegt ist), unbestätigte fliegen aus dem Bericht.

## Umsetzungsschritte

1. [x] Diese Entscheidung.
2. [x] `AtfTemperature`: Index 13 ab 0x61, Mindestlänge 14 Bytes; Tests mit 20-Byte-Antworten (ISO-TP).
3. [x] `AtfTemperature.layoutFields`: Rohfelder nach EGS52-Layout mit Hypothese-Kennzeichnung; Tests.
4. [x] ADR-001 auf diese Entscheidung verweisen.
5. [x] Am C350: im Stand bei laufendem Motor (Schalten nur so möglich) in P, R, N, D lesen – geprüft wird, ob [9]
       und [11] der Stellung folgen; die Temperatur hängt nicht von der Stellung ab. Am besten warm, dazu eine Lesung kalt
       in P (Zündung an, Motor aus); Rohantworten hier eintragen und Felder
       bestätigen oder verwerfen.

## Ergebnis Fahrzeugtest 21.11.2026 (C350 S204, 722.9, Kaltstart bei 5 °C außen)

| Lage | U/min | [9] Wählhebel | [11] Gang | [12] Ziel/Ist | [13] → ATF |
|---|---|---|---|---|---|
| P, Motor aus | 0 | 10 | 0 | DD | 3A → 8 °C |
| P | 1058 | 10 | 0 | DD | 3A → 8 °C |
| R | 777 | 9 | 0 | BB | 3B → 9 °C |
| N | 734 | 8 | 0 | 00 | 3B → 9 °C |
| D (Stand) | 675 | 7 | 0 | 11 | 3B → 9 °C |
| D, Fahrt | 1683 | 7 | 2 | 32 | 70 → 62 °C |

- **[9] Wählhebel** folgt der Stellung: P = 10, R = 9, N = 8, D = 7 (722.9-Kodierung, nicht die des EGS52).
- **[12] Ziel-/Istgang** je Halbbyte (oben Ziel, unten eingelegt): P = 13 (D), R = 11 (B), N = 0, Gänge 1–7; in der
  Fahrt 32 = Schaltung 2 → 3.
- **[11] erkannter Gang** ist im Stand 0, in der Fahrt 2 – der eingelegte Gang. Während einer Schaltung auch andere Werte.
- **Berichtigung 25.11.2026:** Hier stand zuerst „Ist oben, Ziel unten“ (3 → 2). Die Fahrtlogs belegen das Gegenteil:
  Der Gang vor der Schaltung steht im unteren Halbbyte (38 von 39 Fällen), der Gang danach im oberen (48 von 49).
- **[13] ATF** kalt 8 °C bei 5 °C außen und 6 °C Kühlmittel, warm 62 °C nach 30 min Fahrt: plausibel. Die Formel
  „Datenbyte 11 − 50“ (Index 13 ab `61`) gilt damit als bestätigt.
- **[14]/[16]** sind vorzeichenbehaftet (`FF B3` = −77 bei stehendem Motor).

**Entscheidung:** angenommen. Die App zeigt Wählhebel und Gang im Klartext; die übrigen Felder bleiben Rohwerte.

