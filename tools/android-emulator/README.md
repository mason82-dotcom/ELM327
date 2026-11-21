# Android-Emulator für die Oberflächenprüfung

Android 16 (API 36, google_apis x86_64) im Docker-Container `elm-emu`, ohne Fenster, mit KVM. Gedacht für
Screenshots von Monitor und Dashboard vor einem PR, im Hoch- und Querformat und im Format des Android-Radios.

## Starten und beenden

```bash
tools/android-emulator/start.sh            # Telefon (Pixel 6, 1080×2400), AVD elm-test
tools/android-emulator/start.sh radio      # Android-Radio (1024×600, 160 dpi, quer), AVD elm-radio
tools/android-emulator/start.sh --cold     # Snapshot nicht laden (nach einem Hänger)
tools/android-emulator/stop.sh             # beenden, Snapshot speichern (etwa 5 s)
```

- Der erste Start eines Profils legt das AVD an (etwa 80 s). Danach startet es aus dem Snapshot in etwa 10 s.
  Apps, Berechtigungen und die eingetragene Adapteradresse bleiben erhalten.
- Beim allerersten Start meldet Android „System UI isn't responding“ → `nav.sh tap Wait`.
- Immer nur ein Profil gleichzeitig (adb-Gerät `emulator-5560`). Der Container hat einen niedrigen CPU-Anteil, damit
  Gradle-Builds Vorrang haben. Nach der Prüfung `stop.sh`: der Emulator belegt 2–3 GB Arbeitsspeicher.
- Beenden nur mit `stop.sh` (SIGTERM speichert den Snapshot). Wurde der Container hart beendet und startet danach
  nicht („snapshot operation … pending“): im Ordner `<AVD>.avd` die `*.lock`-Dateien und `snapshots/default_boot`
  löschen, dann `start.sh --cold`.

Voraussetzungen und Pfade:

| Variable | Standard | Inhalt |
|---|---|---|
| `ANDROID_EMU_SDK` | `~/android-emu` | `emulator`, `platform-tools`, `cmdline-tools/latest`, `system-images;android-36;google_apis;x86_64` |
| `ELM_EMU_HOME` | `~/elm-emu/home` | AVDs, Snapshots und Einstellungen des Emulators |

Das Image `elm-emu:1` (`Dockerfile`: Laufzeitbibliotheken des Emulators, Java 17) baut `start.sh` beim ersten Mal
selbst. Der Container läuft mit der eigenen Nutzerkennung und der Gruppe `kvm`, ohne zusätzliche Rechte.

## Bedienen

```bash
ADB="$HOME/android-emu/platform-tools/adb -s emulator-5560"
$ADB install -r -g dashboard/build/outputs/apk/debug/dashboard-debug.apk
tools/android-emulator/nav.sh tap "Verbinden"     # Element mit diesem Text antippen
tools/android-emulator/nav.sh shot live.png       # Screenshot
tools/android-emulator/nav.sh rot 1               # Querformat (0 = hoch)
```

`tap` liest die Oberfläche mit `uiautomator dump`. Das hängt, solange Live-Werte laufen; dann mit
`$ADB shell input tap <x> <y>` tippen.

Als ELM327 in der App `192.168.178.50` mit Port `35000` (normal), `35001` (Fehlerphasen) oder `35010` (Proxy)
eintragen – siehe [tools/elm327-emulator](../elm327-emulator/README.md).
