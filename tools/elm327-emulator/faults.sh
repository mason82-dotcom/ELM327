#!/bin/sh
# Fault scenario for the ELM327 emulator (port 35001): writes control commands to the emulator's
# stdin (batch mode) in a fixed cycle, so the apps' "no stale value" rule can be checked by hand.
# Every phase is announced on stderr ("PHASE ...", visible with `docker logs -f elm327-faults`).
# Durations in seconds via FAULT_* environment variables; one cycle is about 95 s by default.
set -u
phase() { echo "$(date +%H:%M:%S) PHASE $1 ($2 s): $3" >&2; }
send() { echo "$1"; }

send "scenario car"
sleep 2
while :; do
  phase NORMAL "${FAULT_NORMAL:-20}" "alle Werte gültig"
  send "emulator.answer = {}"; send "scenario car"
  sleep "${FAULT_NORMAL:-20}"

  phase RPM_NO_DATA "${FAULT_NO_DATA:-15}" "01 0C antwortet NO DATA – nur Drehzahl '–', Rest bleibt"
  send "emulator.answer['RPM'] = '<writeln>NO DATA</writeln>'"
  sleep "${FAULT_NO_DATA:-15}"

  phase RPM_TIMEOUT "${FAULT_TIMEOUT:-15}" "01 0C antwortet erst nach 3 s – Drehzahl sofort '–', Rest bleibt"
  send "emulator.answer['RPM'] = '<exec>time.sleep(3)</exec><writeln>41 0C 1A F8</writeln>'"
  sleep "${FAULT_TIMEOUT:-15}"

  phase RPM_CAN_ERROR "${FAULT_CAN_ERROR:-10}" "01 0C antwortet CAN ERROR – Drehzahl '–'"
  send "emulator.answer['RPM'] = '<writeln>CAN ERROR</writeln>'"
  sleep "${FAULT_CAN_ERROR:-10}"

  phase ENGINE_OFF "${FAULT_ENGINE_OFF:-15}" "Zündung an, Motor aus (Ircama 'engineoff') – alle Motorwerte '–'"
  send "emulator.answer = {}"; send "scenario engineoff"
  sleep "${FAULT_ENGINE_OFF:-15}"

  phase ADAPTER_SILENT "${FAULT_SILENT:-20}" "Adapter antwortet nicht mehr – alle Werte '–', App verbindet neu"
  send "scenario car"; send "emulator.threadState = emulator.THREAD.PAUSED"
  sleep "${FAULT_SILENT:-20}"
  send "emulator.threadState = emulator.THREAD.ACTIVE"
done
