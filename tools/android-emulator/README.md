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
- Absturz- und „reagiert nicht“-Dialoge sind im Emulator abgeschaltet (`hide_error_dialogs`); ein schon offener
  Dialog aus einem älteren Snapshot: `nav.sh tap Wait`.
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

## UI-Tests (Monitor und Dashboard)

```bash
tools/android-emulator/start.sh radio      # oder ohne radio: Telefon im Hochformat
tools/android-emulator/uitest.sh           # beide Apps: bauen, Proxy, installieren, ausführen (etwa 4 min)
tools/android-emulator/uitest.sh dashboard # nur eine App
tools/android-emulator/uitest.sh --no-build app terminalRefusesWritingCommandsAndSendsReadOnlyOnes   # ein Test
```

Espresso-Tests in `app/src/androidTest/…/MonitorUiTest.java` und `dashboard/src/androidTest/…/DashboardUiTest.java`,
gemeinsame Hilfen (Warten, Simulator-Probe, Argumente) im Modul `:testsupport`. Gegen den Simulator `:35000` und
`proxy.py hot,dtc` auf `:35010` sowie `proxy.py atf` auf `:35011` (Getriebeantwort `21 30`), die jede Anfrage der
Apps protokollieren:

| Test | Monitor | Dashboard | Prüft |
|---|---|---|---|
| `startsDisconnectedWithoutValues` | ✓ | ✓ | ohne Verbindung überall „–“, „READ-ONLY“ sichtbar |
| `connectShowsValuesAndDisconnectClearsThemAtOnce` | ✓ | ✓ | Verbinden liefert Werte, nach „Trennen“ sofort wieder „–“ |
| `unreachableAdapterNeverShowsValues` | ✓ | ✓ | Adapter nicht erreichbar: 8 s lang nie ein Wert |
| `storedCodesAreReadOnlyOnRequest` | ✓ | ✓ | Verbinden liest keinen Fehlerspeicher; erst auf Knopfdruck P0171 und P0302 |
| `overheatingFromLiveValueRaisesAlarmAndDisconnectClearsIt` | – | ✓ | 120 °C → Alarm „Motor überhitzt“, Trennen nimmt ihn weg |
| `gearAndTransmissionOilAreLiveWithWarning` | – | ✓ | Gang „D 3→2“ am Drehzahlmesser, ATF 125 °C, Warnung „Getriebeöl“; Trennen nimmt alles weg (ADR-008) |
| `transmissionLiveSwitchesOffWhenTheGearboxNeverAnswers` | – | ✓ | Simulator ohne Getriebe: nach 3 Versuchen „ATF Getriebe · aus“, übrige Werte laufen weiter |
| `terminalRefusesWritingCommandsAndSendsReadOnlyOnes` | ✓ | – | Terminal sperrt `04`, `14 FF 00`, `2E F1 90 00`, `31 01 FF 00`, `27 01`, `ATSH7E1`; `ATIGN` geht hinaus |

Danach prüft `uitest.sh` im Protokoll des Proxys (Ende-zu-Ende-Prüfung der Read-only-Grenze):

- **Kein gesperrter Befehl hat den Adapter erreicht**, auch nicht mit Anhang (z. B. `04 1`). Die Liste steht nur
  in `uitest.sh` (`BLOCKED`) und geht als Argument an den Terminal-Test. `ATSH7E1` ist von dieser Prüfung
  ausgenommen (`LOG_EXEMPT`), weil das Dashboard es für Gang/ATF legitim sendet; im Terminal bleibt es gesperrt. Gegenprobe: `ATIGN` aus dem Terminal-Test
  muss im Protokoll stehen (die App sendet es sonst nie); fehlt der Test, meldet das Skript SKIP statt OK.
- **Verbinden sendet kein Mode 03:** Der DTC-Test der Monitor-App setzt vor dem Klick auf „DTC“ die Marke `AT@1`
  (nie von den Apps gesendet – `ATDPN` gehört zum Verbindungsaufbau und taugt nicht); zwischen dem `ATZ` dieser
  Verbindung und der Marke darf kein `03` stehen, Live-Abfragen (`01…`) müssen dort stehen.
- **Getriebe live im Takt:** `21 30` im Median höchstens alle 2,6 s (ADR-008, Soll 2 s).

Der Simulator bedient wie ein WLAN-ELM327 nur eine Verbindung zur Zeit; nach jedem Test wartet der Test, bis er
wieder antwortet (im Protokoll `simulator free after … ms`, bisher etwa 0,5 s). `uitest.sh` schaltet im Emulator
die Animationen für den Lauf ab (Espresso) und stellt sie danach wieder her. Exit-Code 0 nur, wenn alles besteht. Eine mit anderem Schlüssel installierte App
(z. B. APK aus der CI) wird im Emulator ersetzt; deren Daten gehen dort verloren. Die CI kompiliert die Tests nur
(`assembleDebugAndroidTest`), ausführen kann sie sie nicht.

Als ELM327 in der App `192.168.178.50` mit Port `35000` (normal), `35001` (Fehlerphasen) oder `35010` (Proxy)
eintragen – siehe [tools/elm327-emulator](../elm327-emulator/README.md).
