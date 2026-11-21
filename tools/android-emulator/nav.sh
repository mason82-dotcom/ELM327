#!/usr/bin/env bash
# Hilfen für den Dashboard-Test im Emulator: tap "<Text>", shot <datei>, rot 0|1 (0 hoch, 1 quer)
# tap liest die Oberfläche mit uiautomator; das hängt, solange Live-Werte laufen – dann mit adb shell input tap x y.
A="timeout 60 ${ANDROID_EMU_SDK:-$HOME/android-emu}/platform-tools/adb -s emulator-5560"
# Text wird wörtlich verglichen (grep -F), Punkte oder Klammern im Text sind also kein Muster.
tap() { local b; b=$($A exec-out uiautomator dump /dev/tty 2>/dev/null | grep -o 'text="[^"]*"[^>]*bounds="[^"]*"' \
  | grep -F "text=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"');
  [ -z "$b" ] && { echo "nicht gefunden: $1"; return 1; }; set -- $(echo "$b" | grep -o '[0-9]\+'); $A shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); }
shot() { $A exec-out screencap -p > "$1"; }
rot() { $A shell settings put system accelerometer_rotation 0; $A shell settings put system user_rotation "$1"; }
"$@"
