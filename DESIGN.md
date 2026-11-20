---
version: 1
name: "Mercedes ELM327 Monitor / Service-Labor"
description: "Dunkles technisches Diagnoseinstrument für den C350 S204/W204, ergänzt um klar getrennte Service-Experimentalaktionen."
colors:
  background: "#0D1523"
  card: "#152338"
  border: "#344A66"
  primary: "#225889"
  info: "#70C8FF"
  success: "#81C784"
  warning: "#FFCC76"
  danger: "#FF6B6B"
typography:
  sans:
    fontFamily: "Roboto, sans-serif"
  mono:
    fontFamily: "monospace"
components:
  button: {}
  card: {}
  dialog: {}
omitted:
  - section: spacing
    reason: "Native Android LayoutParams und dp-Werte in beiden vorhandenen Activities bleiben kanonisch."
  - section: rounded
    reason: "Bereits bestehende Android-Drawable/Button-Radien; keine neue Tokenquelle eingeführt."
---

# ELM327 – Designkontext

## Anwendung und Identität

Technisches Werkstattinstrument und Fahrzeug-Dashboard für Mercedes C350 S204/W204
(M272, 2008). Deutsche Oberfläche, querformatfreundlich, große Handschuh- und
Touch-Bedienflächen. Bestehenden dunklen OpenNextion-/OBD-Stil **beibehalten**.

## Kanonische visuelle Quelle

Runtime-Farbquelle für das Dashboard: `dashboard/src/main/java/de/roman/obddashboard/Palette.java`.
**Nicht** mehrere Theme-Kopien pflegen. Aktuelle normative Dashboardfarben:

| Rolle | Hex | Runtime |
|---|---|---|
| Hintergrund | `#0D1523` | `Palette.BG` |
| Karte | `#152338` | `Palette.CARD` |
| Trennlinie | `#344A66` | `Palette.BORDER` |
| Bedienaktion | `#225889` | `Palette.BUTTON` |
| Information | `#70C8FF` | `Palette.CYAN` |
| Lesend/positiv | `#81C784` | `Palette.OK` |
| Vorsicht | `#FFCC76` | `Palette.WARN` |
| Alarm/Fehler | `#FF6B6B` | `Palette.ALARM` |

Typografie: native Android-Roboto über `TextView`, `Button`, `Typeface.BOLD`;
Zahlen/Befehle im Diagnosekontext klar unterscheiden. Keine neuen Fonts.

## Service-Labor-Signatur

Der Experimentalzweig nutzt **WARN** für Service-Freigaben und irreversible
Aktionen, nie das grüne Read-only-Signal. „SIMULATION“ muss im
Schiebedach-Menü, jeder Aktion und jeder Ergebnisantwort sichtbar sein.
Bestätigungsdialoge benutzen native Android-AlertDialog und gemeinsame
`ServiceLabDialogs` statt duplizierter Fenster. Hauptnavigation bleibt
LIVE / GRAPH / DTC / INFO; Service-Einstieg unter DTC und in der Monitor-App.
Keine neuen Farb-/Abstands-Tokens: vorhandene Android-Views und `Palette`.
