#!/usr/bin/env bash
# Startet Anzeige, Simulator und Emulator; noVNC zeigt den Emulator im Browser.
set -u
export ANDROID_SDK_ROOT=/sdk ANDROID_HOME=/sdk ANDROID_AVD_HOME=/data/avd HOME=/data/home DISPLAY=:1
mkdir -p "$ANDROID_AVD_HOME" "$HOME"
# Docker vergibt ohne IPv6 im Daemon kein ::1 auf lo; der Emulator verbindet Modem und netsim (WLAN des Gastes) aber über
# ::1 – sonst hat Android kein Netz (ENETUNREACH, nur lo/dummy0). Gefunden 05.11.2026. Braucht cap NET_ADMIN (nur eigener Netz-NS).
ip -6 addr show dev lo | grep -q '::1' || ip -6 addr add ::1/128 dev lo || echo "WARNUNG: ::1 konnte nicht gesetzt werden" >&2
# Dazu eine private ULA-Adresse: glibc liefert bei AI_ADDRCONFIG IPv6-Ergebnisse (auch für "::1") nur, wenn eine
# nicht-lokale IPv6-Adresse existiert – sonst „address resolution failed for ::1“.
ip -6 addr show dev lo | grep -q 'fd00:e1::1' || ip -6 addr add fd00:e1::1/128 dev lo || true
Xvfb :1 -screen 0 ${SCREEN:-1280x1000x24} -nolisten tcp >/tmp/xvfb.log 2>&1 &
sleep 1
fluxbox >/tmp/fluxbox.log 2>&1 &
# LAN-only Dienst ohne Passwort (Heimnetz-Vorgabe); erreichbar nur über die an 192.168.178.50 gebundene Portfreigabe.
x11vnc -display :1 -forever -shared -nopw -localhost -quiet >/tmp/x11vnc.log 2>&1 &
websockify --web /usr/share/novnc 6080 127.0.0.1:5900 >/tmp/novnc.log 2>&1 &
python3 /opt/emu/elm327_lab_simulator.py --bind 127.0.0.1 --port 35000 >/tmp/simulator.log 2>&1 &
if [ ! -d "$ANDROID_AVD_HOME/obd.avd" ]; then
  echo no | /sdk/cmdline-tools/latest/bin/avdmanager -s create avd -n obd \
    -k "system-images;android-37.2;google_apis_ps16k;x86_64" -d pixel_7 >/tmp/avd.log 2>&1 \
    || { echo "AVD konnte nicht angelegt werden:"; cat /tmp/avd.log; sleep 300; exit 1; }
fi
# Nach jedem Boot: 3-Tasten-Navigation. Die Gestennavigation lässt surfaceflinger per RegionSamplingThread Bildinhalte
# zurücklesen; ohne GPU bricht das im 37.2-Abbild ab (GoldfishMapper::readFromHost) und startet Android neu.
# Mit 3 Tasten: 0 Abstürze in 6 min (05.11.2026). Außerdem Sperrbildschirm aus, Display 30 min an.
postboot() {
  A=/sdk/platform-tools/adb
  while true; do
    $A wait-for-device >/dev/null 2>&1
    until [ "$($A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 5; done
    sleep 10
    $A shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton >/dev/null 2>&1
    $A shell locksettings set-disabled true >/dev/null 2>&1
    $A shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
    $A shell input keyevent 82 >/dev/null 2>&1
    echo "$(date -Is) postboot erledigt" >>/tmp/postboot.log
    # bis zum nächsten Boot (Emulator-Neustart) warten
    while [ "$($A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 30; done
  done
}
postboot &

# Emulator-Neustart bei Absturz; Snapshot aus, damit jeder Start sauber ist.
# -feature -GLDMA: Mit gfxstream/SwiftShader (ohne GPU) meldet der Host sonst ANDROID_EMU_read_color_buffer_dma; surfaceflinger
#   im 37.2-Abbild bricht dann bei jedem Zurücklesen von Bildinhalten ab ('Assertion failed: !rcEnc->featureInfo()->
#   hasReadColorBufferDma'), worauf Android das ganze System neu startet (gefunden 05.11.2026: 7 Neustarts in 3,5 min).
# -feature -Uwb: der UWB-HAL sucht /dev/uwb0 und stürzt sonst alle 5 s ab (nur Protokollrauschen).
while true; do
  # Sperrdateien eines nicht sauber beendeten Laufs (Container neu erstellt) verhindern sonst den Start.
  rm -f "$ANDROID_AVD_HOME"/obd.avd/*.lock
  /sdk/emulator/emulator -avd obd -gpu ${EMU_GPU:-swiftshader_indirect} -accel on -memory ${EMU_RAM_MB:-4096} -cores ${EMU_CORES:-3} \
    -no-audio -no-boot-anim -no-snapshot -skin 1080x2400 \
    ${EMU_FEATURES:--feature -GLDMA -feature -Uwb} >/tmp/emulator.log 2>&1
  echo "$(date -Is) Emulator beendet, Neustart in 30 s" >>/tmp/restarts.log
  sleep 30
done
