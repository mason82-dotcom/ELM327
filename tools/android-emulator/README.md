# Android-Emulator für OBD Dashboard und Monitor-App

Android 17.2 (SDK 37.2, Google APIs, x86_64) im Docker-Container mit KVM, Bedienung im Browser über noVNC und
der ELM327-**Laborsimulator** (kein Fahrzeug, kein Adapter nötig). Dazu **scrcpy**, um ein echtes Handy per
WLAN-ADB im selben Browserfenster zu spiegeln. Betrieben auf brix-node unter `/srv/stacks/android-emulator`.

## Voraussetzungen

- Linux-Host mit Docker und `/dev/kvm`
- Android-SDK: `tools/android-sdk/install-sdk.sh` (legt `~/android-sdk` an, ohne Root und ohne Java auf dem Host)
- etwa 6,5 GB freier RAM (Android 4 GB) und 3–4 CPU-Kerne

## Starten

```bash
cd tools/android-emulator          # auf brix-node: /srv/stacks/android-emulator
cp .env.example .env               # optional: LAN-Adresse, Port, SDK-Pfad
docker compose up -d --build
```

Browser: `http://<EMU_BIND>:<EMU_PORT>/vnc.html` (brix-node: http://192.168.178.50:6081/vnc.html). Nur an die
LAN-Adresse gebunden und ohne Passwort (Heimnetz). Erster Start inklusive Gerät anlegen: etwa 2 Minuten.

## Bedienung (`emu.sh`)

| Befehl | Wirkung |
|---|---|
| `./emu.sh status` | Boot-Zustand, Android-Version, installierte OBD-Apps |
| `./emu.sh install <apk>` | installieren mit Berechtigungen; das Dashboard wird auf den Simulator `10.0.2.2:35000` eingestellt |
| `./emu.sh shot [datei.png]` | Bildschirmfoto des Browserbilds |
| `./emu.sh phone-pair <ip:port> <code>` | Handy einmalig koppeln (Entwickleroptionen › Kabelloses Debugging › Mit Code koppeln) |
| `./emu.sh phone <ip:port>` | Handy verbinden und mit scrcpy im noVNC-Fenster spiegeln |
| `./emu.sh phone-stop` | Spiegelung beenden |
| `./emu.sh adb <args>` | beliebiger adb-Befehl |

## Behobene Fallstricke (05.11.2026)

Ohne diese Maßnahmen startete Android im Container ständig neu oder hatte kein Netz (Details in `entrypoint.sh`):

1. **`-feature -GLDMA`** – mit gfxstream/SwiftShader (ohne GPU) bricht surfaceflinger sonst beim Zurücklesen von
   Bildinhalten ab (`Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`), Android startet neu.
2. **3-Tasten-Navigation** (wird nach jedem Boot gesetzt) – die Gestennavigation lässt surfaceflinger per
   `RegionSamplingThread` dennoch zurücklesen; derselbe Absturz über `GoldfishMapper::readFromHost`.
3. **`::1` und eine ULA-Adresse auf `lo`** (`cap_add: NET_ADMIN`, nur eigener Netz-Namensraum) – Docker vergibt ohne
   IPv6 im Daemon kein `::1`, und glibc löst `::1` ohne nicht-lokale IPv6-Adresse nicht auf; dann bindet der
   Emulator Modem und WLAN nicht an und Android hat kein Netz (`ENETUNREACH`).
4. **`-feature -Uwb`** – der UWB-Treiber sucht `/dev/uwb0` und stürzte alle 5 s ab (nur Rauschen).
5. Verwaiste `*.lock` im AVD werden vor jedem Start entfernt („Running multiple emulators with the same AVD“).

Ergebnis: 1 Systemstart, 0 Abstürze, CPU rund 35 % im Leerlauf, Gast mit `eth0` 10.0.2.15.

## Bauen ohne Android Studio

```bash
tools/android-sdk/gradle.sh "$PWD" :obdcore:testDebugUnitTest :app:assembleDebug :dashboard:assembleDebug
```

`elm327_lab_simulator.py` stammt unverändert aus `OpenNextion_ELM327_Interface/tools/` (Stand 6a8e58e).
