# ADR-002: Passives Mitlesen der Bus-Botschaften am OBD-Port

**Status:** Angenommen – Teil A nach dem Lauf am C350 (21.11.2026), gefiltertes Mitlesen (Option C) ergänzt (22.11.2026)
**Datum:** 20.11.2026, fortgeschrieben 22.11.2026
**Entscheidet:** Betreiber (mason)

## Kontext

Die XSMonitor-Beschreibungen für den W204 (rnd-ash/MBUX-Port, Auswertung in
[mercedes-hacking-docs.md](mercedes-hacking-docs.md)) nennen Antriebsstrang-Botschaften mit Motoröl-Temperatur,
Ölstand, Getriebeöl-Temperatur und Gang. Solche Botschaften senden die Steuergeräte zyklisch von selbst; man müsste
nichts anfragen, nur zuhören. Unklar ist, ob das Gateway des S204 sie an den OBD-Port durchreicht.

Der ELM327 kann zuhören: `ATMA` (Monitor All) zeigt jeden empfangenen Frame. Standardmäßig ist dabei „CAN Silent
Monitoring“ an, der Adapter sendet dann nicht einmal Bestätigungsbits. Allerdings:

1. `ATMA` endet nicht mit einem Prompt, sondern erst, wenn ein Zeichen kommt oder der Puffer überläuft. Die
   normale Befehlsschnittstelle (`sendCommand`, wartet auf `>`) passt nicht.
2. Ein CR als Stopp-Zeichen wiederholt beim ELM327 den letzten Befehl, also wieder `ATMA`.
3. Mit `ATCAF1` deutet der Adapter Frames als ISO-TP; Rohframes braucht `ATCAF0`. Bleibt `ATCAF0` stehen, lesen alle
   OBD-Parser falsche Antworten – auch nach einem TCP-Reconnect, weil WLAN-Adapter ihren Zustand behalten.
4. Filtervarianten (`ATMR`, `ATMT`, `ATCRA`, `ATCF`/`ATCM`) und `ATCSM0` (Bestätigungen senden) sind nicht nötig.

## Entscheidung

Option **A**: eine eigene, zeitbegrenzte Client-Methode und exakte Freigaben.

- `ATMA` steht **nicht** in der Whitelist von `CommandSafety.isAllowed`. Es ist nur über
  `Elm327Client.monitorPassive(dauer, maxBytes)` erreichbar: höchstens 30 s, in der App 5 s; danach ein einzelnes
  **Leerzeichen** als Stopp (kein CR), dann Warten auf `>` (2 s). Kommt kein Prompt, wird die Verbindung verworfen.
- Freigegeben werden zusätzlich genau `ATCAF0` und `ATCAF1`, beide **nicht im Terminal**. Die Sitzung setzt vor dem
  Mitlesen `ATH1` und `ATCAF0`, danach `ATCAF1` und `ATH0`; scheitert das, wird die Verbindung verworfen, und
  `LinkPolicy.reconnectInit` sendet nun immer `ATCAF1`.
- Nur auf Knopfdruck („Bus mitlesen“), als exklusive Diagnose (Live-Polling pausiert), nur bei 11-bit-CAN.
- Auswertung in `CanMonitor` (Android-frei): Kennungen zählen, für fünf bekannte W204-Botschaften die **Rohwerte**
  an den dokumentierten Bitpositionen zeigen. Keine Umrechnung in Einheiten, solange sie nicht am C350 geeicht ist.
- Filterbefehle, `ATCSM0` und alles, was sendet, bleiben gesperrt (Test sichert das ab).

## Betrachtete Optionen

### A: Eigene Client-Methode, exakte Freigaben (gewählt)
| Dimension | Bewertung |
|---|---|
| Aufwand | gering bis mittel |
| Risiko | gering: sendet nichts an das Fahrzeug; Adapterzustand wird wiederhergestellt oder die Verbindung neu aufgebaut |
| Erweiterbarkeit | Signale und Eichung lassen sich ergänzen, ohne die Sicherheitsgrenze zu ändern |

### B: `ATMA` in die allgemeine Whitelist
**Vorteile:** kein neuer Codepfad. **Nachteile:** im Terminal hinge die Verbindung ohne Prompt bis zum Timeout,
ein Folge-CR würde ATMA wiederholen. Abgelehnt.

### C: Gefiltertes Mitlesen (`ATCRA 30D` + `ATMA`)
**Vorteile:** weniger Daten, kein Pufferüberlauf. **Nachteile:** `ATCRA` ist seit ADR-001 bewusst gesperrt
(Adressierung); für die erste Frage „kommt überhaupt etwas an?“ unnötig. Erst neu bewerten, wenn Botschaften ankommen.

### D: Die Weck-Botschaft `02 10 92` aus dem Doku-Repo senden
**Nachteile:** Sitzungswechsel (KWP2000 extended session) an Steuergeräte, für den W204 nicht einmal dokumentiert.
Widerspricht dem Read-only-Grundsatz. Abgelehnt.

## Konsequenzen

- Einfacher: Am Fahrzeug ist in 5 s geklärt, ob passive Werte (Ölstand, Öltemperatur, ATF, Gang) erreichbar sind.
- Schwerer: Ein zweiter Lesepfad im Client neben `sendCommand`; die Stopp-Logik muss bei Änderungen mitgeprüft werden.
- Neu bewerten, sobald ein Lauf am C350 vorliegt: keine Bus-Botschaften → Funktion als reine Prüfung belassen;
  Botschaften vorhanden → Eichung gegen OBD-PIDs (siehe Auswertung, „Was offen bleibt“) und ggf. Option C.

## Umsetzungsschritte

1. [x] Diese Entscheidung (Status „Vorgeschlagen“ bis zur Freigabe).
2. [x] `CommandSafety`: `ATCAF0`/`ATCAF1` exakt, nicht im Terminal; `ATMA` und Filtervarianten gesperrt (Tests).
3. [x] `Elm327Client.monitorPassive` mit Zeitlimit, Leerzeichen-Stopp, Prompt-Prüfung, Datenlimit.
4. [x] `CanMonitor` mit Tests (Bitzählung, Leerzeichen/ohne, Statuszeilen, leerer Bus, nur Diagnose-Antworten).
5. [x] `ObdSession.runCanMonitor` mit Wiederherstellung; `reconnectInit` sendet `ATCAF1`.
6. [x] Knopf „Bus mitlesen“ in beiden Apps (Dashboard: ANALYSE).
7. [x] Am C350 bei laufendem Motor ausgeführt (21.11.2026), Ergebnis unten.

## Fahrzeugtest 21.11.2026 und Fortschreibung: gefiltertes Mitlesen (Option C)

**Ergebnis ungefiltert:** nach 0,2 s 13 Frames, dann `BUFFER FULL`; erkannt nur `0x105` (Drehzahl 925 U/min, passt
zu 01 0C). Der Bus ist am OBD-Port also hörbar – das Gateway reicht Antriebsstrang-Botschaften durch –, aber der
WLAN-Adapter kommt mit der Datenmenge des 500-kbit/s-Busses nicht mit und läuft sofort über. Ob die eigentlich
gesuchten Botschaften (Ölstand/Öltemperatur `0x30D`, ATF/Wählhebel `0x2F1`, Gang `0x0F3`) ankommen, sagt dieser Lauf
nicht.

**Entscheidung (Option C):** zusätzlich je bekannter Botschaft gefiltert mitlesen.

- Freigegeben werden genau `ATCRA30D`, `ATCRA105`, `ATCRA2F1`, `ATCRA0F3`, `ATCRA19F` und `ATCRA` ohne Adresse
  (Filter zurücksetzen) – keine Muster, keine anderen Kennungen, nicht im Terminal. `ATCRA` ändert nur, welche
  empfangenen Frames der Adapter zeigt; es sendet nichts an das Fahrzeug.
- Ablauf in „Bus mitlesen“: ungefiltert 1 s (Überblick über die Kennungen bis zum Überlauf), dann je Kennung
  `ATCRA<id>` und 2 s `ATMA`. Danach `ATCRA`, `ATCAF1`, `ATH0` und eine **Prüfanfrage `0100`**: Nur wenn sie eine
  gültige Antwort bringt, gilt der Adapter als zurückgesetzt; sonst wird die Verbindung verworfen.
- `LinkPolicy.reconnectInit` sendet zusätzlich `ATCRA`: Ein stehengebliebener Filter würde alle OBD-Antworten
  ausblenden und überlebt bei WLAN-Adaptern den TCP-Reconnect.
- Der Bericht zeigt je Kennung: empfangen ja/nein, Anzahl, Rohwerte an den dokumentierten Bitpositionen (weiterhin
  ohne Umrechnung, bis eine Eichung am C350 vorliegt).

| Alternative | Bewertung |
|---|---|
| Maske `ATCF`/`ATCM` | ein Filter für mehrere Kennungen, aber zwei Befehle je Einstellung und Bitmasken – schwerer als Liste exakter Strings zu prüfen |
| `ATS0`/kürzere Ausgabe | spart ~30 % Zeichen; reicht gegen den Überlauf nicht |
| Rücksetzen mit `ATD`/`ATZ` | setzt mehr zurück als nötig (Echo, Protokoll, Header), danach volle Initialisierung |
| weiter nur ungefiltert | beantwortet die Frage nach Ölstand/ATF auf dem Bus nicht |

**Risiko:** Billige Klone kennen `ATCRA` ohne Adresse eventuell nicht – ein gesetzter Filter bliebe dann über jeden
Neuaufbau stehen. Deshalb (Code-Review): Vor dem ersten Filter sendet die App `ATCRA` ohne Adresse; ohne „OK“ setzt
sie gar keinen Filter. Lehnt der Adapter einen Filter oder `ATMA` ab, endet der gefilterte Teil mit dieser Ursache.
Eine `0100` vor dem Mitlesen zeigt, ob das Fahrzeug antwortet; nur dann muss die Prüfanfrage danach eine `41 00` im
normalen Format (ohne PCI-Byte, also mit `ATCAF1`) bringen, sonst genügen die Bestätigungen der Rücksetzbefehle.
Abbrüche mitten im Lauf behalten die gesammelten Ergebnisse; die Verbindung wird dann neu aufgebaut.

8. [x] Freigaben und Tests (`CommandSafety`, Terminal gesperrt), Ablauf in `DiagnosticJobs.readCanMonitor`,
   `reconnectInit` mit `ATCRA`, Bericht je Kennung.
9. [ ] Am C350 erneut „Bus mitlesen“ bei laufendem Motor und warm; Ergebnis hier eintragen.
