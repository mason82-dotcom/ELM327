# ADR-007: Lesende Kennungssuche am Motorsteuergerät (`21 01`–`21 FE`) für die Motoröltemperatur

**Status:** Vorgeschlagen (20.11.2026) – Testfreigabe hinter dem Schalter aus ADR-004, ausgeliefert aus
**Datum:** 20.11.2026
**Entscheidet:** Betreiber (mason)
**Bezug:** [ADR-001](adr-001-atf-temperatur.md), [ADR-004](adr-004-kwp-kennung-fehlerspeicher.md),
Wettbewerbsanalyse „W204 & W212 OBD Pro“ (Lücke „Motoröltemperatur“)

## Kontext

Die Konkurrenz-App zeigt eine Motoröltemperatur an, unsere Apps nicht.

**Korrektur 20.11.2026 (Werkstatthandbuch, WIS GF18.40-P-4114CY, GF07.61-P-3026CY, GF07.61-P-4021CY):** Der Motor
272.961 im Modell 204.0 **bis Modelljahr 08** hat **keinen** Ölsensor B40, sondern nur den Ölstandsschalter S43
(Schwimmer mit Reed-Kontakt). Das ME9.7 bestimmt die Öltemperatur aus einem **Temperaturmodell** (Last, Kühlmittel,
Zeit) für Nockenwellenverstellung und Lüfternachlauf. Die Suche kann daher höchstens diesen **Rechenwert** finden –
nützlich als Anzeige, aber kein Messwert und kein unabhängiger Warngeber. Fahrzeuge ab MJ 09 können B40 haben
(Baudatum/Motornummer im Prüfplan festhalten).

Recherche 20.11.2026:
- Der Standard-PID `01 5C` (Öltemperatur) wird vom C350 **nicht** unterstützt: die Logs zeigen nur PIDs bis `01 4C`,
  die Unterstützungs-Bitmap `01 40` meldet nichts darüber.
- Öffentlich ist **keine** Kennung für ME9.7 belegt. Foren (MBWorld W211/W204) kennen für Torque nur die
  Getriebeöltemperatur `21 30` an 7E1 (bei uns ADR-001); die Frage nach der Motoröltemperatur bleibt dort offen.
- KWP2000 `21 xx` (ReadDataByLocalIdentifier, ISO 14230-3) ist ein reiner Lesedienst. Das ME9.7 beantwortet
  KWP in der Default-Sitzung (`1A 86` aus ADR-004); `21 xx` braucht nach Referenz ebenfalls keinen Sitzungswechsel.

Es bleibt nur, die lokalen Kennungen des Motorsteuergeräts selbst zu lesen und die Öltemperatur darin zu erkennen.

## Entscheidung

**Option B: einmalige Vollsuche `21 01`–`21 FE` an 7E0, nur auf Knopfdruck, hinter dem ADR-004-Schalter.**

- Freigabe: `21` + eine Kennung `01`–`FE`, **nur bei Header 7E0** und nur, solange der Client ausdrücklich im
  Suchmodus ist (`Elm327Client#setLocalIdScan`, im `finally` zurück); nicht im Terminal, nicht an 7E1 (dort bleibt nur
  `21 30`), nicht an 7DF, nicht in anderen Abläufen auf 7E0 (ADR-004). Keine anderen Dienste: kein `10`, `27`, `2E`,
  `30`, `31`, `3B`, `14`.
- Ablauf als exklusive Diagnose: `ATSH7E0` → 254 × `21 xx` (je 1 s Frist, kurze Pause) → `ATSH7DF`; Header im
  `finally` zurück, sonst Verbindung verwerfen (wie ADR-001/004). Kurz vor der Suche wird `01 05` gelesen.
- Keine Wiederholung, keine Abwandlung. Absagen (`7F 21 xx`) und fehlende Antworten werden gezählt. Die Suche endet
  vorzeitig bei acht Anfragen in Folge ohne Antwort (Zeitüberschreitung oder `NO DATA`), bei „Dienst nicht
  unterstützt/nicht in dieser Sitzung“ (`7F 21 11/7F/80`, gilt für alle Kennungen), bei STOP (zweiter Druck), wenn der
  Testschalter ausgeht oder der Adapter nach einer Zeitüberschreitung nicht wieder synchron wird. Bis dahin gelesene
  Antworten bleiben immer im Bericht.
- Ergebnis: alle positiven Antworten roh (Kennung, Länge, Bytes) plus **Kandidaten**: Bytes, die als Temperatur
  gelesen (`A − 40`, `A`, `0,75·A − 48`) im Fenster Kühlmittel −5 K … +5 K (kalt) bzw. +25 K (ab 60 °C; warmes Öl ist
  meist 5–20 K wärmer) liegen. Bericht im Prüfplan.
- Der letzte Lauf wird im App-Speicher abgelegt. Liegt der neue Lauf mindestens 20 K wärmer oder kälter, meldet der
  Bericht **bestätigte** Kandidaten: gleiche Kennung, gleiches Byte, gleiche Skalierung, in beiden Läufen im Fenster und
  in Richtung der Kühlmitteltemperatur mitgewandert. Erst dann eine eigene, exakte Freigabe wie ADR-001 (`21 xx` an
  7E0) mit Anzeige; die Vollsuche entfällt danach wieder.

## Betrachtete Optionen

### A: Nur `1A 86` wie bisher (Ist-Zustand)
| Dimension | Bewertung |
|---|---|
| Aufwand | keiner |
| Risiko | keines |
| Nutzen | keine Öltemperatur |

### B: Vollsuche `21 01`–`21 FE` an 7E0 (gewählt)
| Dimension | Bewertung |
|---|---|
| Aufwand | mittel (Freigabe-Muster nur für 7E0, Ablauf, Auswertung, Knopf) |
| Risiko | gering: ein Lesedienst, ein Steuergerät, keine Sitzung; ~254 Anfragen in etwa 1 min, einmalig |
| Nutzen | findet die Kennung, falls das ME9.7 sie über `21` liefert; Rohdaten auch für weitere Werte |

**Nachteil:** erstmals ein Muster statt exakter Zeichenketten in der Freigabe – deshalb an Header 7E0 und an den
Schalter gebunden, mit Tests, dass `21 xx` an 7DF/7E1 und alle anderen Dienste gesperrt bleiben.

### C: Teilsuche (z. B. `21 01`–`21 3F`)
Weniger Anfragen, aber ohne Beleg, wo die Kennung liegt; ein Fehlschlag wäre nicht aussagekräftig.

### D: Mitschnitt der Konkurrenz-App am Simulator
Zeigt, **welche** Anfrage sie stellt, ohne die App zu zerlegen. Braucht die App (Kauf, Installation am Radio) und
liefert keine Skalierung, weil der Simulator Herstelleranfragen nicht beantwortet. Bleibt als Gegenprobe offen.

## Konsequenzen

- Einfacher: Öltemperatur-Rechenwert (und weitere ME-Werte) ohne Werkstatttester auffindbar.
- Schwerer: `CommandSafety` hat ein headergebundenes Muster; jede Änderung an der Header-Logik muss es mitprüfen.
- Neu bewerten: nach zwei Läufen am C350 – Kennung exakt freigeben und Suche entfernen, oder ADR verwerfen.

## Umsetzungsschritte

1. [x] Diese Entscheidung.
2. [x] `CommandSafety`: `21 01`–`21 FE` nur bei Header 7E0, nicht im Terminal; Tests.
3. [x] `LocalIdScan` (Android-frei): Antworten einordnen, Kandidaten gegen Kühlmittel; Tests.
4. [x] Ablauf in `DiagnosticJobs` mit Header-Wiederherstellung; Knopf „KENNUNGSSUCHE“ im Dashboard (Schalter an),
   zweiter Druck stoppt; Prüfplanschritt „kalt und warm“.
5. [x] Code-Review (medium): 9 Befunde behoben (Schweigen per NO DATA, Teilergebnis bei Abriss, Kühlmittel im
   Prüfplan, Freigabe nur im Suchmodus, STOP, Dienst-Absage, Fenster, PID-Suche, Meldungstexte).
6. [ ] Am C350 kalt und warm ausführen, Prüfplan teilen, Kandidaten hier eintragen.
