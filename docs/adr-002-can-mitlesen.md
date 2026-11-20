# ADR-002: Passives Mitlesen der Bus-Botschaften am OBD-Port

**Status:** Vorgeschlagen (20.11.2026)
**Datum:** 20.11.2026
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
7. [ ] Am C350 bei laufendem Motor ausführen und Ergebnis hier eintragen.
