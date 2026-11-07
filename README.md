# ELM327 – Mercedes OBD2 Monitor

Android-App zur Überprüfung und Überwachung der OBD-II-Schnittstelle zwischen einem Mercedes C350 W204/M272 und einem ELM327-WiFi-Dongle.

## OBD Dashboard (Modul `:dashboard`)

Zweite App im selben Repository: die Oberfläche des **OpenNextion-ELM327-Displays** (Reiter LIVE / GRAPH / DTC / INFO) als Android-App, gedacht für die Halterung im Auto.

- **LIVE:** Rundinstrumente für Drehzahl, Kühlmittel und Tempo mit Warn-/Alarmbereich (zusätzlich als Text), Kacheln für STFT/LTFT Bank 1/2 und Steuergerätespannung, KOEO/KOER.
- **GRAPH:** Verlauf von Drehzahl, Tempo, Kühlmittel, Last und den vier Kraftstoffkorrekturen (240 Werte); ungültige Messungen erscheinen als Lücke.
- **DTC:** 03/07/0A nur auf Knopfdruck, Ergebnis auf der Seite DETAILS; HU/AU-Vorcheck.
- **Unterseiten:** alle Werte, Mode 06 (Aussetzer und Monitortests), Fuel-Trim-Test und Warmlauf-Check.
- Quer- und Hochformat, Bildschirm bleibt während einer Sitzung an, Paket `de.roman.obddashboard` (parallel zur Monitor-App installierbar).

Zum Testen ohne Fahrzeug: ELM327-Emulator als Docker-Dienst im Heimnetz, das Handy verbindet sich per WLAN, siehe [tools/elm327-emulator](tools/elm327-emulator/README.md). Bauen ohne Android Studio mit `tools/android-sdk/gradle.sh`.

Beide Apps nutzen denselben geprüften Kern **`:obdcore`** (ELM327-Verbindung, Read-only-Allowlist, Parser, Diagnosen, CSV, Vordergrunddienst). Nicht frisch gelesene Werte (NO DATA, Parser-/Linkfehler, Verbindungsverlust) zeigen beide Apps als „–“ statt des letzten Werts.

## Version 1.1.0 (Monitor) / 0.2.0 (Dashboard): C350-Diagnosefunktionen

Grundlage: Recherche „C350 M272 Diagnosefunktionen“ (07.11.2026). Alles bleibt read-only.

- **Weitere Standard-PIDs**, nur gepollt, wenn das Fahrzeug sie meldet: Kraftstoffsystem-Status (01 03), Zündwinkel (0E),
  absolute Last (43), Drosselklappe relativ/B/Soll (45/47/4C), Fahrpedal D/E (49/4A), Außentemperatur (46), Laufzeit (1F),
  Lambdasonden vor/nach Kat (14/15/18/19), Breitband-Lambda (24/28/34/38), modellierte Kat-Temperatur (3C/3D).
- **Analyse-Seite im Dashboard** (LIVE → ANALYSE): Momentanverbrauch aus MAF und Soll-Lambda (l/h, l/100 km, Schub = 0),
  Liefergrad (HFM/Falschluft-Hinweis), Lambda-Regelgüte je Bank, Kat-Tendenz aus der Nachkat-Sonde, Zündwinkel,
  E-Gas-Plausibilität (Pedalgeber, Soll/Ist Drosselklappe), Bordnetz. Alle Schwellen sind Richtwerte und als solche bezeichnet.
- **Fahrzeugprofil** (Knopf in beiden Apps): Unterstützungs-Bitmaps 01/06/09 je Steuergerät (Motor 7E8, Getriebe 7E9)
  mit Rohantworten zur Dokumentation des Fahrzeugs; nur bei 11-bit-CAN.
- **ATF-Temperatur der 7G-Tronic** auf Knopfdruck, eng begrenzte Ausnahme nach [ADR-001](docs/adr-001-atf-temperatur.md):
  nur `ATSH7E1` → `2130` → `ATSH7DF`, nie im Terminal, Header-Wiederherstellung samt Reconnect mit `ATSH7DF`.
  Formel Byte 11 − 50 ist am C350 noch nicht bestätigt; die Rohantwort wird immer angezeigt.
- **Steuergerätenamen** (09 0A) im HU/AU-Check.

## Version 1.0.9

- HU/AU-Check: die von der ECU gemeldete DTC-Anzahl (01 01) führt zu „nicht bereit“, auch wenn Mode 03 keine Codes liefert
- HU/AU-Check: Pending-DTCs ergeben mindestens „unklar“, nie „bereit“
- neutrale Formulierung „Diagnosespeicher vor kurzem zurückgesetzt“ (Löschen, Steuergerät-Reset oder Batterietrennung) statt „kürzlich gelöscht“
- HU/AU-Abfragen einzeln fehlertolerant: ein Timeout wird per Prompt-Resync überbrückt und als „nicht verfügbar“ angezeigt; fehlendes 03/07 → „unklar“; Abbruch nur bei verlorenem Sync oder 3 Timeouts in Folge
- Mode-06-Aussetzer nur mit UASID 0x24 (Zähler) ausgewertet
- „4A 00“ im DTC-Dialog als „keine permanenten Fehlercodes“ (auch bei mehreren ECUs und Legacy-Frames)
- Regressionstests für alle sechs Punkte

## Version 1.0.8

- Aussetzer-Analyse pro Zylinder über Mode 06 (OBDMID A2–A7, TID 0B EWMA / 0C aktueller Zyklus) mit M272-Bankzuordnung (Bank 1 = Zyl. 1–3, Bank 2 = Zyl. 4–6); zeigt Aussetzer auch ohne bestätigten Fehlercode
- M272-Werkstatthinweise im DTC-Dialog: typische Ursachen für Nockenwelle/Steuertrieb (P0016–P0019), Nockenwellenversteller, Saugrohrklappen (P2004–P2007), Gemisch (P0171/P0174, P2187/P2189), Aussetzer, Thermostat, Sekundärluft, Kat, Tankentlüftung und Sensoren – ausdrücklich keine Diagnose
- Musterhinweise über mehrere Codes (z. B. P0171 + P0174 = gemeinsame Falschluft-Ursache)
- HU/AU-Vorab-Check: Readiness-Monitore (01 01, mehrere Steuergeräte zusammengeführt), MIL, gespeicherte/Pending/permanente Codes, Strecke/Warmläufe seit Löschen (01 31/30), Strecke mit MIL (01 21) und Freeze Frame (Mode 02) mit Gesamteinschätzung bereit / nicht bereit / unklar
- Hinweis auf kürzlich gelöschte Fehlercodes und Fahrzyklus-Empfehlung für offene Monitore
- alle neuen Funktionen nutzen ausschließlich bereits freigegebene Read-Only-Dienste; exklusiver Adapterzugriff pausiert das Live-Polling während der Diagnose
- weitere JUnit-Tests für Readiness, Mode 06, M272-Hinweise und HU/AU-Check

## Version 1.0.7

- ein einzelner ELM327-Timeout erzwingt keinen Reconnect mehr: die App wartet auf den verspäteten `>`-Prompt und verwirft die Spätantwort; neu verbunden wird erst bei 2 Timeouts in Folge oder ohne Prompt
- Reconnect stellt die vollständige Adapter-Initialisierung wieder her (ATAT1, ATST64) und setzt das bei der Erstverbindung erkannte Protokoll fest (ATSPx statt erneuter Auto-Suche); erste Anfrage danach mit längerem Timeout
- CommandSafety erlaubt zusätzlich ATSP1–ATSP9 (feste Standardprotokolle); benutzerdefinierte Protokolle A–C bleiben gesperrt
- Fuel-Trim-Test mittelt STFT/LTFT/MAF/MAP je Kanal unabhängig – jeder frische Wert zählt genau einmal, STFT wird nicht mehr durch das langsame LTFT-Polling ausgedünnt
- während des Fuel-Trim-Tests werden alle Trim-, Drehzahl-, Geschwindigkeits-, MAF- und MAP-PIDs in jedem Zyklus gelesen
- Messphasen mit zu wenig frischen Werten brechen nach 30 s Verlängerung kontrolliert ab statt endlos zu warten; der Bericht nennt die Anzahl der Werte je Kanal
- Reconnect-Fehler schließen nur noch die eigene Session; Werte veralteter Sessions werden nicht mehr übernommen
- neue Tests für LinkPolicy, PollSchedule, ATSP-Whitelist und Fuel-Trim-Kanalmittelung (41 Tests)

## Version 1.0.6

- Verbindungs-Sessions sind generation-basiert getrennt; ältere Connect-/Reconnect-Schleifen können keine neuere Sitzung mehr übernehmen
- IP/Port werden ausschließlich im UI-Thread gelesen und als unveränderliche Werte an Worker übergeben
- jeder ELM327-Timeout verwirft die TCP-Sitzung und erzwingt eine saubere Resynchronisation
- DTC-Timeouts verwerfen die betroffene Verbindung ebenfalls; verspätete Antworten können nicht in den nächsten Diagnosebefehl hineinlaufen
- Fuel-Trim-Test nutzt SystemClock.elapsedRealtime() statt der veränderbaren Systemuhr
- Fuel-Trim-Messpunkte zählen nur, wenn STFT/LTFT beider Bänke seit dem letzten Punkt tatsächlich neu eingelesen wurden
- Leerlauf- und 2500-U/min-Phase verlangen Fahrzeugstillstand und mindestens drei frische Trim-Datensätze
- bei Verbindungsverlust wird ein laufender Fuel-Trim-Test kontrolliert abgebrochen und alte Telemetrie verworfen
- neue JUnit-Regressionstests für CommandSafety, FuelTrimTest und Mode-01/PID-Bitmaps

## Version 1.0.5

- DTC-Dekodierung für CAN (ISO 15765-4) korrigiert: das DTC-Anzahl-Byte wird ausgewertet statt als Teil des ersten Codes gelesen (vorher z. B. P0171 → P0101)
- ISO-TP-Multiframe-Antworten (`00A` / `0:` / `1:`) werden zusammengesetzt; Segmentnummern und Padding gehen nicht mehr in DTCs ein
- Antwortkennung 43/47/4A wird nur am Frame-Anfang erkannt – DTCs mit Byte 0x43 (z. B. P0043) werden nicht mehr zerschnitten
- CAN/Legacy wird aus ATDPN bestimmt; ohne Protokollinfo automatische Erkennung am Antwortformat
- erste JUnit-Tests für den DTC-Parser; CI führt Unit-Tests vor dem APK-Build aus

## Version 1.0.4

- automatische KOEO/KOER-Erkennung anhand der gelesenen Motordrehzahl
- zustandsabhängige Bewertung von PID 0142 (Steuergeräte-/Bordnetzspannung)
- bei KOEO werden STFT und Soll-Lambda sichtbar als derzeit nicht aussagekräftig behandelt
- geführter passiver Fuel-Trim-Test: 20 s Leerlauf + 15 s bei manuell gehaltenen ca. 2500 U/min
- Fuel-Trim-Test wertet ausschließlich bereits gelesene Mode-01-PIDs aus und sendet keine Stellglied-, Codier- oder Schreibbefehle
- Auswertung vergleicht Gesamttrim Bank 1/2 zwischen Leerlauf und 2500 U/min und gibt Diagnosehinweise zu bankspezifischer Abweichung, Falschluft-Muster und dauerhaft positiver/negativer Korrektur
- Android-Standardroute wird zuerst verwendet, um OxygenOS-EPERM beim expliziten WLAN-Binding zu vermeiden
- Multi-ECU-PID-Bitmaps werden vereinigt, damit unterstützte PIDs nicht verloren gehen
- DTC-Antworten mehrerer ECUs werden getrennt dekodiert; 43 00 / 47 00 werden korrekt als keine DTCs behandelt
- kurze Pausen zwischen DTC-Modi und Read-Only-Verbindungsprobe nach dem Scan
- harter Read-Only-Schutz zentral im ELM327-Client
- nur lesende SAE-J1979-Dienste 01, 02, 03, 05, 06, 07, 09 und 0A sind fahrzeugseitig erlaubt
- Mode 04, Mode 08, UDS-Schreib-/Routine-/Security-Dienste und beliebige Raw-CAN-Kommandos werden nicht übertragen
- CAN-Header-/Adressierungsbefehle wie ATSH/ATCRA/ATCEA/ATTA/ATCP sind im Terminal gesperrt
- DTC-Scan mit eigenem Ergebnisdialog und exklusivem ELM327-Zugriff
- WLAN-Fallback über alle verfügbaren WiFi-Network-Handles
- echte Protokollanzeige nach ECU-Erkennung via ATDP/ATDPN
- gestaffeltes PID-Polling für stabilere Clone-Adapter
- TX/RX-Zähler bleiben über Reconnects erhalten

## Funktionen

- ELM327 über TCP/WLAN
- Standard-Endpunkt `192.168.0.10:35000`, in der App änderbar
- gezielte Nutzung des WLAN-Netzes des Dongles
- ELM-Initialisierung und automatische OBD-Protokollerkennung
- unterstützte Mode-01-PIDs werden automatisch erkannt
- Livewerte: Drehzahl, Geschwindigkeit, Kühlmittel, Motorlast, Drosselklappe, MAF, MAP, Ansaugluft, STFT/LTFT Bank 1 und 2, ECU-Spannung, Soll-Lambda und Umgebungsdruck
- Überwachung von ELM-Antwortzeit, Timeouts, NO DATA, I/O- und Parserfehlern
- Auto-Reconnect
- Verbindung läuft als Foreground-Service weiter (Bildschirm aus, App im Hintergrund); Benachrichtigung mit Status und „Trennen“
- DTC-Abfrage über Mode 03, 07 und 0A
- Monitortests (Mode 06): alle Testergebnisse des Motorsteuergeräts (Kat, Lambdasonden, Sondenheizung, Tankentlüftung, Aussetzer …) mit den Grenzwerten des Steuergeräts; markiert Tests mit weniger als 10 % Reserve als „knapp“
- HU/AU-Vorab-Check zusätzlich mit Monitorstatus der laufenden Fahrt (01 41, auch live), FIN, Software-Kennungen (CALID/CVN) und Monitor-Häufigkeit (IUPR, 09 08)
- passiver Warmlauf-/Thermostat-Check (M272, typisch P0128): Warmlaufdauer und Temperaturabfall bei zügiger Fahrt
- integriertes ELM327-Terminal
- CSV-Log wird fortlaufend in den App-Speicher geschrieben (höchstens 2 s Verlust, wenn Android die App beendet; bis 200 MB je Datei, die letzten 20 Dateien bleiben); „CSV exportieren“ kopiert die aktuelle Datei (ohne Log seit App-Start die neueste vorhandene) nach Downloads/MercedesOBD2Monitor, „Neues Log“ beginnt eine neue Datei
- kein automatisches Löschen von Fehlercodes

## Android

- Zielgerät: OnePlus 13R / Android 16
- minSdk 26 (Android 8.0)
- compileSdk 37.2 (Android 17 QPR2), targetSdk 37
- Build-Tools 37.0.0, Android Gradle Plugin 9.5.1, Gradle 9.9.0
- Java 17

## Build

GitHub Actions testet und lintet alle drei Module und baut bei jedem Push auf `main` zwei Debug-APKs:

- `Mercedes_OBD2_Monitor_v1.1.0-debug` (Modul `:app`)
- `OBD_Dashboard_v0.2.0-debug` (Modul `:dashboard`)

| Modul | Inhalt |
|---|---|
| `:obdcore` | Android-Bibliothek: ELM327-Client, Read-only-Allowlist (`CommandSafety`), Parser, DTC/Mode 06/Readiness/HU-AU, Fuel-Trim- und Warmlauf-Test, CSV-Log, `ObdService` – mit allen Unit-Tests |
| `:app` | Mercedes OBD2 Monitor (Werkstatt-Ansicht, Terminal, Konsole) |
| `:dashboard` | OBD Dashboard (Fahr-Anzeige nach OpenNextion-Vorbild) |

Der komplette Android-Quellcode liegt direkt im Repository.

Lokal bauen (Gradle kommt über den Wrapper, benötigt wird nur JDK 17+ und das Android SDK):

```
./gradlew :obdcore:testDebugUnitTest :app:testDebugUnitTest :dashboard:testDebugUnitTest \
          :app:assembleDebug :dashboard:assembleDebug
```
