#!/usr/bin/env bash
# Emulator geordnet beenden und Snapshot speichern. Der Container bleibt für docker logs; start.sh entfernt ihn.
set -uo pipefail
docker ps -q --filter name='^elm-emu$' | grep -q . || { echo "Läuft nicht."; exit 0; }
# SIGTERM: der Emulator speichert den Snapshot (~6 s) und beendet sich; „adb emu kill“ kennt diese Version nicht.
t=$(date +%s)
docker stop -t 90 elm-emu >/dev/null
if docker logs elm-emu 2>&1 | tail -20 | grep -q "Saving snapshot 'default_boot' took"; then
  echo "Gestoppt nach $(( $(date +%s) - t )) s, Snapshot gespeichert."
else
  echo "Gestoppt nach $(( $(date +%s) - t )) s, ohne Snapshot – nächster Start kalt (docker logs elm-emu)."
fi
