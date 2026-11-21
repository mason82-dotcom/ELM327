# ADR-004: Steuergeräte-Kennung (`1A 86`) und Getriebe-Fehlerspeicher (`18 02 FF 00`) als Testfreigabe

**Status:** Getriebe-Teil angenommen, Motor-Teil verworfen (21.11.2026, Fahrzeugtest) – weiter hinter dem Testschalter
**Datum:** 20.11.2026
**Entscheidet:** Betreiber (mason)
**Bezug:** [ADR-001](adr-001-atf-temperatur.md), [ADR-003](adr-003-atf-antwort-layout.md), Recherche
„Mercedes C350 Codierung und Stellglieder“ (20.11.2026)

## Kontext

Die Recherche empfiehlt als zweite Stufe nach `21 30` zwei weitere **lesende** KWP2000-Dienste in der
Default-Sitzung: die Daimler-Kennung des Steuergeräts und den herstellereigenen Fehlerspeicher des Getriebes.
Der OBD-Fehlerspeicher (Mode 03/07/0A) zeigt nur abgasrelevante Codes; Getriebecodes der 7G-Tronic, die keine
MIL auslösen, sieht man dort nicht.

Belege (Referenzbibliothek [ecu_diagnostics](https://github.com/rnd-ash/ecu_diagnostics), „KWP2000 v2.2 nach
Daimler-Standard“; Getriebeemulation [ultimate-nag52-fw](https://github.com/rnd-ash/ultimate-nag52-fw)):

| Anfrage | Bedeutung | Antwort laut Referenz | Sitzung |
|---|---|---|---|
| `1A 86` | ReadECUIdentification, Daimler-Kennung | `5A 86` + 16 Bytes: Teilenummer (BCD, 10 Ziffern), HW-Woche/Jahr, SW-Woche/Jahr, Lieferant, Diagnose-Info (2), –, Herstelldatum J/M/T (BCD) | Default |
| `18 02 FF 00` | ReadDTCByStatus „gespeicherte“, alle Gruppen | `58` + Anzahl + je Code 2 Bytes + 1 Statusbyte | Default |

Am C350 ist keines davon geprüft; ob ME9.7 und VGS so antworten, ist **vermutet**. Beide Dienste lesen nur. Sie
brauchen weder Sitzungswechsel (`10`) noch TesterPresent (`3E`) noch Security Access (`27`) – die bleiben gesperrt.
Ein ELM327-Klon kann sie senden (Einzelframe-Anfrage, Mehrframe-Antwort). Neu ist der Header **7E0**
(Motorsteuergerät); bisher war nur 7E1 freigegeben.

## Entscheidung

**Exakte Tupel wie ADR-001, zusätzlich hinter einem Schalter, der ausgeliefert aus ist:**

- Freigegeben werden nur `ATSH7E0`, `1A86` (bei Header 7E0 oder 7E1) und `1802FF00` (nur bei Header 7E1).
  Keine anderen Identifier, keine Muster, nicht im Terminal.
- Ablauf als eine exklusive Diagnose: `ATSH7E0` → `1A86` → `ATSH7DF` → `ATSH7E1` → `1A86` → `1802FF00` →
  `ATSH7DF`; die Wiederherstellung von 7DF steht im `finally`, scheitert sie, wird die Verbindung verworfen
  (Reconnect sendet `ATSH7DF`). Bei einem anderen Header als 7DF ist wie bisher nur der Rückweg frei.
- Schalter „Herstellerabfragen nach ADR-004 (Test)“ in den Einstellungen, ausgeschaltet. Erst damit erscheint der
  Knopf „Kennung + Getriebe-Fehler“. Der erste Lauf am C350 ist der Test; danach wird diese ADR angenommen
  (Schalter entfällt) oder verworfen (Code entfällt).
- Anzeige immer mit Rohantwort. Absage (`7F 1A …`/`7F 18 …`) wird mit Code angezeigt, nichts wird automatisch
  wiederholt oder abgewandelt. „Antwort folgt“ (`7F xx 78`) heißt: der Adapter hat nicht nachgewartet.
- Fehlerlöschen (`14`) bleibt gesperrt.

## Betrachtete Optionen

### A: Exakte Tupel, Schalter aus (gewählt)
| Dimension | Bewertung |
|---|---|
| Aufwand | mittel (Freigabe, Ablauf, zwei Parser, Schalter) |
| Risiko | gering: zwei Lesedienste, kein Sitzungswechsel; im Alltag ohne Schalter nicht erreichbar |
| Nutzen | Teilenummern/Softwarestand für Werkstatt und Teilekauf, Getriebecodes ohne MIL |

### B: Exakte Tupel, sofort freigegeben
**Nachteile:** würde ungeprüfte Herstelleranfragen in jeder Diagnose senden. Abgelehnt bis nach dem Test.

### C: Weitere Identifier (`1A 87/88/90`) und Statusvarianten (`18 00/03`) gleich mit
**Nachteile:** mehr ungeprüfte Fläche ohne zusätzlichen Nutzen für die erste Prüfung. Später einzeln entscheiden.

### D: Nicht umsetzen
**Nachteile:** der herstellereigene Getriebefehlerspeicher bliebe unsichtbar.

## Konsequenzen

- Einfacher: Teilenummern und Getriebecodes ohne Werkstatttester.
- Schwerer: `CommandSafety` kennt einen zweiten Herstellerheader; jede Änderung an der Header-Logik muss beide
  Ausnahmen mitprüfen (Tests).
- Neu bewerten: nach dem ersten Lauf am C350.

## Umsetzungsschritte

1. [x] Diese Entscheidung.
2. [x] `CommandSafety`: exakte Tupel, headerabhängig, nicht im Terminal; Tests.
3. [x] `KwpReadout` (Android-frei): Kennung und Fehlerliste auswerten; Tests mit Antwortformen aus der Referenz.
4. [x] Sitzung: exklusive Diagnose mit Header-Wiederherstellung; Schalter aus.
5. [x] Am C350 einmal mit Schalter an ausführen, Rohantworten hier eintragen, ADR annehmen oder verwerfen.

## Ergebnis Fahrzeugtest 21.11.2026

| Anfrage | Antwort | Auswertung |
|---|---|---|
| `ATSH7E0` → `1A 86` (Motor) | NO DATA | das ME9.7 antwortet unter 7E0 nicht auf KWP-Dienste |
| `ATSH7E1` → `1A 86` (Getriebe) | `5A 86 00 34 46 03 10 47 06 19 08 08 04 02 00 08 09 30` | A 003 446 03 10, HW KW 47/06, SW KW 19/08, Lieferant 08, Serie, 30.09.2008 |
| `18 02 FF 00` (Getriebe) | `58 00` | keine gespeicherten Fehler |

**Entscheidung:** Getriebe-Teil angenommen (Format wie Referenz). Motor-Teil verworfen: `ATSH7E0` und `1A 86` an 7E0
entfallen aus der Freigabe; der Ablauf fragt nur noch das Getriebe. Der Testschalter bleibt, bis der Getriebe-Teil
einige Male ohne Auffälligkeit lief.

