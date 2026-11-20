# DTC-/Verbindungswechsel-Regression – 20.11.2026

## Ziel und Sicherheit

Zwei Android-Apps (`:app`, `:dashboard`) verwenden denselben `:obdcore`. Ein Diagnoseauftrag darf
Status, Dialog und Prüfplan nur in der Sitzung aktualisieren, in der er angenommen wurde.
Ein ELM-Prompt `>` allein bestätigt keinen `ATSH`-Headerwechsel: Erst eine eigene `OK`-Zeile
erlaubt die genau freigegebene herstellerspezifische Leseabfrage.

**Nur lesen:** DTC-Löschung (`04`), Codierung, Aktuatoren, Security Access, Schreib-/Flashdienste
bleiben gesperrt. Es wird keine Fahrzeugkonfiguration verändert.

## Automatisierte Prüfung

```sh
./gradlew :obdcore:testDebugUnitTest :app:testDebugUnitTest :dashboard:testDebugUnitTest \
          :obdcore:lintDebug :app:lintDebug :dashboard:lintDebug \
          :app:assembleDebug :dashboard:assembleDebug
```

- `SessionEpochTest`: Ergebnisse und UI-Aufträge nach Trennen, bei inaktiver Sitzung und nach
  Neuverbinden ablehnen; verspätete UI-Zustellung simulieren.
- `Elm327ClientTest`: abgelehntes `ATSH7E1` (`?`) lässt den Header unbekannt; `2130`
  wird **vor dem Senden** gesperrt. Nach bestätigtem `ATSH7DF` sind normale PIDs möglich.
- `DiagnosticJobsTest`: DTC Mode 03/07/0A, Resync, Timeout und Verbindungsverwerfung.

## Manuelle Abnahme (zuerst Simulator, dann nur am stehenden Fahrzeug)

| Test | Schritte | Erwartung | Befund |
|---|---|---|---|
| DTC-01 | Verbinden, DTC/LESEN antippen | Busy sichtbar; exklusiver Adapterzugriff, Live-Polling pausiert | UNBEKANNT |
| DTC-02 | Während DTC Trennen drücken | Status Getrennt, Ladehinweis beendet; kein später DTC-Dialog oder Prüfplaneintrag | UNBEKANNT |
| DTC-03 | Nach Abbruch neu verbinden | Kein Ergebnis/Spinner aus vorheriger Verbindung | UNBEKANNT |
| DTC-04 | DTC zweimal schnell antippen | Nur ein 03/07/0A-Durchlauf, keine Doppel-Ergebnisse | UNBEKANNT |
| DTC-07 | Während 07 blockiert, eigene STOP-/Abbrechen-Taste antippen | Socket wird geschlossen; keine 0A-/0100-Nachabfrage, kein Ergebnis/Prüfplaneintrag, Reconnect | UNBEKANNT |
| DTC-08 | Direkt nach Abbruch neue Diagnose starten, alte UI-Callbacks spät zustellen | Keine alte Busy-/Status-/Ergebnisanzeige in der neuen Auftragskennung | UNBEKANNT |
| DTC-05 | DTC-Antwort künstlich verzögern | Timeout/Resync korrekt sichtbar, keine veraltete Antwort | UNBEKANNT |
| DTC-06 | Adapter während DTC trennen | Reconnect/Fehler statt eines irreführenden Erfolgs | UNBEKANNT |
| HDR-01 | Simulator antwortet auf ATSH7E1 mit ? | Header unbekannt; keine 2130-Aussendung | UNBEKANNT |
| HDR-02 | Simulator bestätigt ATSH7DF mit OK | Normaler OBD-Header und Live-PIDs wiederhergestellt | UNBEKANNT |
| BT-01 | Gekoppelten SPP-Adapter verbinden und trennen | Gleiche DTC-/Headerregeln wie bei WLAN/TCP | UNBEKANNT |

**Noch keine Fahrzeugabnahme dieses Branches.** Bereits bestätigte 010C, 0105 und 010D
belegen weder eine vollständige DTC-Abnahme noch Bluetooth-Betrieb. Für jedes reale Ergebnis
Adapter, Android-Version, Zeitpunkt und relevante anonymisierte Rohlogs dokumentieren.

## Anschlussaufgaben

- DTC-Abbruch und Auftragskennungen wurden als nächster Entwicklungsstand auf dem Feature-Branch
  implementiert. Nicht mit einer Fahrzeugabnahme verwechseln: die oben genannten praktischen Tests
  stehen weiterhin aus.
- Android-Instrumentierungstest für Handler-Queue, Activity-Lifecycle und Service.
