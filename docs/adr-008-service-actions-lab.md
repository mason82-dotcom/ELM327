# ADR-008 – W204-Servicefunktionen: isoliertes Labor-Konzept

**Stand:** 20.11.2026 · **Status:** Forschung / Simulator, keine Fahrzeugfreigabe
**Fahrzeug:** Mercedes C350 S204, M272, 2008, 7G-Tronic.
**Ziel:** Die vom Betreiber gewünschten Funktionen (DTC-Löschen, Schiebedach öffnen/schließen,
Codierung, EEPROM, Stellglieder) nachvollziehbar bewerten, ohne den bestehenden
Read-only-Fahrzeugpfad oder seine Tests zu verändern.

## 1. DTC-Löschen (SAE J1979 Mode 04)

- SAE J1979 definiert Service 04 als **Clear/Reset Emission-Related Diagnostic Information**.
  Er kann u. a. emissionsbezogene DTCs, Freeze-Frames, Monitordaten und Readiness zurücksetzen;
  je nach Steuergerät sind weitere herstellerspezifische Reset-Wirkungen möglich.
- **Nicht** gleichbedeutend mit „alle Fehlercodes aller Steuergeräte löschen“:
  ABS, SRS, Komfort- und andere Mercedes-Speicher werden dadurch nicht pauschal abgedeckt.
- Vor einer möglichen künftigen Fahrzeugfreigabe: vollständigen Vorher-Bericht mit DTC,
  Freeze-Frame, Readiness und Zeitstempel sichern, Auswirkungen verständlich bestätigen lassen,
  Zündung/Motorzustand und ECU-Reaktionen am Simulator prüfen, Nachher-Bericht spezifizieren.
- **Jetzt:** `CommandSafety.isAllowed("04") == false`, kein Aufruf über `ObdSession` oder Terminal.
- Referenz: SAE J1979 (2002), Abschnitt 5.4
  https://www.pgmfi.org/files/sae_j1979_207.pdf

## 2. Schiebedach öffnen / schließen

- Die Baureihe 204 kann unterschiedliche Dachausführungen besitzen. Ausstattungs- und
  Baujahrprüfung (z. B. Panoramadach-Code 413) ist vor jeder Protokollannahme nötig.
- Die dokumentierte Panoramadachsteuerung (A98n1) verarbeitet Dachschalter- und
  Hallsensorsignale, betätigt den Dachmotor und berücksichtigt Einklemmschutz,
  Positionsüberwachung und Normierung. Ein Teil der Signale läuft über Innenraum-CAN;
  die Diagnosebuchse bietet nicht automatisch einen direkt freigegebenen Steuerpfad.
- **Kein** validierter ELM327-Diagnosebefehl für Öffnen/Schließen am C350 MJ 2008.
  Kein Senden von geratenen CAN-Frames oder Ersatz des Einklemmschutzes durch Software.
- Referenzen:
  https://charm.li/Mercedes%20Benz/2011/C%20350%204MATIC%20%282204.087%29%20%28CAN%29%20V6-3.5L%20%28272.971%29/Repair%20and%20Diagnosis/Relays%20and%20Modules/Relays%20and%20Modules%20-%20Body%20and%20Frame/Sunroof%20%2F%20Moonroof%20Module/Description%20and%20Operation/
  https://www.mersec.net/263/signal_acquisition_and_actuation_module_sam_.html
  (Die genannten Dokumente betreffen teils andere Baujahre/Ausstattungen; nicht als
  exakte 2008er-Protokollbestätigung verwenden.)

## 3. Codierungsmöglichkeiten

| Bereich | Prüfgegenstand | Freigabestatus |
|---|---|---|
| Komfortschließung | Fahrzeug-/SA-Ausstattung, Dach- und SAM-Variante, Werkstattfunktion | Forschung |
| Spiegelanklappen | Vorhandene elektrische Spiegel, SAM-/Türsteuergerät-Variante | Forschung |
| Dach-Normierung | Endlagen, Hallsensoren, Einklemmschutz, Werksprozedur | Forschung |
| Diagnose-/Steuergerätekennung | Teile- und Softwarestand lesen, bestehende ADR-004 | Testfreigabe nur lesend |

Varianten-/SCN-Codierung, Security Access, Seed/Key und ungeprüfte EEPROM-Adressen
werden **nicht** in die ELM327-Befehlsfreigabe aufgenommen. Bei einer Werkstattprüfung
sind Originalcodierung, Teilenummer, Softwarestand, Batterieunterstützung,
Rücksetz-/Recovery-Konzept und rechtmäßiger Zugang zwingende Voraussetzungen.

## 4. EEPROM und Stellgliedsteuerung

- EEPROM: zunächst Speicher-/Steuergerätetyp, legitime Lesemöglichkeit und
  Datensensibilität dokumentieren. Kein generisches `readMemory` oder `writeMemory`
  über ELM327, kein Dump, kein Patch und keine Schreibfreigabe.
- Stellglieder: Funktionskatalog und Gefährdungsanalyse (insbesondere bewegte
  Komponenten, Einklemmschutz und unerwarteter Start) erstellen. Ohne geprüfte
  Werkstattprozedur ausschließlich simulierte Zustandsübergänge.
- Alle realen Sendewege bleiben hinter der bestehenden `CommandSafety`-Whitelist.
  Die Klasse `ServiceActionLab` hat **keine** Verbindung zu `Elm327Client` und
  kann lediglich nicht ausführbare Vorschläge zurückgeben.

## 5. Test- und Freigabematrix

| Test | Soll | Stand |
|---|---|---|
| LAB-01 | Alle sechs Funktionskategorien als nicht ausführbar gelistet | JUnit vorhanden |
| LAB-02 | `04`, `08`, `14`, `2E`, `2F`, `31`, `27`, `34`, `36`, `3D` weiterhin blockiert | JUnit vorhanden |
| LAB-03 | DTC-Vorher/Nachher- und Readiness-Auswirkungen in Simulator durchspielen | OFFEN |
| ROOF-01 | Exakte Dachausstattung und Dachsteuergerät am Fahrzeug bestätigen | OFFEN |
| ROOF-02 | Einklemmschutz/Normierung nach Herstellerunterlage nachweisen | OFFEN |
| ROOF-03 | Eine tatsächlich unterstützte Diagnosefunktion in Herstellerdokumentation bestätigen | OFFEN |
| CODE-01 | SA-Codes, Software-/Hardwarestände und gesicherte Ausgangskonfiguration | OFFEN |
| MEM-01 | Legitime EEPROM-Lese-/Schreibschnittstelle und Risikoanalyse | OFFEN |

**Release-Grenze:** Kein Merge in die Fahrzeug-App, das die reale CommandSafety-Whitelist
für diese Funktionen erweitert, bevor Fahrzeugidentifikation, technische Verifikation,
Risikoanalyse und gesonderte explizite Freigabe vorliegen. Ein Merge dieses reinen
Laborkatalogs ist dagegen möglich.
