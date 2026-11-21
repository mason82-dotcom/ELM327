#!/bin/sh
# Fault scenario for the ELM327 emulator (port 35001): writes control commands to the emulator's
# stdin (batch mode) in a fixed cycle, so the apps' "no stale value" rule can be checked by hand.
# Every phase is announced on stderr ("PHASE ...", visible with `docker logs -f elm327-faults`).
# Durations in seconds via FAULT_* environment variables; one cycle is about 95 s by default.
# FAULT_ONLY=<phase> holds one phase permanently instead of cycling (e.g. RPM_TIMEOUT for a screenshot).
set -u
PHASES="NORMAL RPM_NO_DATA RPM_TIMEOUT RPM_CAN_ERROR ENGINE_OFF ADAPTER_SILENT"
announce() { echo "$(date +%H:%M:%S) PHASE $1 ($2): $3" >&2; }
send() { echo "$1"; }

# Watchdog: when a client drops the connection during an echo, ELM327-emulator 4.0.0 loses its server thread
# (BrokenPipeError) but the process keeps running, because this script still feeds its stdin – the port stays dead
# and the restart loop never fires. Every 2 s the emulator checks its own thread and exits if it is gone; the
# next write here then ends this script (SIGPIPE) and the container loop restarts both within about 3 s.
# An if statement runs silently; an expression would print its value into the log every 2 s.
watchdog() { send "if not emulator.thread.is_alive(): os._exit(3)"; }
# Sleeps $1 seconds (fractions allowed) and runs the watchdog every 2 s meanwhile.
wait_s() {
  whole=${1%%.*}; whole=${whole:-0}
  i=0
  while [ "$i" -lt "$whole" ]; do
    sleep 1; i=$((i + 1))
    [ $((i % 2)) -eq 0 ] && watchdog
  done
  case "$1" in *.*) sleep "0.${1#*.}" ;; esac
}

# Each phase sets the emulator state from scratch, so any phase can follow any other.
apply() {
  send "delay 0"; send "emulator.answer = {}"; send "scenario car"
  case "$1" in
    NORMAL) desc="alle Werte gültig" ;;
    RPM_NO_DATA) desc="01 0C antwortet NO DATA – nur Drehzahl '–', Rest bleibt"
      send "emulator.answer['RPM'] = '<writeln>NO DATA</writeln>'" ;;
    RPM_TIMEOUT) desc="01 0C antwortet erst nach 3 s – Drehzahl sofort '–', Rest bleibt"
      send "emulator.answer['RPM'] = '<exec>time.sleep(3)</exec><writeln>41 0C 1A F8</writeln>'" ;;
    RPM_CAN_ERROR) desc="01 0C antwortet CAN ERROR – Drehzahl '–'"
      send "emulator.answer['RPM'] = '<writeln>CAN ERROR</writeln>'" ;;
    ENGINE_OFF) desc="Zündung an, Motor aus (Ircama 'engineoff') – alle Motorwerte '–'"
      send "scenario engineoff" ;;
    ADAPTER_SILENT) desc="Adapter antwortet nicht mehr (jede Antwort 6 s zu spät) – alle Werte '–', App verbindet neu"
      # A delay instead of pausing the emulator thread: a paused emulator kept a closed client socket after resuming
      # and accepted no new connection (found on brix-node 07.11.2026).
      send "delay 6" ;;
  esac
}

# Duration of a phase in seconds; an invalid FAULT_* value falls back to the default and is logged.
num() {
  case "$1" in
    ''|*[!0-9.]*|*.*.*|.*|*.) [ -n "$1" ] && echo "invalid $3='$1' (seconds, e.g. 15 or 2.5) – using $2" >&2; echo "$2" ;;
    *) echo "$1" ;;
  esac
}
duration() {
  case "$1" in
    NORMAL) num "${FAULT_NORMAL:-}" 20 FAULT_NORMAL ;;
    RPM_NO_DATA) num "${FAULT_NO_DATA:-}" 15 FAULT_NO_DATA ;;
    RPM_TIMEOUT) num "${FAULT_TIMEOUT:-}" 15 FAULT_TIMEOUT ;;
    RPM_CAN_ERROR) num "${FAULT_CAN_ERROR:-}" 10 FAULT_CAN_ERROR ;;
    ENGINE_OFF) num "${FAULT_ENGINE_OFF:-}" 15 FAULT_ENGINE_OFF ;;
    ADAPTER_SILENT) num "${FAULT_SILENT:-}" 20 FAULT_SILENT ;;
  esac
}

# An unknown FAULT_ONLY keeps the port usable: the cycle runs and the log says why.
case " $PHASES " in
  *" ${FAULT_ONLY:-} "*) ;;
  *) [ -n "${FAULT_ONLY:-}" ] && echo "$(date +%H:%M:%S) unknown FAULT_ONLY '$FAULT_ONLY' (phases: $PHASES) – running the cycle" >&2
     FAULT_ONLY= ;;
esac

sleep 2
send "import os"
if [ -n "${FAULT_ONLY:-}" ]; then
  apply "$FAULT_ONLY"
  announce "$FAULT_ONLY" "fest" "$desc"
  # Keep stdin of the emulator open: it ends batch mode when this script exits.
  while :; do wait_s 3600; done
fi
while :; do
  for p in $PHASES; do
    apply "$p"
    s=$(duration "$p")
    announce "$p" "$s s" "$desc"
    wait_s "$s"
  done
done
