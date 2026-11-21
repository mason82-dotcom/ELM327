#!/usr/bin/env bash
# UI-Tests der Monitor-App und des Dashboards im laufenden Emulator gegen den ELM327-Simulator.
#   tools/android-emulator/start.sh [radio]                        # vorher: Telefon oder Radio
#   tools/android-emulator/uitest.sh [--no-build] [app|dashboard] [Testname]   # ohne Angabe: beide Apps
# Baut Apps und Testpakete (tools/android-sdk/gradle.sh), startet proxy.py hot,dtc auf Port 35010 mit Protokoll
# aller Anfragen, installiert, führt die Tests aus und prüft danach im Protokoll, dass keiner der im Terminal
# gesperrten Befehle den Adapter erreicht hat. Exit 0 nur, wenn alles bestanden. Simulator: tools/elm327-emulator.
set -euo pipefail
here="$(dirname "$(readlink -f "$0")")"
repo="$(cd "$here/../.." && pwd)"
ADB="${ANDROID_EMU_SDK:-$HOME/android-emu}/platform-tools/adb -s emulator-5560"
HOST="${ELM_HOST:-192.168.178.50}"
PROXY_PORT=35010
# Im Terminal-Test eingegebene, gesperrte Befehle: einzige Liste, geht als Argument "blocked" an MonitorUiTest
# (',' trennt, '_' steht für ein Leerzeichen – adb gibt keine Leerzeichen oder '|' unverändert an die Geräte-Shell)
# und dient danach der Prüfung des Proxy-Protokolls.
# Mode 04 und 14 (Fehler löschen), 2E (Daten schreiben), ATSH7E1 (Header nur im ATF-Ablauf, der hier nicht läuft).
BLOCKED="04,14_FF_00,2E_F1_90_00,ATSH7E1"
build=1 modules="app dashboard" only=""
for a in "$@"; do
  case "$a" in
    --no-build) build=0 ;;
    app|dashboard) modules="$a" ;;
    -*) echo "Unbekannt: $a"; exit 2 ;;
    *) only="$a" ;;
  esac
done
[ -n "$only" ] && [ "$modules" = "app dashboard" ] && { echo "Testname nur zusammen mit app oder dashboard"; exit 2; }

[ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] \
  || { echo "Emulator läuft nicht – erst tools/android-emulator/start.sh"; exit 1; }
# ATZ bis zum Prompt (wie ElmTest.answers im Test, hier vom Host aus); Ende der Verbindung vorher = Fehler
answers() {
  python3 - "$1" "$2" <<'EOF'
import socket, sys
s = socket.create_connection((sys.argv[1], int(sys.argv[2])), 3); s.settimeout(5); s.sendall(b"ATZ\r"); b = b""
while b">" not in b:
    d = s.recv(256)
    if not d: sys.exit(1)
    b += d
EOF
}
answers "$HOST" 35000 2>/dev/null || { echo "ELM327-Simulator $HOST:35000 antwortet nicht – tools/elm327-emulator"; exit 1; }

if [ "$build" = 1 ]; then
  tasks=(); for m in $modules; do tasks+=(":$m:assembleDebug" ":$m:assembleDebugAndroidTest"); done
  "$repo/tools/android-sdk/gradle.sh" "$repo" -q "${tasks[@]}"
fi

proxy_err=$(mktemp) requests=$(mktemp)
python3 "$repo/tools/elm327-emulator/proxy.py" hot,dtc --listen "$HOST:$PROXY_PORT" --upstream "$HOST:35000" \
  --log "$requests" 2>"$proxy_err" &
proxy=$!
trap 'kill $proxy 2>/dev/null || true; rm -f "$proxy_err" "$requests"' EXIT
# bereit erst, wenn der Proxy über den Port antwortet (nicht nur, wenn der Prozess noch lebt)
for _ in $(seq 1 20); do answers "$HOST" "$PROXY_PORT" 2>/dev/null && break; sleep 0.25; done
if ! kill -0 $proxy 2>/dev/null || ! answers "$HOST" "$PROXY_PORT" 2>/dev/null; then
  echo "Proxy auf $HOST:$PROXY_PORT startet nicht:"; cat "$proxy_err"; exit 1
fi

install() {   # $1 APK, $2 Paket
  local r; r=$($ADB install -r -g "$1" 2>&1) && return 0
  if echo "$r" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
    # anderer Signaturschlüssel (z. B. APK aus der CI): im Emulator neu installieren, App-Daten gehen dort verloren
    echo "$2 mit anderem Schlüssel installiert – im Emulator neu installieren"
    $ADB uninstall "$2" >/dev/null || true
    r=$($ADB install -r -g "$1" 2>&1) || { echo "$r"; return 1; }
  else
    echo "$r"; return 1
  fi
}

$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell settings put global hide_error_dialogs 1 >/dev/null
# Espresso: Animationen aus (sonst scheitern Klicks auf bewegten Views); alte Werte am Ende zurück
ANIM="window_animation_scale transition_animation_scale animator_duration_scale"
declare -A anim_old
for k in $ANIM; do
  anim_old[$k]=$($ADB shell settings get global "$k" | tr -d '\r')
  $ADB shell settings put global "$k" 0 >/dev/null
done
restore_anim() {
  for k in $ANIM; do
    v=${anim_old[$k]:-null}
    if [ "$v" = null ]; then $ADB shell settings delete global "$k" >/dev/null 2>&1 || true
    else $ADB shell settings put global "$k" "$v" >/dev/null 2>&1 || true; fi
  done
}
trap 'kill $proxy 2>/dev/null || true; rm -f "$proxy_err" "$requests"; restore_anim' EXIT
# Ein schon offener ANR-Dialog (z. B. aus einem alten Snapshot) nimmt der App den Fokus: mit „Wait“ schließen.
# erst lesen, dann suchen: grep -q in der Pipe beendet adb vorzeitig (SIGPIPE, mit pipefail „nicht gefunden“)
focus=$($ADB shell dumpsys window | grep 'mCurrentFocus=' || true)
if echo "$focus" | grep -q 'Application Not Responding'; then
  echo "Systemdialog „reagiert nicht“ offen – schließe ihn"
  "$here/nav.sh" tap Wait >/dev/null || $ADB shell input keyevent KEYCODE_BACK
  sleep 2
fi

result=0
for m in $modules; do
  case "$m" in
    app) pkg=de.roman.mercedesobdmonitor apk_name=app class=de.roman.mercedesobdmonitor.MonitorUiTest ;;
    dashboard) pkg=de.roman.obddashboard apk_name=dashboard class=de.roman.obddashboard.DashboardUiTest ;;
  esac
  echo "== $m"
  install "$repo/$m/build/outputs/apk/debug/$apk_name-debug.apk" "$pkg"
  install "$repo/$m/build/outputs/apk/androidTest/debug/$apk_name-debug-androidTest.apk" "$pkg.test"
  filter=(); [ -n "$only" ] && filter=(-e class "$class#$only")
  # -r: maschinenlesbar, ausgewertet je Test
  $ADB shell am instrument -w -r -e elmHost "$HOST" -e elmPort 35000 -e deadPort 35009 \
    -e proxyPort "$PROXY_PORT" -e blocked "$BLOCKED" "${filter[@]}" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" \
    | python3 "$here/instrument_result.py" || result=1
done

# Ende-zu-Ende über das Protokoll des Proxys (jede Anfrage der Apps, ohne Leerzeichen, Großbuchstaben)
echo "== Anfragen über den Proxy: $(wc -l <"$requests")"
python3 - "$requests" "$BLOCKED" <<'EOF' || result=1
import sys
lines = [l.replace(" ", "").strip().upper() for l in open(sys.argv[1], encoding="latin1")]
blocked = [c.replace("_", "").upper() for c in sys.argv[2].split(",")]
bad = 0
# 1) kein gesperrter Befehl erreichte den Adapter – auch nicht mit Anhang (z. B. "04 1" mit Antwortzahl)
for cmd in blocked:
    hits = [l for l in lines if l.startswith(cmd)]
    if hits:
        print(f"  FEHLER   gesperrter Befehl {cmd} hat den Adapter erreicht: {hits[:3]}"); bad = 1
# 2) Gegenprobe: ATIGN aus dem Terminal-Test (die App sendet es sonst nie) – ohne ihn hat 1) keine Aussage
terminal_ran = "ATIGN" in lines
if not bad:
    print("  OK       kein gesperrter Befehl gesendet (" + ", ".join(blocked) + "), ATIGN protokolliert"
          if terminal_ran else "  SKIP     Terminal-Test lief nicht – Prüfung der gesperrten Befehle ohne Aussage")
# 3) Verbinden liest keinen Fehlerspeicher: zwischen dem ATZ der Verbindung und der Marke ATDPN (MonitorUiTest
#    storedCodesAreReadOnlyOnRequest, vor dem Klick auf DTC) steht kein Mode 03
if "ATDPN" in lines:
    mark = lines.index("ATDPN")
    start = max((i for i in range(mark) if lines[i] == "ATZ"), default=0)
    if "03" in lines[start:mark]:
        print("  FEHLER   Mode 03 schon beim Verbinden gesendet (vor dem Klick auf DTC)"); bad = 1
    else:
        print("  OK       kein Mode 03 beim Verbinden (bis zur Marke ATDPN)")
else:
    print("  SKIP     DTC-Test der Monitor-App lief nicht – Prüfung „kein 03 beim Verbinden“ entfällt")
sys.exit(bad)
EOF
exit $result
