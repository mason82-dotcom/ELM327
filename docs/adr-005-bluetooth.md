# ADR-005: ELM327 auch über Bluetooth (SPP)

**Status:** Angenommen (20.11.2026)
**Entscheidet:** Betreiber (mason)

## Kontext

Das Dashboard soll auf dem fest verbauten Android-Radio (Navifly N600, 10 Zoll) laufen. Verbindet sich das Radio mit
dem WLAN des ELM327-Adapters, verliert es seinen Internetzugang über den Handy-Hotspot (meist nur eine
WLAN-Verbindung gleichzeitig). Vorhanden ist zusätzlich ein Bluetooth-ELM327 („blau-transparent“, Bluetooth Classic,
SPP, typisch v1.5-Nachbau, PIN 1234/0000).

RFCOMM kennt weder Verbindungs- noch Lese-Timeout; der Client hängt aber vollständig an Timeouts (Prompt, Resync,
ATMA-Stopp, Header-Wiederherstellung).

## Entscheidung

- Transportschicht `ElmLink` (Ströme, Lese-Timeout mit `SocketTimeoutException`, Schließen bricht Lesen ab);
  `ElmLink.Tcp` (bisheriges Verhalten) und `BluetoothLink` (SPP-UUID, sichere, dann unsichere RFCOMM-Verbindung,
  Wächter für die Verbindungsfrist).
- `TimedInputStream`: Lesefaden mit Ringpuffer (64 KB) liefert den Lese-Timeout für Bluetooth.
- Nur gekoppelte Geräte (Kopplung in den Android-Einstellungen), keine Gerätesuche: dafür reicht `BLUETOOTH_CONNECT`
  (Android 12+) bzw. `BLUETOOTH` (bis Android 11); keine Standort- oder Scan-Berechtigung.
- Wahl in beiden Apps über „Adapter: … · ändern“ (WLAN oder gekoppeltes Gerät), gespeichert in den Einstellungen;
  das automatische Wiederverbinden nimmt denselben Weg.
- An der Befehlsfreigabe (`CommandSafety`) ändert sich nichts: sie hängt nicht am Transport.

## Konsequenzen

- Radio behält sein WLAN; das Dashboard läuft dort über Bluetooth.
- Bluetooth-Nachbauten antworten langsamer (typ. 50–150 ms je Befehl); die Abfragerate sinkt entsprechend.
- Nicht am echten Adapter geprüft (Emulator ohne Bluetooth): erster Test im Auto. Unit-Tests decken
  `TimedInputStream` und den Client über einen Transport ohne Socket-Timeout ab.
