# ELM327 – Mercedes OBD2 Monitor

Android-App zur Überprüfung und Überwachung der OBD-II-Schnittstelle zwischen einem Mercedes C350 W204/M272 und einem ELM327-WiFi-Dongle.

## Version 1.0.3

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

`Mercedes_OBD2_Monitor_v1.0.3-debug`

Der komplette Android-Quellcode liegt direkt im Repository.
