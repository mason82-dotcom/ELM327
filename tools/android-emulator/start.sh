#!/usr/bin/env bash
# Android-Emulator für die ELM327-Apps starten.
#   ./start.sh            Telefon (Pixel 6, 1080×2400) – AVD elm-test
#   ./start.sh radio      Android-Radio (1024×600, 160 dpi, quer) – AVD elm-radio
#   ./start.sh … --cold   ohne Snapshot starten (z. B. nach Hänger)
# Das AVD bleibt in $ELM_EMU_HOME erhalten (Berechtigungen, Adapteradresse, installierte Apps).
# Beim Stoppen (./stop.sh) wird ein Snapshot gespeichert; der nächste Start dauert dann Sekunden.
# Pfade: ANDROID_EMU_SDK (emulator, platform-tools, cmdline-tools, Image android-36 google_apis x86_64),
#        ELM_EMU_HOME (AVDs und Einstellungen des Emulators). Voraussetzung: Docker und /dev/kvm.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_EMU_SDK:-$HOME/android-emu}"
DATA="${ELM_EMU_HOME:-$HOME/elm-emu/home}"
ADB="$SDK/platform-tools/adb"
IMAGE=elm-emu:1
profile=phone cold=0
for a in "$@"; do
  case "$a" in
    phone|radio) profile=$a ;;
    --cold) cold=1 ;;
    *) echo "Unbekannt: $a (phone|radio|--cold)"; exit 2 ;;
  esac
done
if [ "$profile" = radio ]; then avd=elm-radio mem=2048; else avd=elm-test mem=3072; fi

kvm_gid=$(getent group kvm | cut -d: -f3 || true)
[ -n "$kvm_gid" ] || kvm_gid=$(stat -c %g /dev/kvm 2>/dev/null || true)
[ -n "$kvm_gid" ] || { echo "/dev/kvm fehlt – Emulator braucht KVM"; exit 1; }

if docker ps -q --filter name='^elm-emu$' | grep -q .; then
  running=$(docker inspect elm-emu --format '{{index .Config.Labels "elm.avd"}}')
  if [ "$running" = "$avd" ]; then echo "Läuft schon ($avd)."; exit 0; fi
  if [ -z "$running" ]; then echo "Ein Container elm-emu von Hand läuft – erst docker stop elm-emu"; exit 1; fi
  echo "Anderes Profil läuft ($running) – erst $(dirname "$0")/stop.sh"; exit 1
fi
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -q -t "$IMAGE" . >/dev/null
mkdir -p "$DATA"
docker rm elm-emu >/dev/null 2>&1 || true   # beendeten Container (Protokoll) vom letzten Lauf entfernen

snap="-no-snapshot-load"; [ "$cold" = 0 ] && snap=""
t0=$(date +%s)
docker run -d --name elm-emu --label elm.avd="$avd" \
  --device /dev/kvm --user "$(id -u):$kvm_gid" \
  --network host --cpu-shares 256 \
  -v "$SDK:/sdk" -v "$DATA:/h" \
  -e ANDROID_HOME=/sdk -e ANDROID_SDK_ROOT=/sdk -e HOME=/h -e ANDROID_USER_HOME=/h/.android \
  -e ANDROID_AVD_HOME=/h/.android/avd -e JAVA_TOOL_OPTIONS=-Duser.home=/h \
  -e ANDROID_EMULATOR_WAIT_TIME_BEFORE_KILL=60 \
  -e AVD="$avd" -e MEM="$mem" -e SNAP="$snap" \
  "$IMAGE" sh -c '
set -e
A=/h/.android/avd
mkdir -p $A
if [ ! -f $A/$AVD.ini ]; then
  echo "Lege AVD $AVD an"
  dev=pixel_6; [ "$AVD" = elm-radio ] && dev=medium_tablet
  echo no | /sdk/cmdline-tools/latest/bin/avdmanager create avd -n $AVD \
    -k "system-images;android-36;google_apis;x86_64" -d $dev >/dev/null
  if [ "$AVD" = elm-radio ]; then
    c=$A/$AVD.avd/config.ini
    sed -i -e "/^hw.lcd.width=/d" -e "/^hw.lcd.height=/d" -e "/^hw.lcd.density=/d" \
           -e "/^hw.initialOrientation=/d" -e "/^skin\./d" $c
    printf "hw.lcd.width=1024\nhw.lcd.height=600\nhw.lcd.density=160\nhw.initialOrientation=landscape\nskin.name=1024x600\nskin.path=_no_skin\n" >> $c
  fi
fi
exec /sdk/emulator/emulator -avd $AVD -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
  $SNAP -memory $MEM -cores 2 -ports 5560,5561'

echo -n "Warte auf Android ($avd)"
"$ADB" start-server >/dev/null 2>&1 || true
for _ in $(seq 1 120); do
  if [ "$("$ADB" -s emulator-5560 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
    echo; echo "Bereit nach $(( $(date +%s) - t0 )) s – adb -s emulator-5560, Helfer ./nav.sh"; exit 0
  fi
  docker ps -q --filter name='^elm-emu$' | grep -q . || { echo; echo "Container beendet:"; docker logs --tail 30 elm-emu 2>&1; exit 1; }
  echo -n .; sleep 2
done
echo; echo "Nicht bereit nach 240 s – docker logs elm-emu"; exit 1
