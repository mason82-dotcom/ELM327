# ADR-001: ATF-Temperatur der 7G-Tronic als eng begrenzte Ausnahme vom Header-Verbot

**Status:** Angenommen (07.11.2026)
**Datum:** 07.11.2026
**Entscheidet:** Betreiber (mason)

## Kontext

Beide Apps sind streng read-only. `CommandSafety` lässt nur SAE-J1979-Lesedienste (01, 02, 03, 05, 06, 07, 09, 0A)
und eine feste Liste von AT-Befehlen zu; Header-Wechsel (`ATSH`, `ATCRA` …) sind bisher ausdrücklich gesperrt.

Die Getriebeöltemperatur (ATF) der 7G-Tronic 722.9 ist der meistgewünschte Zusatzwert für den C350 und der einzige
Mercedes-eigene Messwert mit öffentlichem Beleg (Recherche „C350 M272 Diagnosefunktionen“, 07.11.2026):
Header **7E1** (Getriebesteuergerät), Anfrage **`21 30`** (KWP2000 ReadDataByLocalIdentifier 0x30), Antwort von
7E9 `61 30 …`, **°C = Byte[11] − 50**. Belegt in mehreren Foren für W204 C63, W164, W212, W251 – nicht für den
C350 MJ 2008; es gibt Fehlberichte (konstant −51 °C, also Rohwert 0x01 = keine gültige Antwort).

Risiken:
1. Nach einem Abbruch bleibt der Header auf 7E1; folgende Standard-Abfragen gingen an das Getriebe statt an den Motor.
2. Eine allgemeine Freigabe von Headern oder Herstellerdiensten öffnet den Weg zu Schreib- und Steuerdiensten.
3. Billige ELM327-Klone setzen `ATSH` teils fehlerhaft um.
4. Die Formel ist nicht für dieses Fahrzeug verifiziert.

## Entscheidung

Option **A + D**: eine exakte Byte-Whitelist für genau diese eine Sequenz, nur auf Knopfdruck, mit Rohwert und
Plausibilitätsprüfung.

- Freigegeben werden **nur** die Zeichenketten `ATSH7E1`, `ATSH7DF` und `2130` – keine Muster, keine anderen
  Header, keine anderen Dienste oder Identifier. Alles andere bleibt gesperrt (Test sichert das ab).
- Die Sequenz `ATSH7E1` → `2130` → `ATSH7DF` läuft nur als exklusive Diagnose (Live-Polling pausiert) und stellt den
  Header im `finally` wieder her. Scheitert die Wiederherstellung, wird die Verbindung neu aufgebaut (`ATZ` setzt
  den Header ohnehin zurück).
- Kein Dauer-Polling: Die ATF-Temperatur wird auf Knopfdruck gelesen (Ölstand-Check, Warmlauf) und mit Rohantwort
  angezeigt, damit sie am echten C350 gegen die Warmlaufkurve geprüft werden kann.
- Rohwert 0x00/0x01 oder unplausible Werte (unter −40 °C, über 150 °C) gelten als „kein gültiger Wert“.

## Betrachtete Optionen

### A: Exakte Byte-Whitelist mit atomarer Sequenz
| Dimension | Bewertung |
|---|---|
| Aufwand | gering bis mittel |
| Risiko | gering: genau drei zusätzliche Befehle, keiner schreibt |
| Erweiterbarkeit | bewusst eingeschränkt; jeder weitere Wert braucht eine eigene Entscheidung |

**Vorteile:** Sicherheitsgrenze bleibt prüfbar; Header-Zustand ist an genau einer Stelle gekapselt.
**Nachteile:** Jeder weitere Herstellerwert braucht Code und Review.

### B: Generische Freigabe von 0x21/0x22 mit beliebigen IDs und Headern
**Vorteile:** schnell erweiterbar. **Nachteile:** öffnet beliebige Steuergeräte und Identifier; die Grenze „nur lesen“
ließe sich nicht mehr per Whitelist beweisen. Abgelehnt.

### C: ATF nicht umsetzen
**Vorteile:** kein neues Risiko. **Nachteile:** der wichtigste Zusatzwert fehlt.

### D: Nur Rohlog statt Anzeige
**Vorteile:** ehrlich bei unverifizierter Formel. **Nachteile:** für den Alltag wenig nützlich – daher mit A kombiniert:
Anzeige **plus** Rohantwort.

## Konsequenzen

- Einfacher: ATF-Kontrolle vor dem Ölstand-Check, Getriebeschutz bei Anhängerbetrieb.
- Schwerer: `CommandSafety` kennt jetzt eine zustandsbehaftete Ausnahme; die Wiederherstellung des Headers muss
  bei jeder Änderung mitgeprüft werden.
- Neu bewerten: sobald ein Wert am echten C350 gegen die Warmlaufkurve geprüft ist (Formel bestätigt oder verworfen).

## Umsetzungsschritte

1. [x] Diese Entscheidung.
2. [x] `CommandSafety`: exakt `ATSH7E1`, `ATSH7DF`, `2130` freigeben; Test, dass andere Header/Dienste gesperrt bleiben.
3. [x] `AtfTemperature` (Android-frei): Antwort von 7E9 auswerten, Plausibilität, Tests mit echten Antwortformen.
4. [x] Sitzung: exklusive Diagnose mit Header-Wiederherstellung im `finally`, Reconnect bei Fehler.
5. [x] Oberfläche: Knopf „ATF-Temperatur lesen“, Anzeige mit Rohwert.
   Umgesetzt in 1.1.0 / Dashboard 0.2.0, ergänzt nach Code-Review: Terminal sperrt die Sequenz
   (`CommandSafety.isAllowedInTerminal`), nach einem unbestätigten Header-Befehl gilt der Header als unbekannt
   (nur `ATSH7DF`/`ATZ` erlaubt), `LinkPolicy.reconnectInit` setzt bei 11-bit-CAN `ATSH7DF`.
6. [ ] Am C350 kalt und warm prüfen und hier eintragen.
