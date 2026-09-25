# ELM327 – Mercedes OBD2 Monitor

Android-App zur Überprüfung und Überwachung der OBD-II-Verbindung zwischen einem Mercedes C350 W204/M272 und einem ELM327-WiFi-Dongle.

## Aktueller Stand

- Android-App v1.0.0
- ELM327 über TCP/WLAN
- Standard-Endpunkt: `192.168.0.10:35000`, in der App änderbar
- Live-PIDs inklusive STFT/LTFT Bank 1/2, MAF, MAP, RPM, Kühlmittel, ECU-Spannung und Soll-Lambda
- DTC-Abfrage: gespeicherte, pending und permanente Fehler
- ELM327-Terminal
- Antwortzeit-, Timeout- und Verbindungsüberwachung
- CSV-Logging
- Auto-Reconnect
- kein automatisches Löschen von Fehlercodes

## Build

GitHub Actions baut die App automatisch aus `Mercedes_OBD2_Monitor_v1.0.0_Source.zip`.
Das erzeugte Artifact heißt `Mercedes_OBD2_Monitor_v1.0.0-debug`.

Zielgerät: OnePlus 13R / Android 16. Das Projekt ist zusätzlich für Android API 37 vorbereitet.
