# ADR-006: Automatisch verbinden beim App-Start und nach dem Radiostart

**Status:** Angenommen (20.11.2026)
**Entscheidet:** Betreiber (mason)

## Kontext

Das Dashboard läuft auf dem Android-Radio (Navifly N600). Ohne Autostart wird es im Alltag nicht geöffnet und
zeichnet keine Fahrten auf. Die Konkurrenz-App „W204 & W212 OBD Pro“ startet mit dem Radio und verbindet selbst
(Wettbewerbsanalyse 20.11.2026). Die Sitzung verbindet nach einem Abbruch dauerhaft neu, scheitert aber der erste
Versuch, endet sie – beim Radiostart ist das zu erwarten (Bluetooth fährt hoch, Zündung noch aus).

## Entscheidung

- Einstellung „Automatisch verbinden“ (Dashboard, INFO), ausgeliefert aus.
- **App-Start:** einmal je Prozess verbinden, wenn keine Sitzung läuft und der Benutzer nicht selbst getrennt hat.
- **Radiostart:** `BOOT_COMPLETED` → `ObdService` (Vordergrund) im Modus `ACTION_AUTO_CONNECT`: alle 20 s ein Versuch mit
  den gespeicherten Einstellungen, höchstens 10 min; danach übernimmt das Wiederverbinden der Sitzung. „Trennen“
  beendet das Auto-Verbinden. Fehlt eine Berechtigung, wird nicht weiter versucht.
- Die Oberfläche öffnet Android 10+ nicht aus dem Hintergrund; dafür die Autostart-Liste des Radios.
- Keine neuen Befehle; `CommandSafety` unverändert.

## Konsequenzen

- Fahrten werden auch ohne geöffnete App aufgezeichnet (CSV-Log → Fahrtenbuch, Trim-Verlauf).
- `BOOT_COMPLETED` kommt je nach Gerät erst Minuten nach dem Start (Emulator: ~1–2 min). Radios mit Schlafmodus
  starten bei Zündung nicht neu; dort hält der Dienst die Sitzung und verbindet selbst neu.
- Nach „Beenden erzwingen“ (App-Info) liefert Android kein Boot-Signal mehr, bis die App einmal geöffnet wurde.
- Geprüft im Emulator: Neustart → Dienst verbindet ohne geöffnete App; Adapter minutenlang nicht erreichbar → der
  Dienst versucht weiter und verbindet 20 s, nachdem der Adapter wieder antwortet; App-Start ohne Sitzung → verbindet.
