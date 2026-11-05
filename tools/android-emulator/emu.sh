#!/usr/bin/env bash
# Bedienung des Android-Emulators auf brix-node.
#   ./emu.sh status                 Boot-Zustand, Android-Version, installierte OBD-Apps
#   ./emu.sh install <apk>          APK installieren (Berechtigungen erteilt); Dashboard -> Simulator 10.0.2.2:35000
#   ./emu.sh shot [datei.png]       Bildschirmfoto des Emulatorfensters
#   ./emu.sh adb <args>             beliebiger adb-Befehl im Container
#   ./emu.sh phone-pair <ip:port> <code>   Handy einmalig koppeln (Entwickleroptionen › Kabelloses Debugging › Mit Code koppeln)
#   ./emu.sh phone <ip:port>        Handy verbinden und mit scrcpy im noVNC-Fenster spiegeln (Port aus „Kabelloses Debugging“)
#   ./emu.sh phone-stop             Spiegelung beenden
set -euo pipefail
C=android-emulator
adb() { docker exec "$C" /sdk/platform-tools/adb "$@"; }
case "${1:-status}" in
  status)
    echo "Boot: $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || echo nein) · Android $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r') / SDK $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
    adb shell pm list packages 2>/dev/null | grep -E 'obddashboard|mercedesobdmonitor' || echo "keine OBD-App installiert"
    echo "Browser: http://192.168.178.50:6081/vnc.html" ;;
  install)
    f="${2:?APK angeben}"
    # Nach dem Booten erst warten, bis Paketdienst und Nutzer entsperrt sind (sonst „activity does not exist“).
    for i in $(seq 1 60); do adb shell dumpsys user 2>/dev/null | grep -q "State: RUNNING_UNLOCKED" && break; adb shell input keyevent 82 >/dev/null 2>&1; sleep 3; done
    docker cp "$f" "$C:/tmp/app.apk"
    adb install -r -g /tmp/app.apk | tail -1
    pkg=$(docker exec "$C" /sdk/build-tools/37.0.0/aapt2 dump packagename /tmp/app.apk)
    for p in android.permission.ACCESS_LOCAL_NETWORK android.permission.NEARBY_WIFI_DEVICES android.permission.POST_NOTIFICATIONS; do adb shell pm grant "$pkg" "$p" 2>/dev/null || true; done
    if [ "$pkg" = de.roman.obddashboard ]; then
      docker exec "$C" sh -c "printf '%s' \"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\\\"host\\\">10.0.2.2</string><string name=\\\"port\\\">35000</string><boolean name=\\\"notifications_asked\\\" value=\\\"true\\\" /></map>\" > /tmp/dashboard.xml"
      adb push /tmp/dashboard.xml /data/local/tmp/dashboard.xml >/dev/null
      adb shell am force-stop "$pkg"
      adb shell run-as "$pkg" mkdir -p shared_prefs
      adb shell run-as "$pkg" cp /data/local/tmp/dashboard.xml shared_prefs/dashboard.xml
      echo "Dashboard auf Laborsimulator 10.0.2.2:35000 eingestellt"
    fi
    adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true ;;
  shot)
    out="${2:-emu-$(date +%H%M%S).png}"
    docker exec "$C" sh -c 'DISPLAY=:1 import -window root /tmp/shot.png' && docker cp "$C:/tmp/shot.png" "$out" && echo "$out" ;;
  adb) shift; adb "$@" ;;
  phone-pair) adb pair "${2:?ip:port aus „Mit Code koppeln“}" "${3:?sechsstelliger Code}" ;;
  phone)
    target="${2:?ip:port aus „Kabelloses Debugging“}"
    adb connect "$target"
    docker exec "$C" pkill -x scrcpy 2>/dev/null || true
    # Kein Ton (Container ohne Audio), Fenster auf die noVNC-Fläche begrenzt, Handy-Display bleibt an.
    docker exec -d -e DISPLAY=:1 "$C" sh -c "scrcpy -s '$target' --no-audio --max-size=960 --window-title='Handy (scrcpy)' --stay-awake >/tmp/scrcpy.log 2>&1"
    sleep 4; docker exec "$C" tail -3 /tmp/scrcpy.log
    echo "Im Browser: http://192.168.178.50:6081/vnc.html" ;;
  phone-stop) docker exec "$C" pkill -x scrcpy && echo "Spiegelung beendet" || echo "keine Spiegelung aktiv" ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
