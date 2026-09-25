# ELM327 – Mercedes OBD2 Monitor

Android-App zur Überprüfung und Überwachung der OBD-II-Schnittstelle zwischen einem Mercedes C350 W204/M272 und einem ELM327-WiFi-Dongle.

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
- DTC-Abfrage über Mode 03, 07 und 0A
- integriertes ELM327-Terminal
- CSV-Logging nach Downloads/MercedesOBD2Monitor
- kein automatisches Löschen von Fehlercodes

## Android

- Zielgerät: OnePlus 13R / Android 16
- minSdk 26
- compileSdk / targetSdk 37
- Java 17

## Build

GitHub Actions baut bei jedem Push auf `main` eine Debug-APK. Das Artifact heißt:

`Mercedes_OBD2_Monitor_v1.0.6-debug`

Der komplette Android-Quellcode liegt direkt im Repository.
