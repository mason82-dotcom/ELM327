# Änderungen

Neueste Version zuerst. Monitor = Modul `:app`, Dashboard = Modul `:dashboard`.

## Version 1.16.2 (Monitor) / 0.24.2 (Dashboard): Gang und ATF im 2-s-Takt

- **Getriebe live (ADR-008):** Gang und ATF kamen am C350 nur alle 3,8 s (Median), weil die Abfrage erst nach einer
  ganzen Runde der übrigen Werte lief. Jetzt wird sie zwischen die einzelnen Abfragen eingeschoben, sobald sie fällig
  ist: im Emulator alle 2,03 s (95 % unter 2,07 s). Ein Gangwechsel erscheint damit in der Regel nach etwa 2 s
  (länger, wenn gerade eine andere Abfrage auf eine Antwort wartet). Nicht als erste Anfrage nach einem Neuaufbau;
  eine Zeitüberschreitung des Getriebes löst keinen Neuaufbau mehr aus. `uitest.sh` prüft den Takt.

## Version 1.16.1 (Monitor) / 0.24.1 (Dashboard): Bus mitlesen ohne unnötigen Neuaufbau

- **Fehler aus 1.16.0 / 0.24.0 behoben:** Kennt der Adapter das Zurücksetzen des Empfangsfilters nicht (am C350
  am 22.11.: Antwort auf `ATCRA` ohne Adresse fehlte), setzt die App zu Recht keinen Filter – verlangte am Ende aber
  trotzdem dessen Bestätigung und baute danach jedes Mal die Verbindung neu auf, mit der falschen Meldung „keine
  gültige Antwort auf 01 00“. Jetzt wird das Zurücksetzen nur nach einem gesetzten Filter verlangt, und der Hinweis
  nennt den Schritt, der nicht bestätigt wurde.

## Version 1.16.0 (Monitor) / 0.24.0 (Dashboard): Bus mitlesen mit Filter je Botschaft

- **Bus mitlesen (ADR-002, Option C):** Am 21.11. lief der WLAN-Adapter ungefiltert nach 0,2 s über (BUFFER FULL,
  nur 0x105). Jetzt nach 1 s Überblick je bekannter W204-Botschaft (0x30D Öltemperatur/Ölstand, 0x105, 0x2F1 ATF/
  Wählhebel, 0x0F3 Gang, 0x19F Kombi) 2 s mit Empfangsfilter: der Bericht sagt je Kennung „empfangen“ mit Rohwerten
  oder „nicht empfangen“, und ob der Filter wirkt. Freigegeben sind genau `ATCRA30D`, `ATCRA105`, `ATCRA2F1`,
  `ATCRA0F3`, `ATCRA19F` und `ATCRA` (zurücksetzen), nie im Terminal. Ein Filter wird nur gesetzt, wenn der Adapter
  das Zurücksetzen vorher bestätigt; danach Rücksetzen und Prüfanfrage 01 00 im normalen Format (bei Zündung aus
  genügen die Bestätigungen) – sonst Neuaufbau, der den Filter immer zurücksetzt. Abbrüche behalten die bisherigen
  Ergebnisse. Anzeige „Lese Bus (etwa 12 s)“.

## Version 1.15.0 (Monitor) / 0.23.0 (Dashboard): Getriebe-Fehlercodes im Klartext

- **Getriebe-Fehlerspeicher (ADR-004, `18 02 FF 00`):** jeder Code der 7G-Tronic 722.9 mit Bauteil und Teilfehler
  (z. B. „2759 – Magnetventil Wandlerüberbrückung (Y3/8y8): elektrische Prüfung fehlgeschlagen“), alle 129 Kennungen
  der WIS-Tabelle „ETC2 (722.9) ab 2008“ als eigene Kurzfassung. Angezeigt werden immer die gesendeten 2 Bytes;
  eine 5-stellige E-Kennung mit denselben letzten Stellen erscheint nur als „möglich, nicht belegt“, ohne Rat.
  Statusbyte nach ISO 14230 gelesen (aktuell vorhanden / nur gespeichert, Warnlampe): Hinweise „Was jetzt“ je Gruppe
  nur für aktuelle Fehler, nur gespeicherte ohne akuten Rat. Codes ohne Eintrag mit ISO-Lesart zum Nachschlagen.
  Keine neuen Befehle.

## Version 1.14.2 (Monitor) / 0.22.0 (Dashboard): Gang und Getriebeöl live

- **Gang und ATF live im Dashboard (ADR-008):** Gang groß im Drehzahlmesser („D 3“, beim Schalten „D 3→2“, „P“/„R“/„N“),
  ATF-Temperatur als Kachel (im Querformat statt „ALLE WERTE“, der Knopf steht jetzt in der Fußzeile). Gelesen alle
  2 s mit derselben Sequenz wie „ATF lesen“ (`ATSH7E1` → `21 30` → `ATSH7DF`), im Thread der Live-Abfragen; Header
  immer zurück, sonst wird die Verbindung verworfen. Nach drei ungültigen Antworten oder einem gescheiterten
  Rückweg für die Verbindung aus, die Kachel nennt den Grund. Werte im CSV-Log, Alterslimit wie alle Live-Werte.
- **Warnung Getriebeöl:** gelb ab 120 °C, rot ab 130 °C (5 s Haltezeit, 4 K Hysterese) – Richtwerte; das WIS nennt
  nur den Arbeitsbereich bis 110 °C und Momentenrücknahme bei Übertemperatur.
- **Parser:** mehrteilige CAN-Antworten mit Header (ATH1) werden zusammengesetzt; vorher nur Einzelrahmen, die
  Getriebeantwort im Live-Betrieb wäre nicht lesbar gewesen (Unit-Test mit der Antwort vom 21.11.).
- **UI-Tests:** Dashboard prüft Gang/ATF mit Warnung (`proxy.py atf`) und die Selbstabschaltung am Simulator ohne
  Getriebe. Die Marke für „kein Mode 03 beim Verbinden“ ist jetzt `AT@1` – `ATDPN` sendet die App beim Verbindungs-
  aufbau selbst, die Prüfung aus #58 war damit ohne Aussage. Neu im Terminal-Test: `31 01 FF 00` und `27 01`; `ATSH7E1`
  bleibt im Terminal gesperrt, ist aber von der Protokollprüfung ausgenommen (vom Dashboard jetzt legitim gesendet).

## Version 1.14.1 (Monitor) / 0.21.1 (Dashboard): Starttest mit Spannungsabgleich

- **Abgleich Adapter ↔ Motorsteuergerät:** Am 21.11. maß der WLAN-Adapter (`ATRV`) in Ruhe 10,6 V, das Steuergerät
  (01 42) 11,5 V; nach dem Start 11,1 V gegen 14,4 V. Der Starttest liest jetzt 01 42 vor der Messung und nach der
  Ladungsmessung und zeigt beide Werte. Weicht der Adapter ab (Ruhe > 0,5 V, Ladung > 1,0 V; Steuergerät + 0,2 V als
  Batteriewert angenommen), werden Ruhe- und Ladespannung nach dem Steuergerät bewertet und der Einbruch beim
  Anlassen um die Ruhe-Abweichung verschoben geschätzt (grob). Ohne Ruhe-Abgleich sagt das Ergebnis ausdrücklich,
  dass der Einbruch nicht bewertbar ist.

## Version 1.14.0 (Monitor) / 0.21.0 (Dashboard): Ergebnisse des Fahrzeugtests vom 21.11.2026

- **ADR-003 angenommen:** Die Antwort `61 30` folgt der eingelegten Stellung – Wählhebel [9] P = 10, R = 9, N = 8,
  D = 7; Gang [12] je Halbbyte (P = 13, R = 11, N = 0, Gänge 1–7); ATF [13] 8 °C kalt bei 5 °C außen, 62 °C warm. Der
  ATF-Bericht zeigt jetzt „Wählhebel D · Gang 3 → 2“ im Klartext; Motor- und Wandlermoment vorzeichenrichtig.
- **ADR-004:** Getriebe-Teil angenommen (A 003 446 03 10, Software KW 19/2008, Fehlerspeicher leer). Motor-Teil
  verworfen – das ME9.7 antwortet unter 7E0 nicht; `ATSH7E0` ist aus der Freigabe entfernt.
- **ADR-007 verworfen und entfernt:** Kennungssuche `21 01`–`21 FE` (Motor schweigt, kein Öltemperatursensor).
  Freigabe, Suchmodus, Knopf und Prüfplanschritt entfallen; alte Prüfplan-Einträge bleiben lesbar.
- **Mode 06:** Sondenschwellen (Kennwert mit Grenze = Wert, Nachkat-Sonde TID 81/82) gelten nicht mehr als „knapp“ –
  am C350 waren das 8 Fehlalarme samt Hinweis „gealterte Sonde“.
- **Prüfplan:** die beiden ATF-Schritte sind erledigt (ADR-003) und entfallen; die Checkliste hat noch 6 Schritte.
  Alte ATF- und Kennungssuche-Einträge bleiben im Bericht lesbar. Die Datei der Kennungssuche wird beim Start gelöscht.
- **Starttest:** Ladespannung über 20 s gemessen, bewertet werden die letzten 5 s (das ME fährt die Lichtmaschine
  verzögert hoch). Bei tief entladener Batterie und zwischenzeitlich mindestens 1 s lang normaler Ladespannung heißt es nicht mehr
  „Lichtmaschine lädt nicht“, sondern: nach einigen Minuten bei 2000 U/min nachmessen.

## Version 1.13.3 (Monitor) / 0.20.3 (Dashboard): Wählhebel/Gang-Prüfung richtig benannt

- Der Prüfplanschritt hieß „ATF-Temperatur warm in P, R, N, D“, prüft aber das **Layout der Antwort** `61 30`:
  folgen Wählhebel [9] und Gang [11] der eingelegten Stellung (ADR-003)? Die Temperatur hängt nicht von der Stellung
  ab. Neuer Name „Wählhebel/Gang prüfen in P, R, N, D (ATF-Antwort)“.
- Zählung: jede Antwort mit Wählhebelfeld bei laufendem Motor, je Stellung einmal – unabhängig davon, ob die
  Temperatur plausibel ist. „Warm“ ist keine Voraussetzung mehr, nur empfohlen (Vergleich mit der Kaltlesung).
- README, ADR-003 und Prüfplan-PDF entsprechend.

## Version 1.13.2 (Monitor) / 0.20.2 (Dashboard): Prüfplan-Zählung nachgeschärft

Code-Review von 1.13.1 (7 Befunde) und Abgleich App/README/PDF.

- **P/R/N/D-Lesungen** zählen nur mit gültiger ATF-Temperatur (keine Absage, kein NO DATA), mit laufendem Motor bei
  bekannter Drehzahl ab 400 U/min (gleiche Grenze wie die Warnungen) und **je Wählhebelstellung einmal** (Rohwert [9]);
  viermal in P lesen erledigt den Schritt nicht mehr. Die Kaltlesung zählt ebenfalls nur mit gültigem Wert.
- **Frische Werte:** Der Prüfplan übernimmt Drehzahl und Kühlmittel nur, wenn sie höchstens 15 s alt sind – eine
  Drehzahl von kurz vor dem Abstellen meldet kein „Motor läuft“ mehr.
- „Bus mitlesen“ nutzt dieselbe Laufend-Grenze.
- README und Prüfplan-PDF enthalten jetzt auch „Kennungssuche Motor kalt und warm“ (PDF: 12 Schritte, Hinweis auf die
  Gemischadaption bei den Monitortests).

## Version 1.13.1 (Monitor) / 0.20.1 (Dashboard): Schalten nur bei laufendem Motor

- **Prüfplan „ATF warm in P, R, N, D“:** Der Wählhebel lässt sich nur bei laufendem Motor aus P bewegen. Der Schritt
  sagt das jetzt ausdrücklich (Motor im Leerlauf, Feststellbremse an, Fuß fest auf der Bremse), und Lesungen mit
  stehendem Motor zählen nicht mehr als P/R/N/D-Lesung. Gleicher Hinweis im ATF-Bericht, im README und in ADR-003.

## Version 1.13.0 (Monitor) / 0.20.0 (Dashboard): Fehlercodes mit ME9.7-Bedeutung

Vorschlag B aus der Handbuch-Auswertung.

- **ME9.7-Bedeutung je P-Code** (227 Codes): Unter jedem gelesenen Fehlercode steht, was das Motorsteuergerät damit
  meint – Seite (rechts = Bank 1, links = Bank 2), Bauteil mit Kennzeichen (z. B. G3/4, Y49/5, B70) und Teilfehler,
  z. B. „Gemischadaption rechts Teillast: Anreicherung über Grenze (lief mager)“. Mehrere mögliche Teilfehler stehen
  als „einer von“. Eigene deutsche Kurzfassung der ME9.7-Fehlertabelle; Abweichungen der Tabelle von der OBD-Norm
  (P0174/P0175, P0038, P0013) sind im Text vermerkt. Je Code nur einmal, auch wenn er in mehreren Modi steht.
- **Prüfreihenfolgen** für weitere Gruppen in den M272-Hinweisen: Sondenheizung, Sonden vor/nach Kat, vertauschte
  Sondenstecker, Einspritzventile, Zündspulen, Thermostat elektrisch, Kühlmittelsensor, Luftmassenmesser,
  Saugrohrdrucksensor, Drosselklappe, Nockenwellengeber und -magnete, Klopfsensoren, Tankentlüftung, Sekundärluft
  elektrisch, Versorgungsspannung, Lichtmaschine, Steuergerät; P0422 (Kat beim ME9.7) und P2279 (Falschluft).
- M272-Hinweise nur noch mit dem M272-Profil.
- Code-Review (medium): 10 Befunde behoben.

## Version 1.12.1 (Monitor) / 0.19.1 (Dashboard): Herstellerwerte und Fahrzyklus-Hilfe

Vorschläge E und D aus der Handbuch-Auswertung (WIS-Nummern im Code).

- **Starttest:** Hinweis „nahe der Startgrenze“ unter etwa 8 V (das ME startet unter ~7 V nicht mehr); bei niedriger
  Ruhespannung der Hinweis, dass auch der Werkstatt-Batterietest erst ab 12,4 V aussagekräftig ist; Ladung bis
  15,0 V gilt als normal (das ME regelt bis 15 V). Gleiche 15-V-Grenze jetzt in Analyse, Spannungsbewertung und
  Warnungen.
- **Warmlauf-Check:** Thermostat-Beschreibung nach WIS (regelt etwa 80–100 °C, ab ~110 °C Notlauf ganz offen);
  über 110 °C lautet das Ergebnis „Überhitzung“ mit Prüfhinweis.
- **HU/AU-Check:** neuer Abschnitt „Fahrzyklus-Hilfe (ME9.7)“ bei offenen Monitoren oder Fehlern – Fahrhinweis je
  Monitor (Kat, Sekundärluft nur nach Kaltstart, Tankentlüftung, Sonden), welche Prüfungen den Readiness-Code setzen,
  Lampenlogik (an nach 2, aus nach 3 Zyklen, gelöscht nach 40/80). Hinweis korrigiert: Batterie-Abklemmen löscht den
  Fehlerspeicher des ME9.7 nicht.
- Code-Review (medium): 10 Befunde behoben.

## Version 1.12.0 (Monitor) / 0.19.0 (Dashboard): Gemischadaption je Bank, Trims nach Kaltstarts

- **Gemischadaption je Bank** (Monitortests/Mode 06, beide Apps): Das ME9.7 meldet unter MID 81/82 die gelernte
  Gemischanpassung je Bank (TID 80 in %, TID 82 als Faktor; Referenz laut Werkstatthandbuch ±4,5 % bzw. 0,70–1,30).
  Neuer Abschnitt oben im Bericht: Bank 1 (rechts) und Bank 2 (links) mit Abstand zur Grenze (Grenzen des
  Steuergeräts, sonst Referenz), Differenz B1−B2 und **Verlauf** über alle Lesungen mit Trend. Warnung nahe der Grenze
  mit dem passenden Code (mager P0171/P0174, fett P0172/P0175). Gespeichert in `gemischadaption.txt`, je Fahrzeug
  (FIN aus 09 02); gleiche Werte und Lesungen binnen einer Stunde verdrängen keinen Verlauf. Nur Profil M272 und nur,
  wenn das Steuergerät MID 81/82 liefert.
- **Trims nach Kaltstarts** (Fahrtenbuch): Jede Fahrt merkt sich Warmlaufzeit (Kühlmittel ≥ 80 °C) und höchste
  Kühlmitteltemperatur. Kalte Kurzstrecken (unter 70 °C oder weniger als 5 min warm) und die folgenden kurzen Fahrten
  bis zu einer Fahrt mit 20 min warm sind gekennzeichnet („Kraftstoff im Öl möglich“, WIS GF07.61-P-2003V); ihre
  Trim-Differenz steht in Klammern und zählt nicht in den neuen Mittelwert „aus vergleichbaren Fahrten“ (30 Tage).
  CSV mit Spalten warm_min, max_kuehlmittel_c, trim_einordnung. Kühldaten in eigenem Schlüssel – ältere Versionen
  lesen die Fahrten unverändert.
- Code-Review (medium): 10 Befunde behoben.

## Dashboard 0.18.1 (Monitor 1.11.5): Befunde aus dem Werkstatthandbuch

Quelle: Offline-Werkstatthandbücher C 350 (204.056, Motor 272.961), nur privat ausgewertet; hier nur Zusammenfassungen
mit WIS-Dokumentnummer.

- **Warnungen:** „Ladung schwach“ erst unter 12,6 V (vorher 13,0 V). Das ME senkt die Ladespannung bei voller Batterie
  im Leerlauf absichtlich ab (GF07.61-P-3003CY); Obergrenze bis 15 V. Alarm unter 12,2 V und über 15,0 V unverändert.
- **Mode 06:** herstellerspezifische Tests des ME9.7 benannt (Referenztabelle des Handbuchs) – vor allem MID 81/82
  TID 80/82 = **Gemischadaption je Bank** (% mit Referenz ±4,5 %, Faktor 0,70–1,30), dazu Kat-Sauerstoffspeicher,
  Sondenheizungen, Sekundärluft. Der Bankvergleich nutzt die Namen.
- **Hinweise:** Bank 1 = rechte Zylinderbank (Sonden G3/4/G3/6), Bank 2 = links (G3/3/G3/5) – belegt über die
  ME9.7-Fehlertabelle (P0030 = G3/4, P0016 = rechts). P0171/P0174 mit dem Prüfablauf des Herstellers (Einspritzventile
  der Bank, Falschluft/Abgasleck vor der Sonde); „beide Bänke mager“ nennt zusätzlich den Sitz des Luftmassenmessers.
- **ADR-007 korrigiert:** Motor 272.961 bis MJ 08 hat keinen Öltemperatursensor (nur Ölstandsschalter S43); das ME
  rechnet die Öltemperatur aus einem Modell. Die Kennungssuche kann nur diesen Rechenwert finden.

## Dashboard 0.18.0 (Monitor 1.11.4): Kennungssuche Motoröltemperatur (ADR-007, Test)

- **Recherche:** Für die Motoröltemperatur des ME9.7 ist keine Kennung öffentlich belegt; der Standard-PID `01 5C`
  wird vom C350 nicht unterstützt (Logs: PIDs nur bis `01 4C`). (Korrektur in 0.18.1: kein Sensor, nur Rechenwert.)
- **Kennungssuche** ([ADR-007](docs/adr-007-kennungssuche-motor.md)) unter ANALYSE, nur mit dem Testschalter
  (jetzt „ADR-004/007“): `ATSH7E0` → `21 01`…`21 FE` → `ATSH7DF`, vorher `01 05`. Bericht mit allen positiven
  Antworten roh und Kandidaten im Fenster um die Kühlmitteltemperatur; ein zweiter Lauf mit mindestens 20 K Unterschied
  meldet **bestätigte** Kandidaten. Zweiter Druck stoppt; vorzeitiges Ende bei schweigendem Steuergerät oder
  abgelehntem Dienst, Teilergebnis bleibt. Neuer Prüfplanschritt „Kennungssuche Motor kalt und warm“.
- **Freigabe:** `21 01`–`21 FE` nur bei Header 7E0 und nur, solange der Client im Suchmodus ist; im Terminal und in
  allen anderen Abläufen gesperrt (Tests über alle 254 Kennungen und gesperrte Dienste).
- Im Simulator geprüft (Befehlsfolge, Header zurück, Livewerte laufen weiter); am C350 offen.
- Monitor 1.11.4: nur gemeinsamer Kern (Freigabe).

## Dashboard 0.17.0 (Monitor 1.11.3): Warnungen für Kühlmittel und Bordnetz

- **Warnleiste** über allen Seiten des Dashboards, unsichtbar solange alles in Ordnung ist:
  - Kühlmittel: Warnung ab 108 °C, Alarm ab 115 °C (dieselben Grenzen wie die Rundanzeige); Alarm fällt unter
    111 °C auf Warnung zurück, die Warnung endet unter 104 °C.
  - Motor läuft (ab 20 s nach dem Start): Ladung unter 13,0 V Warnung, unter 12,2 V Alarm, über 15,0 V Alarm.
  - Zündung an, Motor aus: Batterie unter 11,8 V Warnung (Log 20.11.: 11,5 V vor dem Start).
  - Jede Meldung muss über mindestens zwei Messungen anstehen; ungültige oder ältere Werte als 15 s nehmen sie weg.
- **Ton** nur bei Alarm (Alarm-Lautstärke, auch bei „Nicht stören“), beim Auftreten und jede Minute, bis „Ton aus“.
  Auswertung und Ton laufen in der Sitzung, also auch wenn die Navigation vorn ist; ein Radiostart mit
  „Automatisch verbinden“ schaltet sie ebenfalls ein. Jede neue Meldung steht in der Konsole.
- Kühlmittel und Steuergerät-Spannung werden doppelt so oft gelesen (jede 2. statt 5. Runde, im Fuel-Trim-Test jede
  3. statt 10.); im Log 20.11. lagen bis zu 23 s zwischen zwei Messungen.
- Keine neuen Befehle, weiterhin nur lesen. Monitor 1.11.3: nur gemeinsamer Kern (Abfragetakt).

## Version 1.11.2 (Monitor) / 0.16.1 (Dashboard): DTC-Abbruch, Sitzungswechsel, Bluetooth-Korrektur

- **DTC-Scan abbrechen** (#36): STOP in beiden Apps beendet genau den laufenden Scan; der Adapter wird neu
  verbunden, 03/07/0A werden nicht wiederholt, kein Dialog und kein Prüfplaneintrag.
- **Sitzungswechsel** (#35, #37): Ergebnisse, Status und Livewerte einer alten Verbindung landen nicht mehr in der
  neuen; Header-Wechsel (`ATSH`) gelten erst nach „OK“ des Adapters.
- **Behoben (Review von #35–#37)**:
  - Bluetooth: seit 1.11.0 brach jeder Verbindungsaufbau sofort ab, weil das Schließen vor dem Verbinden den
    Bluetooth-Öffner endgültig abbrach. Abgebrochen wird jetzt nur noch beim Trennen oder bei neuer Verbindung.
  - `ATZ` gilt auch bei Nachbauten, deren Kennung weder „ELM“ noch „OBD“ enthält (nur `?`/`ERROR`/Echo nicht).
  - Ein fertiges Diagnoseergebnis geht nicht mehr verloren, wenn gleich danach eine neue Diagnose startet oder die
    Verbindung wechselt, und STOP nach Abschluss verwirft es nicht mehr.
  - STOP während der Resynchronisierung zählt keinen Adapter-Timeout und trennt nicht doppelt.
  - Dashboard: die DTC-Karte folgt dem Arbeitszustand statt Statustexten und bleibt nicht auf „wird abgebrochen“.
  - Doppelter Zustell- und Cache-Code zusammengeführt.
- Offen: `ObdSession` hängt Android-seitig an `Handler`/`Context`; die Sitzungsregeln sind als reine Funktionen
  getestet (`SessionEpoch`, `DiagnosisToken`), die Verdrahtung nur im Emulator.

## Dashboard 0.16.0 (Monitor 1.11.1): Automatisch verbinden fürs Radio

- **Automatisch verbinden** ([ADR-006](docs/adr-006-autostart.md), INFO, ausgeliefert aus): beim Öffnen der App und
  nach dem Start des Radios. Nach dem Radiostart verbindet der Hintergrunddienst ohne geöffnete App (alle 20 s,
  höchstens 10 min), danach hält die Sitzung die Verbindung. „Trennen“ beendet es. Im Emulator per Neustart geprüft.
- Monitor 1.11.1: nur gemeinsamer Kern (Verbinden aus gespeicherten Einstellungen).

## Version 1.11.0 (Monitor) / 0.15.0 (Dashboard): Bluetooth-ELM327

- **Bluetooth** ([ADR-005](docs/adr-005-bluetooth.md)): „Adapter: … · ändern“ (Monitor-App unter Adresse/Port,
  Dashboard unter INFO) wählt WLAN oder einen gekoppelten Bluetooth-ELM327 (SPP). Gedacht für das Android-Radio im
  Auto, das so sein WLAN behält. Nur gekoppelte Geräte, Berechtigung `BLUETOOTH_CONNECT`.
- **Transportschicht** `ElmLink` (TCP/Bluetooth) und `TimedInputStream` (Lese-Timeout für Bluetooth); Tests über
  einen Transport ohne Socket-Timeout. Dabei behoben: bei vollem Puffer weckte der Lesefaden den Leser nicht (Test:
  12 s → 7 ms).
- Am echten Bluetooth-Adapter noch ungeprüft; WLAN im Emulator am Simulator unverändert.

## Version 1.10.2 (Monitor) / 0.14.2 (Dashboard): Diagnoseabläufe herausgelöst

- **`DiagnosticJobs`**: die Abläufe (DTC, Mode 06, HU/AU, Fahrzeugprofil, ATF, Kennung/Getriebe-Fehler, Bus
  mitlesen, Starttest) aus `ObdSession` herausgelöst (1 691 → 1 365 Zeilen); Zugriff auf die Sitzung nur über eine
  kleine `Host`-Schnittstelle. Verhalten unverändert.
- **Ablauftests** gegen einen ELM327-Nachbau: ATF sendet genau `ATSH7E1` → `2130` → `ATSH7DF`, ADR-004 genau seine
  sieben Befehle; Header am Ende 7DF; scheitert der Rückweg, wird die Verbindung verworfen; ohne 11-bit-CAN wird
  nichts gesendet; DTC liest 03/07/0A mit M272-Hinweisen und Probe.

## Version 1.10.1 (Monitor) / 0.14.1 (Dashboard): Wartung nach Tech-Debt-Audit

- **DTC-Scan** läuft über dieselbe Diagnoseroutine wie alle anderen (vorher eine eigene Kopie): ein I/O-Fehler
  ließ die Verbindung im Diagnosezustand stehen, jetzt wird sie wie überall neu aufgebaut; Timeouts zählen mit.
- **Elm327Client** hat erstmals Tests (7, gegen einen ELM327-Nachbau auf einem lokalen Socket): Prompt,
  Byte-Zähler, Sperre vor dem Senden, Header-Zustand nach Timeout, Resync, ATMA mit Stopp, ignoriertem Stopp und
  Datenlimit.
- Prüfplan-Hilfen beider Apps in `PlanUi` zusammengeführt.

## Version 1.10.0 (Monitor) / 0.14.0 (Dashboard): Prüfplan für den Termin am Auto

- **Prüfplan** (Dashboard: INFO → PRÜFPLAN, Monitor-App: Abschnitt „Prüfplan“): Checkliste der offenen
  Fahrzeugtests – ATF kalt, ATF warm in P/R/N/D (4 Lesungen), Kennung + Getriebe-Fehler (ADR-004), Starttest,
  Bus mitlesen bei laufendem Motor, Monitortests und Aussetzer warm (Mode 06), Fehlercodes. Jedes
  Diagnose-Ergebnis wird mit Zeit, Kühlmitteltemperatur und Drehzahl gespeichert und hakt den passenden Schritt
  ab (kalt < 40 °C, warm ≥ 70 °C).
- **TEILEN** gibt einen einzigen Text mit Checkliste und allen Ergebnissen samt Rohantworten aus; **LEEREN** fragt
  mit Anzahl nach. Gespeichert im App-Speicher (`pruefplan.txt`, höchstens 60 Ergebnisse).

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
