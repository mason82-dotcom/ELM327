# Auswertung: rnd-ash/mercedes-hacking-docs (und MBUX-Port/204)

**Stand:** 20.11.2026 · Quelle: <https://github.com/rnd-ash/mercedes-hacking-docs> (Commit `305e836`, 13.08.2021),
verlinkt: <https://github.com/rnd-ash/MBUX-Port> (Commit `aad0638`, 28.07.2021).
**Frage:** Was davon gilt für den C350 T-Modell (S204, M272, 722.9, MJ 2008) und was davon passt in die Apps,
ohne die Read-only-Grenze aufzugeben?

## Kurzfassung

- Das Doku-Repo ist eine unvollständige Einführung (9 Kapitel, rund 24 KB Text; Kapitel 5, 6, 8 und 9 sind leer).
  Es behandelt W203-Zeit-Fahrzeuge, den W204 nur indirekt.
- Wirklich verwertbar sind die **XSMonitor-CAN-Beschreibungen für den W204** im Schwesterprojekt MBUX-Port
  (`204/POWERTRAIN.txt`, `CHASSIS_BUS.txt`, `BODY_BUS.txt`, `HEADUNIT_BUS.txt`). Sie nennen Kennung, Bitposition
  und Bedeutung jedes Signals, aber **keinen Faktor und keinen Versatz**.
- Daraus entsteht in den Apps **„Bus mitlesen“** ([ADR-002](adr-002-can-mitlesen.md)): 5 s passiv zuhören
  (`ATMA`), ob der OBD-Port des C350 überhaupt Bus-Botschaften zeigt, und für fünf bekannte Botschaften die
  Rohwerte anzeigen. Es wird nichts an das Fahrzeug gesendet.

## Aussagen des Repos und ihre Bewertung

| Aussage im Repo | Bewertung für den C350 | Folge für die Apps |
|---|---|---|
| Mercedes nutzt die Diagnose-IDs 0x7DF–0x7E7 nicht, man solle tiefer (z. B. 0x300) gehen (Kap. 1) | **Widerlegt** für Motor und Getriebe: der W203-Scan aus der NAG52-Arbeit, die ATF-Berichte (W164, W204 C63) und unsere eigenen Fahrten antworten auf 7E0/7E8 bzw. 7E1/7E9 | keine; ADR-001 bleibt bei 7E1 |
| Der OBD-Port ist über Gateway (EZS/SAM) gefiltert: alles lesen, aber nur Diagnose-IDs schreiben (Kap. 1) | Plausibel, für den W204 nicht belegt. Ob „alles lesen“ beim S204 gilt, ist offen | genau das prüft „Bus mitlesen“ am Fahrzeug |
| Weck-Botschaften je Baureihe, Inhalt `02 10 92 …` an 0x001C, 0x04E4 oder 0x0692 (Kap. 3) | Der **W204 fehlt** in der Tabelle. `10 92` = KWP2000 StartDiagnosticSession „extended“ – das ist eine Sitzungsanforderung an Steuergeräte, kein harmloses Wecken | **nicht übernommen**: würde Header-Freigaben und einen Sitzungswechsel bedeuten (ADR-001 erlaubt nur 7E1/`2130`) |
| ISO 15765 (ISO-TP): Einzel-/Mehrfachframes, Flow Control (Kap. 4) | Korrekt, entspricht dem Standard; der ELM327 erledigt das bei Abfragen selbst | keine |
| K-Line/KWP2000-Weckverfahren (5-Baud, Fast-Init) (Kap. 3) | Für den C350 (CAN) ohne Belang | keine |
| Werkzeuge: Macchina M2, CANtact Pro, J2534-Treiber für Xentry (Kap. 2/3) | Direkter Buszugang hinter dem Gateway, nicht über ELM327 | keine; außerhalb des Projekts |
| Abgriff CAN-B/CAN-C am Kombiinstrument (Kap. 7, W203) | Fahrzeugeingriff, beim W204 andere Steckerbelegung | keine |

## MBUX-Port/204: ausgewählte Antriebsstrang-Botschaften

Bitzählung wie im MBUX-Port-Code (`ECU.h`, `getParam`): Bit 0 ist das höchstwertige Bit von Byte 0, Felder laufen
big-endian über Bytegrenzen. Die Bedeutung stammt aus den übersetzten XSMonitor-Texten; Einheiten wie dort.

| ID | Name | Sender | Signal (Bit, Länge) |
|---|---|---|---|
| 0x30D | ECM_A1 | Motor | Kühlmittel (0, 8) · Ansaugluft (8, 8) · **Motoröl-Temperatur (16, 8)** · **Ölstand mm (24, 8)** · Ölqualität (32, 8) · Verbrauch µl/250 ms (40, 16) |
| 0x105 | ENG_RS3_PT | Motor | Drehzahl U/min (2, 14) |
| 0x2F1 | TCM_A1 | Getriebe | **Getriebeöl-Temperatur (0, 8)** · Übertemperatur (15, 1) · Wählhebel (17, 3) |
| 0x0F3 | ENG_RQ2_TCM | Getriebe | Zielgang (0, 4) · Istgang (4, 4) |
| 0x19F | IC_A1 | Kombi | angezeigte Geschwindigkeit km/h (4, 12) · angezeigte Außentemperatur (16, 8) |

Die vollständigen Dateien enthalten 129 (Antriebsstrang), 351 (Fahrwerk), 429 (Innenraum) und 97 (Head-Unit)
Botschaften. Sie stammen aus Daimlers XSMonitor (DAS-Beigabe); MBUX-Port hat **keine Lizenzdatei**. Deshalb liegen die
Dateien nicht in diesem Repo: `CanMonitor` enthält nur die fünf Zeilen oben als eigene Tabelle mit Quellenangabe.

## Was offen bleibt

1. **Gateway:** Zeigt der OBD-Port des S204 (Pins 6/14) Antriebsstrang-Botschaften? Erwartung: eher nein. Ein Lauf
   von „Bus mitlesen“ bei laufendem Motor beantwortet das.
2. **Skalierung:** Falls Botschaften ankommen, lassen sich Versätze gegen bekannte Werte eichen: Kühlmittel gegen PID
   01 05 (üblich wäre Rohwert − 40), Drehzahl gegen 01 0C, Geschwindigkeit gegen 01 0D, ATF gegen ADR-001
   (Datenbyte 11 nach `61 30` − 50, ADR-003). Erst danach dürfen Werte in °C/mm angezeigt werden.
3. **Ölstand:** Der Ölstand (mm) wäre der wertvollste Zusatz, weil der C350 ihn sonst nur im Kombi zeigt.
   Bei unbekannter Skalierung bleibt er bis zur Eichung (Messstab-Vergleich) ein Rohwert.
