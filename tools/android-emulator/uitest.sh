#!/usr/bin/env bash
# UI-Tests des Dashboards im laufenden Emulator gegen den ELM327-Simulator.
#   tools/android-emulator/start.sh [radio]       # vorher: Telefon oder Radio
#   tools/android-emulator/uitest.sh [--no-build] [Testname]
# Baut App und Testpaket (tools/android-sdk/gradle.sh), startet proxy.py hot,dtc auf Port 35010, installiert beide
# Pakete und führt die Tests aus. Exit 0 nur, wenn alle Tests bestanden. Simulator: tools/elm327-emulator (:35000).
set -euo pipefail
repo="$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)"
ADB="${ANDROID_EMU_SDK:-$HOME/android-emu}/platform-tools/adb -s emulator-5560"
HOST="${ELM_HOST:-192.168.178.50}"
PROXY_PORT=35010
build=1 only=""
for a in "$@"; do
  case "$a" in
    --no-build) build=0 ;;
    -*) echo "Unbekannt: $a"; exit 2 ;;
    *) only="$a" ;;
  esac
done

[ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] \
  || { echo "Emulator läuft nicht – erst tools/android-emulator/start.sh"; exit 1; }
# ATZ bis zum Prompt (wie die Probe im Test, hier vom Host aus); Ende der Verbindung vorher = Fehler
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
  "$repo/tools/android-sdk/gradle.sh" "$repo" -q :dashboard:assembleDebug :dashboard:assembleDebugAndroidTest
fi
apk="$repo/dashboard/build/outputs/apk/debug/dashboard-debug.apk"
test_apk="$repo/dashboard/build/outputs/apk/androidTest/debug/dashboard-debug-androidTest.apk"

proxy_log=$(mktemp)
python3 "$repo/tools/elm327-emulator/proxy.py" hot,dtc --listen "$HOST:$PROXY_PORT" --upstream "$HOST:35000" \
  2>"$proxy_log" &
proxy=$!
trap 'kill $proxy 2>/dev/null || true; rm -f "$proxy_log"' EXIT
# bereit erst, wenn der Proxy über den Port antwortet (nicht nur, wenn der Prozess noch lebt)
for _ in $(seq 1 20); do answers "$HOST" "$PROXY_PORT" 2>/dev/null && break; sleep 0.25; done
if ! kill -0 $proxy 2>/dev/null || ! answers "$HOST" "$PROXY_PORT" 2>/dev/null; then
  echo "Proxy auf $HOST:$PROXY_PORT startet nicht:"; cat "$proxy_log"; exit 1
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
install "$apk" de.roman.obddashboard
install "$test_apk" de.roman.obddashboard.test
$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell settings put global hide_error_dialogs 1 >/dev/null
# Ein schon offener ANR-Dialog (z. B. aus einem alten Snapshot) nimmt der App den Fokus: mit „Wait“ schließen.
# erst lesen, dann suchen: grep -q in der Pipe beendet adb vorzeitig (SIGPIPE, mit pipefail „nicht gefunden“)
focus=$($ADB shell dumpsys window | grep 'mCurrentFocus=' || true)
if echo "$focus" | grep -q 'Application Not Responding'; then
  echo "Systemdialog „reagiert nicht“ offen – schließe ihn"
  "$(dirname "$(readlink -f "$0")")/nav.sh" tap Wait >/dev/null || $ADB shell input keyevent KEYCODE_BACK
  sleep 2
fi
filter=(); [ -n "$only" ] && filter=(-e class "de.roman.obddashboard.DashboardUiTest#$only")
# -r: maschinenlesbar; je Test eine Zeile, bei Fehlern die erste Zeile der Meldung und die Testzeile
$ADB shell am instrument -w -r -e elmHost "$HOST" -e elmPort 35000 -e deadPort 35009 \
  -e proxyPort "$PROXY_PORT" "${filter[@]}" de.roman.obddashboard.test/androidx.test.runner.AndroidJUnitRunner \
  | tr -d '\r' | python3 -c '
import re, sys
ok = fail = skip = 0; test = None; stack = []; inst_fail = None
for line in sys.stdin:
    line = line.rstrip("\n")
    # Schlüssel kommen sortiert (… stack, stream, test): test= nach stack=, deshalb stack hier nicht leeren
    if line.startswith("INSTRUMENTATION_STATUS: test="):
        test = line.split("=", 1)[1]
    elif line.startswith("INSTRUMENTATION_STATUS: stack="):
        stack = [line.split("=", 1)[1]]
    elif stack and not line.startswith("INSTRUMENTATION_"):
        stack.append(line)
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        code = int(line.split(": ")[1])
        if code == 0: ok += 1; print("  OK      ", test)
        elif code in (-3, -4): skip += 1; print("  SKIP    ", test)
        elif code in (-1, -2):
            fail += 1; print("  FEHLER  ", test)
            first = stack[0][:300] if stack else ""
            where = list(dict.fromkeys(l.strip() for l in stack if "DashboardUiTest.java" in l))
            print("           " + first)
            for w in where[:2]: print("           " + w)
        stack = []
    elif line.startswith("INSTRUMENTATION_RESULT: shortMsg=") or line.startswith("INSTRUMENTATION_FAILED"):
        inst_fail = line
print(f"{ok} bestanden, {fail} fehlgeschlagen, {skip} übersprungen" + (f" – {inst_fail}" if inst_fail else ""))
sys.exit(0 if fail == 0 and ok > 0 and not inst_fail else 1)
'
