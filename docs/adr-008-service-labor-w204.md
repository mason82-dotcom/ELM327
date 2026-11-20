# ADR-008 – isoliertes W204-Service-Labor (Mode 04, Dach, Codierung, EEPROM)

**Status:** Experimenteller Entwicklungszweig, ausdrücklich **nicht** auf `main`; erste Revision vom 20.11.2026.
**Fahrzeug:** Mercedes C350 S204/W204, M272, Baujahr 2008, 7G-Tronic (konkrete Steuergeräte-Varianten noch festzustellen).
**Ziel:** Berechtigte Servicefunktionen erforschen und ein isoliertes, prüfbares System mit strikten Freigaben aufbauen.
**Keine Aussage über reale Fahrzeugabnahme.**

## 1. Befund und belegte Funktionen

### A. OBD-Service 04 (emissionsbezogene Diagnoseinformationen löschen)

ELM Electronics erläutert Mode 04 und die **Folgen** auf Daten- und Readiness-Ebene:
[ELM327L-Datenblatt, „Resetting Trouble Codes“](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327L_Data_Sheet.pdf).
[Snap-on OBD-II Service Modes](https://www.snapon.com/DiagnosticsManuals/SOLUS%20Legend_NA/Content/Common/OBD/OBDII%20Service%20Modes.htm).

- **Erreichbar über standardisierte OBD-Diagnose**, sofern das Fahrzeug antwortet.
- Löscht emissionsbezogene DTCs/Freeze-Frames sowie weitere Testdaten, setzt Readiness neu (Not Ready möglich),
  MIL kann ausgehen; permanente Mode-0A-Codes werden **nicht** unmittelbar gelöscht.
- Antwort `44` wird als Bestätigung des Fahrzeugs gewertet. Timeout, `?`, `NO DATA` und negative Antworten
  sind **kein** Nachweis einer Nichtausführung. **Nie automatisch erneut senden**.
- Ein kurzer motorseitiger Neulernprozess und eine ausstehende HU/AU können Folgen sein.
- Nicht geeignet als Diagnoseersatz; Fehlerursache beseitigen, Diagnosedaten **vorher sichern**.

**Implementierter Sicherheitsvertrag:**
- Main-Branch bleibt read-only. `CommandSafety.isAllowed("04")` und Terminalzugriff bleiben gesperrt.
- Nur `Elm327Client.clearEmissionsOnce(ticket)` kann das exakte `04` einmal senden;
  Ticket ist nicht wiederverwendbar, nicht persistiert und per Service-Pfad erzeugbar.
- Kein Mode-04-Aufruf aus Autostart, Hintergrunddienst, automatischem Reconnect, Simulator oder CSV-Import.
- Explizites Aktivieren nur für die gegenwärtige Verbindung; gesonderte Warnung; zweite Bestätigung
  durch exakte Eingabe `LÖSCHEN`.
- Vorher ein vollständig ausgewerteter DTC-Report (Modes 03/07/0A) **innerhalb derselben Sitzung, höchstens 5 Minuten alt**. Teilantworten wie „keine Antwort“, nicht identifizierte Rohantworten und Fehler-/Timeout-Status zählen ausdrücklich nicht.
- Im exklusiven Auftrag unmittelbar vor dem Versand mit aktivierter Header-Anzeige (`ATH1`) neu lesen:
  `010C` RPM = 0, `010D` Tempo 0 und `0142` ECU-Versorgung 11,8–15,5 V.
  **Alle drei Werte müssen nachweislich aus Antwortframes des Motorsteuergeräts `7E8` stammen.**
  Wenn ausschließlich `7E9` antwortet, die Antworten nicht vollständig sind oder der Header nicht erkannt
  wird, **keine Ausführung**. Vor Mode 04 wird `ATH0` wiederhergestellt.
  Grenzwerte sind vorsichtige Entwicklungs-Interlocks, keine Herstellerfreigabe.
- Nur bestätigtes 11-bit-CAN-Protokoll und funktionaler Standardheader `7DF`.
- Vor und nach Mode 04 keine unkontrollierten Fahrzeug-Schreibbefehle. Beim Fehlversuch Verbindung verwerfen,
  Status *unklar* melden, manuell erneut lesen; keine automatische Wiederholung.
- Regenerierung von Fehlermeldungen zählt nicht als erfolgreiche Reparatur.

### B. Dachbetätigung (physische Stellgliedfunktion)

[Mercedes StarTuned 2021 – Sliding Roof Systems](https://automotivetechinfo.com/wp-content/uploads/2021/12/Mercedes-Benz-Sliding-Roof-Systems.pdf)
beschreibt den W204-Elektrikpfad `N70` (DBE / Dachbedieneinheit), `N70s1` (Schalter),
`M12` (Dachantrieb), SAM, Innenraum-CAN; Einklemmschutz, Hinderniserkennung,
Initialisierung/Normalization und thermischen Schutz. Je nach SA-Ausstattung muss
zwischen Code **414** (elektrisches Hub-/Schiebedach) und Code **413** (Panoramadach,
anderer Steuergerätpfad `A98`) unterschieden werden.
[2008er Mercedes-Dokumentation zur Komfortfunktion](https://charm.li/Mercedes%20Benz/2008/C%20230%20%28204.052%29%20%28CANADA%29%20V6-2.5L%20%28272.921%29/Repair%20and%20Diagnosis/Body%20and%20Frame/Locks/Description%20and%20Operation/GF80.45-P-0003CW%20Convenience%20Feature%20%28CF%29%2C%20Function/).
[StarCOM W204-Abdeckung](https://www.obdtester.com/starcom-diagnostic-coverage/mercedes_benz/c/2008_s204_w204)
führt u. a. `ROOF 204` und `TSSR204` als eigenständige Diagnosebereiche.

**Nicht belegt:** sendefähige ECU-Adresse, exakte Anfragebytes, Rückmeldesemantik,
Einklemmschutz/Schalterpriorität **für dieses konkrete Fahrzeug**.
Deshalb derzeit **nur `RoofLab`** mit OFFLINE-Zustandsmodell und Blockadesimulation:
kein Hardwarebackend, keine CAN-Sendung, kein echtes Öffnen/Schließen.

**Echte Freigabe erfordert separat:** Ausstattungs-Code/Steuergerätekennung,
XENTRY-/WIS-Abgleich, bestätigtes Verhalten für Bewegung/Unterbrechung,
physische Überwachung, Quetschschutz, Rückmeldeprüfung, eindeutigen Abbruch und
eine eigene ADR mit Testplan. Ohne diese Nachweise nicht implementieren.

### C. Codierung und EEPROM

**Belegte potenzielle Kandidaten, nicht implementiert:**

| System | Forschungsoption | Voraussetzung / Grenze |
|---|---|---|
| `IC_204` Kombiinstrument | AMG-Menü (Feature-Vorhandensein) | Softwarestand `SW0`/Variante, vollständiges EEPROM-Backup, sicherer Rückweg |
| `N70`/`A98` / `ROOF 204` / `TSSR204` | Diagnose-/Komfortparameter | SA 413/414, Original-Zustand, Einklemmschutz, Steuergerätvariante |
| `SAM-F`/`SAM-R` | Komfort-/Beleuchtungsvarianten | exakt passende Codierparameter, zugelassene Fahrzeugausstattung |
| `VGS` / 7G-Tronic | Steuergeräte-Identifikation/Codierung | fahrzeugspezifische Daten, keine Schalt-/Sicherheitslogikänderungen |
| `CGW` / Gateway | Ausstattungskonfiguration *nur als Inventar* | hohe Nebenwirkung, keine Live-Schreibfreigabe |

MSC Software dokumentiert explizit ein
[AMG-Menu-Coding-Verfahren für `IC_204`](https://www.mscsi.net/amgmenu/de/)
mit EEPROM-Backup und Einschränkung auf unterstützte `SW0`-Stände. Die bloße
Angabe **„per ELM327“ bedeutet nicht**, dass die Byteadressierung auf diesem
C350 bekannt, kompatibel oder sicher ist. Die
[StarCOM-W204-Übersicht](https://www.obdtester.com/starcom-diagnostic-coverage/mercedes_benz/c/2008_s204_w204)
benennt zahlreiche weitere Steuergeräte und Funktionen; ein Eintrag ist keine
Freigabe für unsere eigene Diagnoseimplementierung.

EEPROM-Analyse zunächst **offline** (Backup auf technische Konsistenz und Prüfsumme
prüfen, Versionen inventarisieren, differenzieren); **kein** Lese-/Schreibzugriff
über ECU-Service-Routinen ohne separat belegte Parameter und Recovery.
Keine Wegfahrsperren-/Schlüssel- oder Kilometerstandsänderung, kein Airbag-/ESP-/
Abgasmanipulationspfad; keine Seed-Key-Umgehung oder universelle Write-Konsole.

### D. Stellgliedtests

Mögliche Testkategorien: Schiebedachantrieb, Sitz-/Spiegel-Komfort, SAM-Ausgänge
(nur nach belegter konkreter ECU-Freigabe). Risiken sind Bewegung, Quetschen,
falsche Testadressierung, fehlende Verfahrwege und blockierte Mechanik.
**Aktuell alles offline/simuliert.**

## 2. Integrierter Branch und Verifikation

- Eigener Branch: `feature/w204-service-lab-mode04-roof-coding-20261120`.
- Shared-UX `ServiceLabDialogs` in beiden Android-Apps; klare Markierung „EXPERIMENTELL“.
- `ServiceMode04Gate` prüft Vorbedingungen, `Ticket` ist einmal nutzbar.
- `Elm327Client` besitzt ausschließlich einen separaten, exakten `04`-Transportweg.
- `ObdSession` nutzt exklusiven Adapterzugriff, aktuelle PID-Vorprüfung,
  eindeutige Bestandsaufnahme und kein Write-Replay.
- `RoofLab` simuliert Öffnen/Schließen/Kippen/Stopp/Blockade ohne Gerätezugriff.
- Tests: negative Vorbedingungen, Standard-Whitelist bleibt read-only,
  Einmal-Ticket, Header-Sperre und rein lokale Dachzustände.

**Abnahme:** CI-Unit-Tests, Lint, Android-Build; anschließend Simulator-Tests.
Ein realer Mode-04-Test ist **nicht** durchgeführt und darf wegen des Datenverlusts
nur mit tatsächlichem Ausführungswunsch und dokumentierter Vorprüfung erfolgen.
Echte Dachbetätigung/EEPROM-Codierung sind **nicht freigeschaltet**.

## 3. Phasenplan

1. **P0** – Service-Mode-04-Labortests, Recovery, Readiness- und DTC-Nachleseprüfung.
2. **P1** – Dachvariante 413/414 und Steuergeräte-`ID` mit XENTRY/WIS auslesen (nur lesen);
   Offline-Tests für Blockade, Stopp, Verlust der Verbindung, falsche ECU.
3. **P2** – IC_204-, SAM- und ROOF-Codierfunktionen inventarisieren, Originalwerte
   und Sicherungs-/Wiederherstellungsstrategie verifizieren, separat freigeben.
4. **P3** – Eigenes defensives EEPROM-Offline-Analysemodul für gesicherte Dumps;
   keinen generischen Schreibdienst einschleusen.
