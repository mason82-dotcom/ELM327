# ADR-008: Gang und ATF-Temperatur live im Dashboard, mit ATF-Warnung

**Status:** Angenommen (21.11.2026) – am C350 im Fahrbetrieb noch zu bestätigen
**Datum:** 21.11.2026
**Entscheidet:** Betreiber (mason)
**Bezug:** [ADR-001](adr-001-atf-temperatur.md) (ändert dort den Punkt „kein Dauer-Polling“),
[ADR-003](adr-003-atf-antwort-layout.md), Wettbewerbsanalyse „W204 & W212 OBD Pro“ (Lücke „Gang/ATF live“)

## Kontext

ADR-001 gibt genau drei Befehle frei – `ATSH7E1` → `21 30` → `ATSH7DF` – und nur auf Knopfdruck, weil die Formel
damals für den C350 unbelegt war. Seit dem Fahrzeugtest vom 21.11. ist das Antwortlayout `61 30` bestätigt
(ADR-003): Wählhebel [9] (P 10, R 9, N 8, D 7), Gang [12] je Halbbyte, ATF [13] = Wert − 50, kalt 8 °C bei 5 °C
außen, 62 °C nach 30 min Fahrt. Gang und ATF während der Fahrt sind der meistgewünschte Zusatzwert.

Die Live-Schleife (`ObdSession.monitorLoop`) fragt alle PIDs nacheinander in **einem** Thread ab (Live-Polling mit
`ATH1` auf 11-bit-CAN); `pollDriveCycle` zeigt bereits das Muster für einen Zusatzblock alle N Runden. Ein Block in
diesem Thread kann sich mit keiner anderen Abfrage überschneiden.

Werkstatthandbuch (WIS, eigene Zusammenfassung): Betriebstemperatur des 722.9 etwa 80 °C, Getriebeöl-Thermostat
öffnet über 90 °C, mehrere Getriebefunktionen gelten nur bis 110 °C, bei zu heißem Öl nimmt das
Getriebesteuergerät das Motormoment zurück. Eine feste Übertemperatur-Grenze nennt das Handbuch nicht.

Risiken wie in ADR-001: Der Header bleibt nach einem Fehler auf 7E1 (Standard-PIDs gingen ans Getriebe), und jeder
weitere Befehl weicht die Freigabe auf. Neu: Last auf dem Adapter und Wartezeit der übrigen Live-Werte.

## Entscheidung

**Option A**: dieselbe Dreier-Sequenz im Live-Thread, etwa alle 2 s.

- **Keine neuen Befehle.** Freigabe und `CommandSafety` bleiben unverändert; nur die Häufigkeit ändert sich.
- Der Block läuft im Thread der Live-Schleife, nur auf 11-bit-CAN und nur, wenn die App ihn einschaltet (Dashboard,
  wie die Warnungen). Die Monitor-App behält den Knopf „ATF-Temperatur“.
- **Header immer zurück:** `ATSH7DF` im `finally`; scheitert das oder meldet der Client danach nicht 7DF, wird die
  Verbindung verworfen (Reconnect setzt den Header über `LinkPolicy.reconnectInit`).
- **Selbstabschaltung:** nach 3 Fehlschlägen in Folge (keine/unplausible Antwort, Ablehnung 7F) oder einem
  verworfenen Header für den Rest der Sitzung aus (bis „Trennen“; automatische Neuverbindungen ändern daran nichts), mit Hinweis in der Konsole – kein Dauerfehler bei einem anderen
  Fahrzeug oder Adapter.
- **Kein alter Wert:** Gang und ATF laufen durch dasselbe Alterslimit wie alle Live-Werte (`LiveValues`, 15 s) und
  zeigen „–“ ohne frische Antwort. Rohwert 0/1 oder unplausibel (ADR-001) = kein Wert.
- **ATF-Warnung (Richtwerte, keine Herstellerangabe):** Warnung ab **120 °C**, Alarm ab **130 °C**, je 5 s Haltezeit
  und 4 K Hysterese wie beim Kühlmittel. Begründung: deutlich über dem Funktionsbereich bis 110 °C und dem Thermostat
  (90 °C), damit normale Bergfahrten im Sommer nicht warnen.

## Betrachtete Optionen

### A: Dreier-Sequenz im Live-Thread alle ~2 s
| Dimension | Bewertung |
|---|---|
| Aufwand | mittel |
| Risiko | gering: keine neuen Befehle, Header-Rückweg wie ADR-001 im `finally`, Abbruch verwirft die Verbindung |
| Last | 3 Befehle je 2 s (geschätzt 150–300 ms) – die übrigen Live-Werte kommen etwas seltener |
| Nutzen | Gang reagiert in ~2 s, ATF praktisch live |

**Vorteile:** Gang wird brauchbar, Warnung möglich, Freigabe bleibt beweisbar.
**Nachteile:** Header-Wechsel alle 2 s; billige Klone mit fehlerhaftem `ATSH` fallen auf (→ Selbstabschaltung).

### B: Nur ATF alle 10–30 s
**Vorteile:** kaum Last. **Nachteile:** Gang nutzlos; Warnung bis zu 30 s zu spät. Für die ATF allein ausreichend,
aber verfehlt das Ziel „Gang live“.

### C: `ATSH7E1` dauerhaft, Standard-PIDs über zweiten Header oder `ATCRA`
**Vorteile:** keine Header-Wechsel. **Nachteile:** neue Befehle (`ATCRA`, weitere Header), alle OBD-Werte hingen
an einer Sonderkonfiguration; ein Fehler beträfe jede Abfrage. Abgelehnt.

### D: Nichts live
**Vorteile:** kein neues Risiko. **Nachteile:** größte Lücke gegenüber der Konkurrenz bleibt.

## Abwägung

A und B unterscheiden sich nur im Takt; das Risiko liegt in der Sequenz selbst und ist für beide gleich gekapselt.
Der Takt von 2 s ist der kleinste, bei dem ein Gangwechsel noch sichtbar folgt, und lässt den übrigen Werten über
85 % der Adapterzeit. C würde die Freigabe erweitern und den Normalbetrieb an den Sonderfall koppeln.

## Konsequenzen

- ADR-001 „Kein Dauer-Polling“ gilt nicht mehr für das Dashboard; die Sequenz und ihre Freigabe bleiben gleich.
- Die Live-Schleife bekommt einen zweiten Zusatzblock neben `pollDriveCycle`; Gang/ATF erscheinen als Live-Werte mit
  eigenem Schlüssel (kein OBD-PID) und im CSV-Log.
- Nachzuprüfen am C350: Takt und Latenz der übrigen Werte im Fahrbetrieb, ATF unter Last (Bergfahrt), Verhalten bei
  Wählhebel P/R/N während der Fahrt.

## Aufgaben

1. [x] Live-Block in `ObdSession` (Takt 2 s, nur 11-bit-CAN, Einschalter, Header-Rückweg, Selbstabschaltung).
2. [x] Gang/ATF als Live-Werte mit Alterslimit, CSV-Spalten.
3. [x] `LiveWarnings`: ATF-Warnung/-Alarm mit Haltezeit und Hysterese.
4. [x] Dashboard: Gang und ATF auf LIVE (Hoch- und Querformat, Radio 1024×600).
5. [x] Tests: Parser mit `ATH1`-Antwort, Sequenz und Abschaltung im ELM-Nachbau, Warnregeln, UI-Test über `proxy.py`.
6. [ ] Am C350: Fahrt mit Gangwechseln, Prüfplan-Bericht teilen.

## Nachtrag 22.11.2026: Takt am C350

Erste Fahrten mit Getriebe live (Logs 22.11.): Gang D1–D7 passend zum Tempo (D7 ab 85 km/h), ATF 6 → 64 °C, übrige
Werte +10–15 % langsamer (Drehzahl alle 2,35 s statt 2,0–2,15 s) – wie abgeschätzt. Gang/ATF kamen aber nur alle
3,8 s (Median), weil der Block erst nach einer ganzen Abfragerunde lief. Seitdem prüft die Schleife vor jeder PID,
ob er fällig ist (nicht als erste Anfrage nach einem Neuaufbau, mit 25 ms Abstand danach); im Emulator 2,03 s
Median, 2,07 s p95. Getriebe-Zeitüberschreitungen zählen nicht mehr in den Verbindungszähler der PIDs, nur in die
Abschaltung nach drei Fehlschlägen. `uitest.sh` prüft den Takt über das Proxy-Protokoll (Median ≤ 2,6 s).

