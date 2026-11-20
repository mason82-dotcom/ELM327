# C350 W204 / M272 – Live-Logprüfung 20.11.2026

**Grundlage:** Zwei vom Anwender bereitgestellte Exportdateien mit identischem Start
`Mercedes_OBD2_Monitor_20261120_165041.csv` und
`Mercedes_OBD2_Monitor_20261120_165041 (1).csv`.
Die zweite Datei enthält vollständig auch die erste (exakt 2.656 identische Datensätze).
Nicht als zwei unabhängige Fahrten auswerten.
Fahrzeugbefunde (Spannung bei Zündung an, Trims, Bankunterschied kalt):
[c350-log-20261120.md](c350-log-20261120.md).

## Verifizierte Beobachtungen im längeren CSV

| Kennzahl | Ergebnis |
|---|---:|
| Zeitraum | 16:50:41.360–17:01:50.050 Uhr (lokale Loggerzeit) |
| Gültige CSV-Datensätze | 4.154 |
| Unterschiedliche Mode-01-PIDs | 31 |
| Antworten mit `7E8` und `7E9` | 878 |
| Davon `7E9` vor `7E8` | 335 |
| Jeweils direkt aus CAN-Nutzdaten kontrollierte Werte (01 0C/0D/05/42) | 579 |
| Abweichung CSV-Wert gegenüber `7E8` (bei diesen 579) | **0** |
| Unterschiede der ECU-Rohwerte bei diesen 579 | 401 |
| Antwortlatenz Median / P95 / P99 | 91 / 265 / 304 ms |
| Antwortlatenz Maximum | 1.528 ms |
| Erfolgreiche Werte über 1.000 ms | 4 |
| Standard-Poll-Timeout (`LinkPolicy.POLL_TIMEOUT_MS`) | 1.800 ms |

**Abgleich:** Aktueller `ObdParser.payloadLines` priorisiert `7E8` unabhängig
von der Ankunftsreihenfolge. Die Logs bestätigen das in den auswertbaren
Doppelantworten. Besonders wichtig: bei PID `42` meldete `7E9`
10,925 V und `7E8` 11,497 V; der Livewert war 11,497 V. Bei
PID `0C` waren 1.060 U/min (`7E9`) und 1.072,75 U/min (`7E8`)
gleichzeitig vorhanden; angezeigt wurde 1.072,75 U/min.

## Vier tatsächlich erfolgreiche langsame Antworten

| PID | Latenz | CSV-Wert | Rohantwort |
|---|---:|---:|---|
| `0C` | 1.367 ms | 1.072,75 U/min | `7E904410C1090 7E804410C10C3` |
| `08` | 1.322 ms | +2,34375 % | `7E803410883` |
| `0C` | 1.414 ms | 678,5 U/min | `7E804410C0A9A 7E904410C0A98` |
| `06` | 1.528 ms | 0 % | `7E803410680` |

Diese vier Ereignisse sind **erfolgreiche Antworten**, nicht Socket-Timeouts.
Sie rechtfertigen keine pauschale Verlängerung des Poll-Timeouts. Die bestehende
Policy (1.800 ms, zwei aufeinanderfolgende Timeouts mit erfolgreicher
Resynchronisierung, andernfalls Reconnect) wurde nicht geändert. Reserve zur Deadline: rund 270 ms; über
Bluetooth (ADR-005) beim ersten Test am echten Adapter die Latenz im Log beobachten.

## Maßnahmen in dieser Revision

- Polling-Timeout als eine gemeinsam verwendete Konstante in `LinkPolicy`;
  Live-PID-Abfrage und Drive-Cycle-Read benutzen denselben Wert.
- JUnit-Regressionsbeispiele mit echten, auf einzelne Rohantworten beschränkten
  Fällen für `7E9`-first/`7E8`-first, unterschiedliche ECU-Werte sowie
  alle vier Latenzausreißer.
- **Nicht** die vollständigen hochgeladenen CSV-Dateien in GitHub einspielen:
  minimierte Messwertbeispiele genügen für den Parser-Regressionstest.

## Nicht belegt / offen

- Die CSV-Dateien protokollieren erfolgreiche Mode-01-Messungen, **keine**
  vollständigen DTC-Scans für Modes 03/07/0A. Über deren Ergebnisse lässt sich
  hieraus keine Aussage treffen.
- Aus dem Export allein folgt weder eine vollständig fehlerfreie Adapterverbindung
  noch die Abnahme der DTC-STOP-/Reconnect-Funktion.
- Nächster echter Hardwaretest: mit WiFi_OBDII einen Mode-03/07/0A-Scan starten,
  ihn gezielt mit STOP abbrechen, Verbindung/Livewerte neu aufbauen lassen
  und ausschließlich anonyme Protokollausgaben sowie Soll/Ist und Zeitstempel
  dokumentieren. Fahrzeugkonfiguration unverändert lassen; strikt read-only.
