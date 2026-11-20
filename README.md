# ELM327 – Mercedes OBD2 Monitor

Android-App zur Überprüfung und Überwachung der OBD-II-Schnittstelle zwischen einem Mercedes C350 T-Modell S204/M272 (Baujahr 2008) und einem ELM327-WiFi-Dongle.

## OBD Dashboard (Modul `:dashboard`)

Zweite App im selben Repository: die Oberfläche des **OpenNextion-ELM327-Displays** (Reiter LIVE / GRAPH / DTC / INFO) als Android-App, gedacht für die Halterung im Auto.

- **LIVE:** Rundinstrumente für Drehzahl, Kühlmittel und Tempo mit Warn-/Alarmbereich (zusätzlich als Text), Kacheln für STFT/LTFT Bank 1/2 und Steuergerätespannung, KOEO/KOER.
- **GRAPH:** Verlauf von Drehzahl, Tempo, Kühlmittel, Last und den vier Kraftstoffkorrekturen (240 Werte); ungültige Messungen erscheinen als Lücke.
- **DTC:** 03/07/0A nur auf Knopfdruck, Ergebnis auf der Seite DETAILS; HU/AU-Vorcheck.
- **Unterseiten:** alle Werte, Mode 06 (Aussetzer und Monitortests), Fuel-Trim-Test und Warmlauf-Check.
- Quer- und Hochformat, Bildschirm bleibt während einer Sitzung an, Paket `de.roman.obddashboard` (parallel zur Monitor-App installierbar).

Zum Testen ohne Fahrzeug: ELM327-Emulator als Docker-Dienst im Heimnetz, das Handy verbindet sich per WLAN, siehe [tools/elm327-emulator](tools/elm327-emulator/README.md). Bauen ohne Android Studio mit `tools/android-sdk/gradle.sh`.

Beide Apps nutzen denselben geprüften Kern **`:obdcore`** (ELM327-Verbindung, Read-only-Allowlist, Parser, Diagnosen, CSV, Vordergrunddienst). Nicht frisch gelesene Werte (NO DATA, Parser-/Linkfehler, Verbindungsverlust) zeigen beide Apps als „–“ statt des letzten Werts.

## Version 1.9.0 (Monitor) / 0.13.0 (Dashboard): Trim-Anteile gemeinsam / je Bank

- **Trim-Karte** (Analyse in beiden Apps) zerlegt die Korrektur in den Anteil, den beide Bänke gemeinsam haben
  (Ursache vor der Bankaufteilung), und den Zusatz einer Bank. Der gemeinsame Anteil wird eingeordnet: fällt er
  mit der Last → Falschluft hinter dem Luftmassenmesser (beim M272 z. B. das Y-Stück der Kurbelgehäuseentlüftung,
  siehe [Forenauswertung](docs/m272-forum-auswertung.md)); lastunabhängig → eher LMM-Kennlinie oder
  Kraftstoffdruck; steigt er mit der Last → Kraftstoffversorgung/LMM.
- Am C350 (Log 07.11.2026): gemeinsam +2,5 % Leerlauf / +3,8 % Last (klein, lastunabhängig), Bank 1 zusätzlich
  +6,7 % / +7,7 %. Ein gerissener Entlüftungsschlauch erklärt dieses Bild nicht; der Unterschied liegt bei Bank 1.

## Version 1.8.1 (Monitor) / 0.12.1 (Dashboard): M272-Hinweise nach Servicebulletin

- Auswertung des MBClub-Threads „The Complete M272 Engine Thread“ (7 Seiten) und des Mercedes-Bulletins
  S-B-03.30/08i: [docs/m272-forum-auswertung.md](docs/m272-forum-auswertung.md). Kernpunkt: die
  Ausgleichswelle betrifft die Baureihe 204 laut Bulletin nicht; Versteller-Magnete (bis Motornummer
  2729..30 759427) und Öl am Magnetstecker (bis 895140) schon.
- DTC-Hinweise: Löschtest bei P0016–P0019, Motornummer-Grenzen bei P0010–P0024, Y-Stück der
  Kurbelgehäuseentlüftung unter dem Luftmassenmesser bei „mager“.

## Version 1.8.0 (Monitor) / 0.12.0 (Dashboard): Kennung und Getriebe-Fehler (Test), schnelleres Fahrtenbuch

- **Kennung + Getriebe-Fehler** ([ADR-004](docs/adr-004-kwp-kennung-fehlerspeicher.md), Testfreigabe, ausgeliefert
  **aus**): nach dem Einschalten (Monitor-App: Haken unter den C350-Knöpfen, Dashboard: INFO) liest der Knopf die
  Daimler-Kennung `1A 86` von Motor (7E0) und Getriebe (7E1) – Teilenummer, HW/SW-Stand, Herstelldatum – und den
  Getriebe-Fehlerspeicher `18 02 FF 00`. Exakte Befehle, nicht im Terminal, Header immer zurück auf 7DF; Absagen
  mit Code. Formate nach der Referenzbibliothek ecu_diagnostics, am C350 ungeprüft: der erste Lauf ist der Test.
- **Fahrtenbuch** liest das wachsende Log nur noch ab der gemerkten Stelle (Beginn der offenen Fahrt) statt bei jedem
  Öffnen ganz; das Ergebnis ist dasselbe (Test mit dem Log vom 19.11. in mehreren Stücken).
- **ATF-Abfrage** nennt bei einer Absage den Code (z. B. „7F 21 12: Unterfunktion/Format nicht unterstützt“).
- **UDS `22`** (ReadDataByIdentifier) bleibt gesperrt: der C350 spricht KWP2000, das Gegenstück ist `21` (Test).
- **M272-Referenz** ([docs/m272-referenz.md](docs/m272-referenz.md)): Kernaussagen der Schulungsunterlage 2004;
  daraus zwei Hinweise – gleicher Raildruck für beide Bänke (Bankvergleich) und LIN-geregelte Lichtmaschine
  (Starttest).

## Version 1.7.1 (Monitor) / 0.11.1 (Dashboard): ATF-Temperatur aus dem richtigen Byte

- **Korrektur** ([ADR-003](docs/adr-003-atf-antwort-layout.md)): Die ATF-Temperatur steht im Datenbyte 11 **nach**
  `61 30` (Index 13 ab 0x61), nicht an Index 11 ab 0x61. So zählen Torque („L-50“), die OBDScanner-Datenbank
  (`bix 88` nach dem Echo) und das EGS52-Layout (Bit 104). Index 11 ist dort der erkannte Gang – die App hätte am
  C350 vermutlich den Gang als Temperatur gezeigt. Am Fahrzeug war `21 30` noch nie gelesen.
- **Weitere Felder** derselben Antwort erscheinen als Rohwerte an ihren EGS52-Positionen (Wandler, Wählhebel, Gang,
  Ist-/Zielgang, Momente, Abtriebsdrehzahl), klar als ungeprüfte Hypothese markiert. Kein neuer Befehl.
- **Prüfung am C350:** warm im Stand in P, R, N und D „ATF-Temperatur“ lesen, dazu einmal kalt. Passen Wählhebel
  und Gang, gilt das Layout.

## Version 1.7.0 (Monitor) / 0.11.0 (Dashboard): Starttest und Bus mitlesen

- **Starttest** (Monitor-App: Knopf „Starttest“, Dashboard: TESTS): Ruhespannung, tiefster Wert und Dauer beim
  Anlassen, danach Ladespannung – nur aus `ATRV`. Ein zweiter Druck bricht ab. Werte ab 3 V gelten beim Anlassen
  als Messung (gerade ein sehr tiefer Einbruch ist der Befund); bleibt die Spannung nach dem Start unter dem Wert
  davor, endet das Anlassen nach 1,5 s und der Bericht bewertet die Ladung statt „sehr lang angelassen“.
- **Bus mitlesen** nach [ADR-002](docs/adr-002-can-mitlesen.md): 5 s passiv `ATMA`. Beendet ein Adapter das Mitlesen
  nicht sauber (der ELM327-Simulator tut das), bleiben die gelesenen Frames im Bericht und nur die Verbindung wird
  neu aufgebaut; eine vom Datenlimit abgeschnittene Zeile zählt nicht als Frame.
- **Fahrtenbuch:** gespeichert werden Teilstücke je Log, verbunden wird nur an einem offenen Ende (Log endete bei
  laufendem Motor). 60 s Motor aus beim Tanken bleiben zwei Fahrten.

## Version 1.4.0 (Monitor) / 0.8.0 (Dashboard): Fahrtenbuch

- **Fahrtenbuch** (Dashboard → ALLE WERTE → FAHRTEN, Monitor-App → Abschnitt „Fahrtenbuch“): je Fahrt Motorlaufzeit,
  Strecke, Ø-Verbrauch, Leerlaufanteil, Höchsttempo und Trim-Differenz B1−B2, dazu die Summe der letzten 30 Tage;
  als CSV teilbar. Quelle sind die CSV-Logs, die der Dienst durchgehend schreibt (auch ohne sichtbare Oberfläche),
  sowie importierte Logs. Eine Fahrt beginnt mit laufendem Motor und endet nach 30 s Motor aus, 2 min ohne Werte oder
  bei einem Neustart; unter 1 min Motorlaufzeit wird verworfen. Teilstücke eines Logwechsels werden verbunden.
- **Rechnung** (`TripComputer`): Strecke aus dem Tempo (Trapez), Kraftstoff aus Luftmasse und Soll-λ (wie die Analyse,
  Schub = 0), nur über Abstände bis 15 s – bei 5 s gingen im normalen Abfragetakt bis zur Hälfte der Strecke verloren.
  Ohne Luftmasse (≥ 50 % der Laufzeit) bleibt der Verbrauch unbekannt statt 0.
- **Validiert** gegen eine unabhängige Nachrechnung am Log vom 19.11.2026: 4,72 km / 0,71 l (15,0 l/100 km) und
  4,56 km / 0,55 l (12,0 l/100 km); Laufzeit stimmt mit 01 1F überein. Der Verbrauch bleibt ein Richtwert
  (Dichte 0,745 kg/l, ohne Trim-Korrektur), kein Tankbeleg.
- Log-Import: Trim-Verlauf und Fahrtenbuch nutzen dieselbe Aufteilung in Fahrten.
- Hinweis: Logs speichern Ortszeit ohne Zeitzone. Wird ein Log nach einem Zeitzonenwechsel erneut eingelesen, kann
  eine Fahrt doppelt erscheinen.

## Version 1.3.1 (Monitor) / 0.7.2 (Dashboard): Bankvergleich in Mode 06

- **Monitortests (Mode 06)** enthalten einen **Bankvergleich**: Jeder Test, den beide Bänke mit Grenzwert liefern
  (Lambdasonden 1–4, Sondenheizungen 1–4, Katalysator), steht mit seiner Reserve zur Grenze nebeneinander; ab
  25 Punkten Unterschied wird die schwächere Bank markiert. Liegt die Vorkat-Sonde von Bank 1 näher an ihren Grenzen,
  verweist der Bericht auf den Trim-Befund (Sonde B1S1, Abgasleck vor der Sonde, Krümmer/Flansch Zyl. 1–3).
- Am C350 einmal **warm** (nach einer Fahrt, Fahrzyklus abgeschlossen) „Monitortests“ lesen. Der ELM327-Simulator
  liefert Mode 06 nur für Bank 1, dort erscheint kein Vergleich; abgedeckt durch `Mode06BankComparisonTest`.

## Version 1.3.0 (Monitor) / 0.7.1 (Dashboard): Analyse auch in der Monitor-App

- **Monitor-App:** neuer Abschnitt **Analyse (C350/M272)** unter den Livewerten – derselbe Bericht wie im Dashboard
  (Verbrauch, Liefergrad, Kraftstoffsystem, Lambda-Regelung, Trim-Karte, **Trim-Verlauf**, Kat-Tendenz, Zündwinkel,
  E-Gas, Bordnetz), dazu **Log importieren (Trim-Verlauf)**. Jede App speichert ihren eigenen Verlauf.
- **Gemeinsamer Kern:** `LiveAnalysis` (Werte, Trim-Karte, Kat-Tendenz, Bericht), `TrimRecorder` (gespeicherter
  Verlauf, Import) und `AnalysisController` (Verbindungswechsel, Sichern auch beim Trennen, gedrosselte
  Aktualisierung, Import ohne Bezug auf eine Activity). Vorher lag das nur in der Dashboard-Activity.
- Markierter Text im Bericht bleibt markiert (keine Aktualisierung während einer Auswahl), damit er sich kopieren lässt.

## Dashboard 0.7.0: Querformat für die Halterung, Zurück-Geste, Lesbarkeit

Alle Seiten im Emulator (Android 16) am ELM327-Simulator geprüft (Design-Kritik und Barrierefreiheits-Prüfung):

- **LIVE quer:** Drehzahl über die volle Höhe, Kühlmittel/Tempo daneben, Kacheln als Raster 2×3 mit ALLE WERTE.
  Vorher bekamen die Instrumente nur die Resthöhe (~110 dp); in einem ersten 3-Zeilen-Raster wurde die Wertzeile
  abgeschnitten, das Komma sah dort wie ein Punkt aus.
- **Unterseiten** (Werte, Analyse, Mode 06, Trim-Test, HU/AU, DTC-Details) nutzen einen gemeinsamen Aufbau: quer
  Knöpfe als Spalte rechts, statische Hinweise scrollen mit, Live-Status (Trim-Test) bleibt fest. ALLE WERTE zeigt
  quer 4 statt 2,5 Zeilen.
- **INFO quer:** Einstellungen links (scrollbar), Protokoll rechts – vorher waren die Log-Knöpfe abgeschnitten.
- **Zurück-Geste** auf einer Unterseite führt zur übergeordneten Seite statt aus der App (Android 13+ über
  `OnBackInvokedCallback`, darunter über die Zurück-Taste); Ziel wie der ZURÜCK-Knopf.
- **Instrumente:** Mindestschrift 12 sp statt 12 px (≈ 4,6 sp), Titel wird in kleine Instrumente eingepasst.
- **Graph:** Einheit über der Skala statt über der „0“.
- Textkontraste geprüft: alle Textfarben ≥ 6,6:1 auf dem Hintergrund (gedämpftes Grau 9:1).

## Dashboard 0.6.1: Analyse-Seite für die Halterung

Im Emulator (Android 16, Pixel 6) am ELM327-Simulator geprüft:

- **Querformat:** Die Knöpfe stehen als Spalte rechts, der Hinweis „Richtwerte“ scrollt mit. Statt etwa 3 sind
  jetzt etwa 8 Zeilen der Analyse sichtbar.
- **Hochformat:** Knöpfe werden zweizeilig statt abgeschnitten („FAHRZEUGPR…“ → „PROFIL LESEN“, „LOG IMPORTIEREN“).
- **Trim-Verlauf:** zwei Zeilen je Verbindung, die Bankdifferenz steht vorn, importierte Fahrten sind mit „(Log)“
  markiert, ältere werden gezählt („… 2 ältere“).

## Dashboard 0.6.0: Logs in den Trim-Verlauf importieren

- **LOG IMPORTIEREN** (Dashboard → ANALYSE): ein CSV der Monitor-App über die Dateiauswahl des Systems öffnen (nur
  Lesen). Jede Fahrt im Log (neue Fahrt nach mindestens 5 min Pause) läuft wie live durch die Trim-Karte und landet
  als Eintrag im Trim-Verlauf (bis 30 Einträge). Ein erneuter Import desselben Logs ersetzt seine Einträge;
  überschneidet sich eine Fahrt zeitlich mit einer schon gespeicherten Verbindung, bleibt diese (live gilt mehr als
  nachgetragen). Die Rückmeldung nennt übernommene, schon vorhandene und zu alte Fahrten.
- Live-Einträge werden nur bei exakt gleichem Verbindungsstart aktualisiert; ein schnelles Neuverbinden überschreibt
  keinen fremden Eintrag. Gespeichert wird per Lesen-Ändern-Schreiben, damit Import und Live-Aufzeichnung sich nicht
  gegenseitig verlieren. Zeitstempel in der doppelten Stunde der Zeitumstellung bleiben eine Fahrt.
- Die Logs vom 07.11. und 19.11.2026 ergeben vier Fahrten: Bankdifferenz +6,4 / +8,0 / +7,3 / +7,7 % → „Bank 1 in
  allen 4 Verbindungen stärker angefettet – beständig; Unterschied stabil“.
- Dashboard und Import nehmen Proben über dieselbe Regel (`EngineAnalysis.TrimMap.offer`); Kern `LogReplay`, Tests
  `LogReplayTest`.

## Version 1.2.2 (Monitor) / 0.5.0 (Dashboard): Trim-Verlauf und zweites C350-Log

Auswertung des zweiten Logs (19.11.2026), Details in [docs/c350-log-20261119.md](docs/c350-log-20261119.md).

- **Absenderwahl bestätigt:** 1 899 Doppelantworten Motor/Getriebe, in 763 kam das Getriebe zuerst – geloggt wurde
  immer der Motorwert (7E8).
- **Trim-Verlauf** (Dashboard → ANALYSE): je Verbindung ein gespeicherter Eintrag der Trim-Karte (Leerlauf/Teillast/
  hohe Last, Bankdifferenz); zeigt, ob ein Bankunterschied beständig ist und ob er wächst. Am C350 lag Bank 1 am
  07.11. und 19.11. gleich weit (5,7–8,2 %) über Bank 2.
- **Log wiedereinlesen:** Das CSV trennt mehrere Antworten mit Leerzeichen; der Parser trennt solche Header-Zeilen
  jetzt ebenfalls und wertet auch dann 7E8.
- Regressionstest `C350Log20261119Test` mit 5 587 echten Rohantworten.

## Version 1.2.0 (Monitor) / 0.4.0 (Dashboard): Werte vom Motorsteuergerät

Am C350 beantworten Motor (7E8) und Getriebe (7E9) dieselben PIDs (01 0C/0D/04/05/42/49). Ohne Header galt die
zuerst eintreffende Antwort; je Abfrage lagen die Werte bis 194 U/min bzw. 1,1 V auseinander.

- Auf 11-bit-CAN läuft das Live-Polling jetzt mit CAN-Header (`ATH1`); der Parser wertet die Antwort von 7E8 und
  nimmt ein anderes Steuergerät nur, wenn der Motor nicht antwortet. Single-Frame-PCI begrenzt die Nutzdaten.
- Diagnosen (DTC, Mode 06, HU/AU-Check, Freeze Frame, Fahrzeugprofil, ATF) laufen weiter mit `ATH0` und stellen
  danach den Live-Zustand wieder her; Reconnect und Terminal (`ATZ`, `ATH0`) ebenso.
- Andere Protokolle (29-bit-CAN, K-Line) bleiben bei `ATH0`.
- Das CSV-Log enthält auf 11-bit-CAN die Rohantworten mit Absender (z. B. `7E804410C0EE7`).
- **Am Fahrzeug:** Livewerte am 19.11.2026 bestätigt; DTC-Scan und HU/AU-Check mit `ATH1`-Wechsel noch einmal am C350 laufen lassen.

## Version 1.1.2 (Monitor) / 0.3.1 (Dashboard): Review-Korrekturen

- Schub: ein gültiges Soll-Lambda hat Vorrang vor der Drosselklappen-Regel; `FFFF` zählt nur bei Status 4 (oder unbekanntem Status) als Schub
- Trim-Karte: bankspezifisches Muster nur mit Leerlauf- und Lastdaten; Korrektur über +10 % wird auch ohne Leerlaufabfall gemeldet;
  Stand mit erhöhter Drehzahl zählt nicht als Last; Proben am Schub-Übergang werden übersprungen
- Lambda-Regelgüte: `FFFF` (Messbereichsende) ergibt keine Abweichung mehr

## Version 1.1.1 (Monitor) / 0.3.0 (Dashboard): Erkenntnisse aus dem ersten C350-Log

Auswertung der ersten zwei Fahrten am C350 (07.11.2026), Details in [docs/c350-log-20261107.md](docs/c350-log-20261107.md).

- **Schub richtig erkannt:** Soll-Lambda `FFFF` (λ 1,99997) zeigt am ME9.7 die Schubabschaltung an; die geschlossene
  Drosselklappe meldet 0,8–1,6 % statt < 1 %. Verbrauch im Schub ist jetzt 0 l/h, die Anzeige lautet „FFFF (Schub/Messbereichsende)“
  bzw. bei Breitband-λ „> 2 λ (Schub/Messbereich)“ statt „2,000 λ“.
- **Trim-Karte** (Dashboard → ANALYSE): Gesamtkorrektur STFT+LTFT je Bank getrennt nach Leerlauf, Teillast und hoher Last,
  nur geregelt und warm. Unterscheidet ein bankspezifisches Muster von Falschluft (Leerlauf hoch, fällt mit Last).
  Am C350 zeigt sie Bank 1 lastunabhängig etwa 6–8 % fetter korrigiert als Bank 2 (Richtwert, keine Diagnose).
- **Regressionstest mit echten Rohantworten** (`C350LogTest`, Auszug des Logs als Testressource).
- Antworten zweier Steuergeräte auf dieselbe PID (Motor + Getriebe): gelöst in 1.2.0.

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
  Formel Datenbyte 11 nach `61 30` − 50 ([ADR-003](docs/adr-003-atf-antwort-layout.md), bis 1.7.0 zwei Bytes zu früh gelesen) ist am C350 noch nicht bestätigt; die Rohantwort wird immer angezeigt.
- **Steuergerätenamen** (09 0A) im HU/AU-Check.
- **Starttest** (Knopf in beiden Apps, Dashboard: TESTS): Batterie und Anlassen aus der Adapterspannung (nur `ATRV`,
  OBD-Stecker Pin 16). Ruhespannung (Zündung an, Motor aus), tiefster Wert und Dauer beim Anlassen mit
  temperaturabhängigem Richtwert (BCI-Tabelle, Außentemperatur aus 01 46), danach Ladespannung. Reißt die
  WLAN-Verbindung beim Anlassen ab, wird das als Hinweis auf einen starken Einbruch berichtet. Richtwerte, keine
  Herstellerangaben.
- **Bus mitlesen** (Knopf in beiden Apps, Dashboard: ANALYSE) nach [ADR-002](docs/adr-002-can-mitlesen.md): 5 s passiv
  `ATMA`, sendet nichts an das Fahrzeug. Zeigt, ob der OBD-Port Bus-Botschaften durchreicht, und für bekannte
  W204-Botschaften (Motoröl, Ölstand, ATF, Gang) die Rohwerte. Grundlage und Bewertung:
  [docs/mercedes-hacking-docs.md](docs/mercedes-hacking-docs.md).

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
- Mitlesen (ATMA) nur zeitbegrenzt über „Bus mitlesen“; ATMR/ATMT/ATCSM0 und ATCAF0/1 sind im Terminal gesperrt (ADR-002)
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

- `Mercedes_OBD2_Monitor_v1.4.0-debug` (Modul `:app`)
- `OBD_Dashboard_v0.8.0-debug` (Modul `:dashboard`)

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
