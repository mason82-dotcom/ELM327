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

## 2. Schiebe-/Hebedach öffnen / schließen – **Code 414, N70**

**Fahrzeugangabe des Betreibers (20.11.2026):** normales Schiebe-/Hebedach
im C350 **S204, 2008**, ausdrücklich **kein Panoramadach**.
Das entspricht den Werkstattunterlagen zur Baureihe 204 für die
**elektrische Glas-Schiebe-/Hebedachausführung SA-Code 414**.
Die SA-Code-Zuordnung ist aus den Unterlagen abgeleitet; die konkrete
Datenkarte/VIN des Fahrzeugs wurde bisher **nicht** gegengeprüft.

### Komponenten und Abgrenzung

| Ausstattung | Steuerung / Bauteile | Status |
|---|---|---|
| **414**, normales Schiebe-/Hebedach | **N70** Dachbedieneinheit / Overhead Control Panel, **M12m1** Dachmotor | W204-Dokumentation, auf dem konkreten Fahrzeug noch ungeprüft |
| **413**, Panoramadach | separates Panorama-Dachsteuergerät **A98/A98n1**, eigenes Motorsystem | **Nicht die Ausstattung dieses Fahrzeugs** |

- Für Code **414** beschreibt die Werkstattdokumentation, dass **N70** die
  Polung der Motoransteuerung und damit die Bewegungsrichtung festlegt.
  Referenz: **GF77.20-P-5212CW** (Antriebseinheit) und
  **GF82.20-P-5216CW** (N70), Baujahr-/Variantenangabe beachten.
- Die Dachschalterbedienung erfolgt an der Dachkonsole; die
  Mercedes-W204-Erstschulungsunterlage nennt die **Normierung über den
  Dachschalter** und einen **Einklemmschutz**.
- Die elektrische Prinzipschaltung **PE77.20-P-2050-97FAA**
  ordnet **N70** und **M12m1** dem Dachsystem zu.
- **Wichtig:** Eine Verbindung von N70 zur Fahrzeug-Kommunikation ist **kein**
  Nachweis für einen über die OBD-Buchse erreichbaren, autorisierten
  Diagnose-Aktuatorbefehl. Der ELM327 unterstützt im Projekt bislang nur
  lesende, genau freigegebene Diagnosen.

### Sichere, konkrete Entwicklungsreihenfolge

1. **Datenkarte** auf SA **414** prüfen und das exakte S204-Baumuster/
   Fertigungsdatum sowie N70-Teilenummer dokumentieren (keine VIN im Repository).
2. **Passiven Ist-Zustand** erfassen: Dach funktioniert über Originalschalter,
   Schieben/Heben, definierte Endlagen, keine Störung, Einklemmschutz laut
   Werkstattverfahren geprüft; keinerlei erzwungene Motorbewegung durch die App.
3. **Herstellerdokumentation** des verbauten N70 zu Werkstatt-
   Diagnosefähigkeiten und erreichbarem Gateway/Bus prüfen; vorerst
   **keine** CAN-Injektion, Direktverdrahtung oder Stellgliedaktivierung.
4. Im **Simulator** die geplante HMI ausschließlich als Zustandsmodell
   OFFEN / GESCHLOSSEN / GEKIPPT / UNBEKANNT prüfen, inklusive
   Unterbrechung, Timeout, Sperre bei unbekannter Position sowie STOP.
   **Simulation ist keine reale Positionsmessung.**
5. Erst nach nachweislich unterstützter fahrzeugspezifischer Diagnosefunktion
   und Sicherheits-/Betriebsfreigabe neu über reale Umsetzung entscheiden.

**Keine** verifizierte ELM327-Steuersequenz für Öffnen oder Schließen an
diesem C350. Keine Schiebedachbefehle im Fahrzeugpfad, kein Umgehen
von Einklemmschutz/Normierung.

**Quellen für die Dachvariante:**
- W204-Werkstattunterlage **GF77.20-P-5212CW**, Antrieb des elektrischen
  Glas-Schiebe-/Hebedachs mit Code 414:
  https://lemon.dogeware.me/Mercedes%20Benz/2012/C300%20Luxury%204Matic/Repair%20and%20Diagnosis%20%28Single%20Page%29/Body%20%26%20Frame/Sun%20Roof%2FT-Top%2FConvertible%20Top/Sliding%20Roof%2C%20Soft%20Top%20--%20C300%204MATIC%20%28204.081%29/Basic%20Knowledge/Tilting%2Fsliding%20roof%20drive%20unit%2C%20component%20description%2C%20up%20to%20model%20year%202008%20-%20GF77.20-P-5212CW/
- Mercedes-C350-MJ-2008-Motor- und Dachreferenz, Code 414:
  https://workshop-manuals.com/mercedesbenz/c_350_%28204.056%29/v6-3.5l_%28272.961%29/body_and_frame/roof_and_associated_components/sunroof/moonroof/sunroof/moonroof_motor/component_information/locations/page_15276/
- Elektrischer Funktionsplan **PE77.20-P-2050-97FAA**:
  https://lemon-manuals.la/Mercedes%20Benz/2011/C350/Repair%20and%20Diagnosis%20%28Single%20Page%29/Body%20%26%20Frame/Sun%20Roof%2FT-Top%2FConvertible%20Top/Sliding%20Roof%2C%20Soft%20Top%20--%20%20C63%2FC350%20%28204%29/Wiring%20Diagrams/Electrical%20function%20schematic%20for%20tilting%2Fsliding%20roof%20%28SHD%29%20-%20PE77.20-P-2050-97FAA/
- W204-Erstschulung (Dachsysteme, Normierung am Dachschalter):
  https://www.mersec.net/251/roof_systems.html

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
| ROOF-01 | Dachtyp **normal**, nicht Panorama, vom Betreiber bestätigt; **SA 414** aus W204-Unterlagen, Fahrzeug-Datenkarte und konkrete N70-Teilenummer noch offen | TEILWEISE |
| ROOF-02 | Originalschalter, Einklemmschutz und Normierung nach W204-Herstellerunterlage prüfen | OFFEN |
| ROOF-03 | **N70 / Code 414**: tatsächlich unterstützten OBD-Gateway-Zugriff und eine Hersteller-Werkstattfunktion belegen | OFFEN |
| CODE-01 | SA-Code 414 und N70-Hardware/Softwarestände gegen Fahrzeug-Datenkarte prüfen, Ausgangskonfiguration sichern | OFFEN |
| MEM-01 | Legitime EEPROM-Lese-/Schreibschnittstelle und Risikoanalyse | OFFEN |

**Release-Grenze:** Kein Merge in die Fahrzeug-App, das die reale CommandSafety-Whitelist
für diese Funktionen erweitert, bevor Fahrzeugidentifikation, technische Verifikation,
Risikoanalyse und gesonderte explizite Freigabe vorliegen. Ein Merge dieses reinen
Laborkatalogs ist dagegen möglich.
