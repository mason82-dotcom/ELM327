# ELM327 – Mercedes OBD2 Monitor

Zwei Android-Apps, die über einen ELM327-WLAN-Adapter die Diagnosedose eines **Mercedes C350 T-Modell S204 (M272,
7G-Tronic, Baujahr 2008)** auslesen – **nur lesend**: keine Codierung, kein Löschen von Fehlercodes, keine
Stellglieder.

| App | Paket | Wofür |
|---|---|---|
| **Mercedes OBD2 Monitor** (`:app`) | `de.roman.mercedesobdmonitor` | Werkstatt-Ansicht: alle Werte, Diagnosen, Konsole, Terminal |
| **OBD Dashboard** (`:dashboard`) | `de.roman.obddashboard` | Anzeige für die Halterung (Quer-/Hochformat), Reiter LIVE / GRAPH / DTC / INFO |

Beide lassen sich nebeneinander installieren und nutzen denselben Kern `:obdcore`. Was sich wann geändert hat, steht
in [CHANGELOG.md](CHANGELOG.md).

## Schnellstart

1. ELM327 in die Diagnosedose stecken, Zündung an.
   - **WLAN-Adapter:** Handy/Radio mit dem WLAN des Adapters verbinden, Adresse prüfen (Standard `192.168.0.10:35000`).
   - **Bluetooth-Adapter:** einmal in den Android-Einstellungen koppeln (PIN meist 1234 oder 0000), dann in der App
     „Adapter: … · ändern“ (Dashboard: INFO) das Gerät wählen. Gut für Android-Radios, die so ihr WLAN behalten.
2. **Verbinden**. Fürs Radio: unter INFO „Automatisch verbinden“ einschalten – dann verbindet das Dashboard beim
   Öffnen und nach dem Radiostart selbst (die Oberfläche selbst über die Autostart-Liste des Radios öffnen).
3. Die App erkennt Protokoll und unterstützte Werte selbst; Livewerte erscheinen nach wenigen Sekunden. Werte, die
   gerade nicht frisch gelesen werden, zeigt sie als „–“ statt des letzten Werts.

Die Verbindung läuft als Vordergrunddienst weiter (Bildschirm aus, App im Hintergrund) und schreibt fortlaufend ein
CSV-Log.

## Termin am Auto: der Prüfplan

Für einen Termin am Fahrzeug gibt es eine Checkliste (Dashboard: **INFO → PRÜFPLAN**, Monitor-App: Abschnitt
„Prüfplan“). Sie hakt jeden Schritt anhand des echten Ergebnisses ab – mit Kühlmitteltemperatur und Drehzahl, damit
„kalt“, „warm“ und „Motor läuft“ stimmen:

1. **Kennung + Getriebe-Fehler** – vorher den Schalter „Herstellerabfragen nach ADR-004“ einschalten.
2. **Starttest** – nach mehreren Stunden Standzeit, bei „Jetzt starten“ den Motor starten (Batterie vorher laden,
   wenn die Ruhespannung unter 12,4 V liegt).
3. **Bus mitlesen** – bei laufendem Motor (ab 400 U/min).
4. **Monitortests** und 5. **Aussetzer** (Mode 06) – warm nach einer Fahrt bzw. nach einigen Minuten Leerlauf.
6. **Fehlercodes**.

Die ATF-Schritte (kalt sowie Wählhebel/Gang in P, R, N, D) sind seit dem Fahrzeugtest vom 21.11.2026 erledigt:
ADR-003 ist bestätigt, „ATF lesen“ zeigt jetzt Temperatur, Wählhebel und Gang im Klartext.

Danach **TEILEN**: ein Text mit Checkliste und allen Ergebnissen samt Rohantworten. Jede App führt ihren eigenen Plan.

## Funktionen

**Livewerte und Verlauf**
- Drehzahl, Tempo, Kühlmittel, Last, Drosselklappe, Luftmasse, Saugrohrdruck, Ansaugluft, STFT/LTFT beider Bänke,
  Steuergerätespannung, Soll-Lambda, Umgebungsdruck; Dashboard mit Rundinstrumenten und Verlaufskurven.
- Werte des Motorsteuergeräts (7E8), auch wenn das Getriebe dieselben PIDs beantwortet.
- **Warnungen (Dashboard):** Leiste über allen Seiten bei hoher Kühlmitteltemperatur (gelb ab 108 °C, rot ab 115 °C),
  schwacher Batterie bei Zündung an (unter 11,8 V), zu wenig Ladung (unter 12,6 V; bei voller Batterie senkt das Steuergerät die Ladespannung absichtlich) oder Überspannung (über 15,0 V) bei
  laufendem Motor. Rot mit Ton (auch wenn eine andere App vorn ist), „Ton aus“ je Meldung. Orientierungswerte, keine
  Herstellerangabe; nur aus den ohnehin gelesenen Werten.

**Auswertung (Richtwerte, keine Diagnose)**
- **Analyse:** Verbrauch aus Luftmasse, Liefergrad, Lambda-Regelung, Kat-Tendenz, Zündwinkel, E-Gas, Bordnetz.
- **Trim-Karte** je Lastbereich und Bank, zerlegt in den **gemeinsamen Anteil** beider Bänke (Falschluft, Luftmassenmesser,
  Kraftstoffdruck) und den **Zusatz einer Bank**; **Trim-Verlauf** über alle Verbindungen, auch aus importierten Logs.
- **Fahrtenbuch** aus den CSV-Logs: Strecke, Ø-Verbrauch, Leerlaufanteil, Warmlaufzeit, Trim-Differenz je Fahrt; als CSV
  teilbar. Trims nach kalten Kurzstrecken (Kraftstoff im Öl) sind markiert und zählen nicht in den Mittelwert.
- **Gemischadaption je Bank** aus den Monitortests (Mode 06, MID 81/82): vom Steuergerät gelernter Faktor je Bank mit
  Abstand zur Grenze und Verlauf über alle Lesungen – zeigt, ob Bank 1 Richtung Grenze wandert.
- **Fuel-Trim-Test** (Leerlauf gegen 2 500 U/min) und **Warmlauf-Check** (Thermostat).

**Diagnose auf Knopfdruck**
- **Fehlercodes** (Mode 03/07/0A) mit der Bedeutung am Motorsteuergerät ME9.7 (Seite, Bauteil, Teilfehler) und M272-Werkstatthinweisen und separater **Abbrechen**-Taste: beendet die laufende Abfrage, verwirft unvollständige Ergebnisse und baut die Adapterverbindung bei Bedarf wieder auf; **HU/AU-Vorcheck** (Readiness, FIN, CALID/CVN, IUPR) mit Fahrzyklus-Hilfe je offenem Monitor.
- **Mode 06:** Aussetzer je Zylinder, alle Monitortests mit Grenzwerten, Bankvergleich der Lambdasonden.
- **Fahrzeugprofil:** welche Werte Motor (7E8) und Getriebe (7E9) melden.
- **ATF-Temperatur** der 7G-Tronic, dazu die übrigen Felder derselben Antwort als ungeprüfte Hypothese.
- **Starttest:** Ruhespannung, Einbruch und Dauer beim Anlassen, Ladespannung (nur `ATRV`); bewertet mit Herstellerwerten
  (Startgrenze ~7 V, Batterietest ab 12,4 V, Ladung bis 15 V); gleicht die Adapter-Spannung mit dem Motorsteuergerät
  ab und bewertet bei ungenauem Adapter nach dem Steuergerät.
- **Bus mitlesen:** 5 s passiv, ob die Diagnosedose Bus-Botschaften durchreicht.
- **Kennung + Getriebe-Fehler** (Testfreigabe, ausgeliefert aus): Teilenummer/Softwarestand von Motor und Getriebe,
  herstellereigener Getriebe-Fehlerspeicher.

**Werkzeuge**
- Konsole mit allen Rohantworten, ELM327-Terminal (nur freigegebene Befehle), Statistik zu Antwortzeit und Fehlern.
- CSV-Log im App-Speicher (bis 200 MB je Datei, die letzten 20 bleiben); **CSV exportieren** nach
  Downloads/MercedesOBD2Monitor, **Neues Log**, **Log importieren** für Trim-Verlauf und Fahrtenbuch.

## Sicherheit: nur lesen

Jeder Befehl läuft durch eine feste Freigabeliste (`CommandSafety`): Standard-OBD-Lesedienste und wenige AT-Befehle.
Jede Ausnahme ist eine eigene Entscheidung mit exakt festgelegten Befehlen und Rückkehr zum Standard-Header:

| Entscheidung | Was sie freigibt |
|---|---|
| [ADR-001](docs/adr-001-atf-temperatur.md) | ATF-Temperatur: `ATSH7E1` → `21 30` → `ATSH7DF` |
| [ADR-002](docs/adr-002-can-mitlesen.md) | Bus mitlesen: `ATMA` zeitbegrenzt, `ATCAF0/1` |
| [ADR-003](docs/adr-003-atf-antwort-layout.md) | Auswertung der ATF-Antwort: Wählhebel, Gang, ATF – am C350 bestätigt (kein neuer Befehl) |
| [ADR-004](docs/adr-004-kwp-kennung-fehlerspeicher.md) | Getriebe-Kennung `1A 86` und -Fehler `18 02 FF 00` (nur 7E1) – am C350 bestätigt, Testschalter |
| [ADR-005](docs/adr-005-bluetooth.md) | Transport über Bluetooth (SPP) – keine neuen Befehle |
| [ADR-006](docs/adr-006-autostart.md) | Automatisch verbinden (App-Start, Radiostart) – keine neuen Befehle |
| [ADR-007](docs/adr-007-kennungssuche-motor.md) | verworfen (21.11.2026): das Motorsteuergerät antwortet unter 7E0 nicht – keine Freigabe |

Gesperrt bleiben Fehlerlöschen, Sitzungswechsel, Security Access, Stellglieder, Routinen, Schreiben und Flashen.

## Hintergrund zum Fahrzeug

- [C350-Log 07.11.](docs/c350-log-20261107.md) und [19.11.2026](docs/c350-log-20261119.md): Bank 1 wird beständig
  ~7 % stärker korrigiert als Bank 2.
- [M272-Forenauswertung](docs/m272-forum-auswertung.md) mit Mercedes-Bulletin S-B-03.30/08i (Ausgleichswelle betrifft
  die Baureihe 204 nicht; Versteller-Magnete abhängig von der Motornummer).
- [M272-Schulungsunterlage](docs/m272-referenz.md) (Kernaussagen) und [mercedes-hacking-docs](docs/mercedes-hacking-docs.md).

## Entwicklung

- Android: minSdk 26 (Android 8.0), compileSdk 37.2, targetSdk 37; AGP 9.5.1, Gradle 9.9.0, Java 17.
  Zielgerät OnePlus 13R / Android 16.
- Ohne Fahrzeug testen: ELM327-Simulator als Docker-Dienst, siehe [tools/elm327-emulator](tools/elm327-emulator/README.md).
- Oberfläche ohne Handy prüfen: Android-Emulator (Telefon und Radio 1024×600), siehe
  [tools/android-emulator](tools/android-emulator/README.md).
- Bauen ohne Android Studio: `tools/android-sdk/gradle.sh`. Mit installiertem SDK:

```
./gradlew :obdcore:testDebugUnitTest :app:testDebugUnitTest :dashboard:testDebugUnitTest \
          :obdcore:lintDebug :app:lintDebug :dashboard:lintDebug \
          :app:assembleDebug :dashboard:assembleDebug
```

- GitHub Actions führt genau diesen Umfang aus und lädt bei jedem Push auf `main` die beiden Debug-APKs hoch
  (`Mercedes_OBD2_Monitor_v<Version>-debug`, `OBD_Dashboard_v<Version>-debug`, Version aus `build.gradle`).

| Modul | Inhalt |
|---|---|
| `:obdcore` | ELM327-Client, Freigabeliste, Parser, Diagnoseabläufe (`DiagnosticJobs`), Analysen, CSV-Log, Vordergrunddienst – mit allen Unit-Tests |
| `:app` | Mercedes OBD2 Monitor |
| `:dashboard` | OBD Dashboard (nach dem Vorbild des OpenNextion-ELM327-Displays) |
