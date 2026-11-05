#!/usr/bin/env python3
# Herkunft: OpenNextion_ELM327_Interface/tools/elm327_lab_simulator.py (Stand 6a8e58e), unverändert übernommen.
"""Labor-only ELM327 TCP simulator. NO vehicle, Bluetooth, CAN or USB access."""
import argparse
import socket
import socketserver
import threading
import time
from pathlib import Path

# Standard Mode 01 supported PIDs: 05 coolant, 0C RPM, 0D speed.
RESPONSES = {
    "0100": "41 00 9F BB 80 01",  # 01/04/05/06/07/08/09/0B/0C/0D/0F/10/11/20
    "0120": "41 20 80 01 A0 01",  # 21/30/31/33/40
    "0140": "41 40 50 00 00 00",  # 42/44
    "0101": "41 01 00 07 25 00",  # MIL off, 0 DTC, monitors complete
    "0104": "41 04 4C",           # 29.8% load
    "0106": "41 06 80",           # STFT B1 0%
    "0107": "41 07 8A",           # LTFT B1 7.8125%
    "0108": "41 08 80",           # STFT B2 0%
    "0109": "41 09 87",           # LTFT B2 5.46875%
    "010B": "41 0B 28",           # MAP 40 kPa
    "010F": "41 0F 46",           # intake 30C
    "0110": "41 10 01 F4",        # MAF 5 g/s
    "0111": "41 11 20",           # throttle 12.55%
    "0133": "41 33 65",           # barometric 101 kPa
    "0142": "41 42 34 BC",        # ECU 13.500 V
    "0144": "41 44 80 00",        # target lambda 1.000
    "0121": "41 21 00 00",        # MIL distance
    "0130": "41 30 05",           # warmups since reset
    "0131": "41 31 01 2C",        # km since reset 300
    "020200": "42 02 00 00 00",  # freeze-frame DTC absent
    "0105": "41 05 82",       # 90 C
    "010C": "41 0C 13 48",    # 1234 rpm
    "010D": "41 0D 00",       # 0 km/h
    "06A2": "013\r0:46 A2 0B 24 00 00\r1:00 00 00 10 A2 0C 24\r"
             "2:00 00 00 00 00 10 00",  # real CAN: 19 bytes, padded
    "06A3": "013\r0:46 A3 0B 24 00 00\r1:00 00 00 10 A3 0C 24\r"
             "2:00 00 00 00 00 10 00",  # real CAN: 19 bytes, padded
    "06A4": "013\r0:46 A4 0B 24 00 00\r1:00 00 00 10 A4 0C 24\r"
             "2:00 00 00 00 00 10 00",  # real CAN: 19 bytes, padded
    "06A5": "013\r0:46 A5 0B 24 00 00\r1:00 00 00 10 A5 0C 24\r"
             "2:00 00 00 00 00 10 00",  # real CAN: 19 bytes, padded
    "06A6": "013\r0:46 A6 0B 24 00 00\r1:00 00 00 10 A6 0C 24\r"
             "2:00 00 00 00 00 10 00",  # real CAN: 19 bytes, padded
    "06A7": "013\r0:46 A7 0B 24 00 00\r1:00 00 00 10 A7 0C 24\r"
             "2:00 00 00 00 00 10 00",  # real CAN: 19 bytes, padded
    "03": "43 00",            # no stored DTC
    "07": "47 00",            # no pending DTC
    "0A": "4A 00",            # no permanent DTC
}
ADAPTER_COMMANDS = {"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATDPN"}
# Headerless indexed multi-frame responses from an ELM-style formatter.
# An indexed response is a separate complete sequence, NOT an ECU address.
DTC_FIXTURES = {
    "MULTI": {
        "03": "00A\r0:43 04 01 71 03 01 03\r1:02 03 03",
        "07": "47 00", "0A": "4A 00",
    },
    "TWO": {
        "03": "004\r0:43 01 01 74\r"
              "00A\r0:43 04 01 71 03 01 03\r1:02 03 03",
        "07": "47 00", "0A": "4A 00",
    },
    "TRUNCATED": {
        "03": "00A\r0:43 04 01 71 03 01 03", # Missing segment 1
        "07": "47 00", "0A": "4A 00",
    },
    "BAD_SEQUENCE": {
        "03": "00A\r0:43 04 01 71 03 01 03\r2:02 03 03",
        "07": "47 00", "0A": "4A 00",
    },
    # Real ELM327 shape: first frame carries 6 bytes, the last consecutive
    # frame is printed untrimmed with ISO 15765-2 padding (here 3x 00).
    # 10 payload bytes = P0171, P0301, P0302, P0303.
    "PADDED": {
        "03": "00A\r0:43 04 01 71 03 01\r1:03 02 03 03 00 00 00",
        "07": "47 00", "0A": "4A 00",
    },
}

# Fixed local transport scenarios, never arbitrary responses or destinations.
DTC_TRANSPORT_FIXTURES = {
    f"{action}_{mode}": (action, mode)
    for action in ("DELAY", "NO_PROMPT", "DISCONNECT")
    for mode in ("03", "07", "0A")
}
DTC_PROMPT_DELAY_SECONDS = 2.0  # below the firmware's 3.5 s DTC timeout


def dtc_transport_from_file(path: str | None, command: str) -> str | None:
    """Inspect at most 65 bytes; only nine named DTC transport fixtures exist.

    The selector is persistent until changed/removed. It never affects adapter
    setup or live PIDs and does not re-arm a scan on the ESP32 after reconnect.
    """
    if not path or command not in ("03", "07", "0A"):
        return None
    try:
        with open(path, "rb") as source:
            raw = source.read(65)
        if len(raw) > 64:
            return None
        value = raw.decode("ascii").strip().upper()
    except (OSError, UnicodeError):
        return None
    scenario = DTC_TRANSPORT_FIXTURES.get(value)
    return scenario[0] if scenario and scenario[1] == command else None


def answer_for(command: str, fault_pid: str | None = None, rpm_override: int | None = None,
               dtc_fixture: str | None = None) -> str:
    """Return only fixture responses, never execute or forward a command."""
    command = command.strip().upper()
    if command == "ATZ":
        return "ELM327 LAB SIM v0.1"
    if command == "ATDPN":
        return "A6"  # ISO 15765-4 11-bit CAN 500 kbit/s (simulated)
    if command in ADAPTER_COMMANDS:
        return "OK"
    if command == fault_pid and command in RESPONSES and command.startswith("01"):
        # Deliberately invalid PID response while keeping TCP connected.
        return "NO DATA"
    if command == "010C" and rpm_override is not None:
        raw = int(rpm_override) * 4
        return f"41 0C {(raw >> 8) & 0xFF:02X} {raw & 0xFF:02X}"
    if dtc_fixture in DTC_FIXTURES and command in ("03", "07", "0A"):
        return DTC_FIXTURES[dtc_fixture][command]
    if command in RESPONSES:
        return RESPONSES[command]
    return "?"


def fault_pid_from_file(path: str | None) -> str | None:
    """Read-only PID-specific fault switch; ignored when missing or invalid.

    The fault file is inspected per command, so it can be added or removed
    during one ongoing ESP32-to-Pi session without stopping the TCP server.
    This is strictly a test server; no command ever reaches a vehicle.
    """
    if not path:
        return None
    try:
        value = Path(path).read_text(encoding="ascii").strip().upper()
    except (OSError, UnicodeError):
        return None
    if len(value) == 4 and value.startswith("01") and value in RESPONSES:
        return value
    return None


def rpm_from_file(path: str | None) -> int | None:
    """Explicit LAB-only RPM override, reloaded on every simulator request."""
    if not path:
        return None
    try:
        value = Path(path).read_text(encoding="ascii").strip()
    except (OSError, UnicodeError):
        return None
    if not value.isascii() or not value.isdecimal() or len(value) > 4:
        return None
    rpm = int(value)
    return rpm if 0 <= rpm <= 7000 else None


def dtc_fixture_from_file(path: str | None) -> str | None:
    if not path:
        return None
    try:
        value = Path(path).read_text(encoding="ascii").strip().upper()
    except (OSError, UnicodeError):
        return None
    # Only recognized local fixtures; no arbitrary payload pass-through.
    return value if value in DTC_FIXTURES else None


class ProtocolSearch:
    """ATSP0 behaviour of a real ELM327 within one TCP session.

    Until the first OBD request the protocol is unknown: ATDPN reports "A0".
    That first request prints "SEARCHING..." and takes noticeably longer.
    ATZ and ATSP0 forget the detected protocol again.
    """
    def __init__(self):
        self.known = False

    def respond(self, command: str, reply: str, search_delay: float = 0.0) -> str:
        if command in ("ATZ", "ATSP0"):
            self.known = False
            return reply
        if command == "ATDPN":
            return reply if self.known else "A0"
        if command.startswith("AT") or reply == "?" or self.known:
            return reply
        self.known = True
        if search_delay > 0:
            time.sleep(search_delay)
        return "SEARCHING...\r" + reply


class ElmCrHandler(socketserver.BaseRequestHandler):
    """ELM327-compatible CR terminator; one command per prompt."""
    def handle(self):
        self.request.settimeout(35)
        echo = True
        missing_prompt = False
        protocol = ProtocolSearch()
        buf = bytearray()
        peer = self.client_address[0]
        self.server.take_over(self.request)
        print(f"[LAB] client {peer} connected", flush=True)
        try:
            while True:
                data = self.request.recv(64)
                if not data:
                    return
                for ch in data:
                    if ch in (10, 13):
                        if not buf:
                            continue
                        cmd = buf.decode("ascii", errors="replace").upper().strip()
                        buf.clear()
                        if not cmd:
                            continue
                        if missing_prompt:
                            # Also covers pipelined commands in the same recv.
                            print("[LAB] REJECT request after withheld DTC prompt", flush=True)
                            return
                        print(f"[LAB] RX {cmd}", flush=True)
                        fault_pid = fault_pid_from_file(self.server.fault_file)
                        rpm_override = rpm_from_file(self.server.rpm_file)
                        fixture = dtc_fixture_from_file(self.server.dtc_file)
                        transport = dtc_transport_from_file(
                            getattr(self.server, "dtc_transport_file", None), cmd)
                        reply = protocol.respond(
                            cmd, answer_for(cmd, fault_pid, rpm_override, fixture),
                            getattr(self.server, "search_delay", 0.0))
                        if fixture and cmd in ("03", "07", "0A"):
                            print(f"[LAB] DTC FIXTURE {fixture} for {cmd}", flush=True)
                        if reply == "NO DATA" and fault_pid == cmd:
                            print(f"[LAB] INJECT NO DATA {cmd} (TCP remains open)", flush=True)
                        if reply == "?":
                            print(f"[LAB] REJECT {cmd}", flush=True)
                        if transport:
                            print(f"[LAB] DTC TRANSPORT {transport} for {cmd}", flush=True)
                        if transport == "DISCONNECT":
                            return
                        reply_delay = getattr(self.server, "reply_delay", 0.0)
                        if reply_delay > 0:
                            time.sleep(reply_delay)
                        payload = ((cmd + "\r") if echo else "") + reply + "\r"
                        if transport in ("DELAY", "NO_PROMPT"):
                            self.request.sendall(payload.encode("ascii"))
                            if transport == "DELAY":
                                time.sleep(DTC_PROMPT_DELAY_SECONDS)
                                self.request.sendall(b">")
                            else:
                                missing_prompt = True
                        else:
                            self.request.sendall((payload + ">").encode("ascii"))
                        if cmd == "ATZ":
                            echo = True
                        elif cmd == "ATE0":
                            echo = False
                    elif len(buf) < 32:
                        buf.append(ch)
                    else:
                        print("[LAB] oversize command rejected", flush=True)
                        if missing_prompt:
                            return
                        self.request.sendall(b"?\r>")
                        buf.clear()
        except (TimeoutError, ConnectionError, OSError):
            pass
        finally:
            self.server.release(self.request)
            print(f"[LAB] client {peer} disconnected", flush=True)


class Server(socketserver.ThreadingTCPServer):
    """One active client, newest wins.

    An ESP32 that resets (flash, power cycle) never closes its old TCP
    session. A strictly sequential server then sat up to 35 s on the dead
    socket while the board's new connection waited in the backlog and its
    ATZ timed out. Replacing the previous client keeps one-command-at-a-time
    semantics and lets the reconnected board in immediately.
    """
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._lock = threading.Lock()
        self._active = None

    def take_over(self, sock):
        with self._lock:
            previous, self._active = self._active, sock
        if previous is not None:
            print("[LAB] new client replaces previous session", flush=True)
            try:
                previous.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def release(self, sock):
        with self._lock:
            if self._active is sock:
                self._active = None


def main():
    parser = argparse.ArgumentParser(description="Read-only ELM327 LAB server; no vehicle access")
    parser.add_argument("--bind", default="127.0.0.1",
                        help="default loopback only; use Pi LAN IP explicitly for ESP32 bench tests")
    parser.add_argument("--port", type=int, default=35000)
    parser.add_argument("--fault-file", default=None,
                        help="optional local file containing a permitted Mode 01 PID; return NO DATA for it")
    parser.add_argument("--rpm-file", default=None,
                        help="optional local file with simulated RPM from 0..7000 (no vehicle access)")
    parser.add_argument("--dtc-file", default=None,
                        help="optional local DTC fixture selector: MULTI, TWO, TRUNCATED, BAD_SEQUENCE, PADDED")
    parser.add_argument("--dtc-transport-file", default=None,
                        help="optional persistent LAB selector: DELAY_03, NO_PROMPT_07, "
                             "DISCONNECT_0A (each action supports 03/07/0A)")
    parser.add_argument("--search-delay", type=float, default=1.5,
                        help="seconds the first OBD request takes after ATZ/ATSP0 (ATSP0 protocol search)")
    parser.add_argument("--reply-delay", type=float, default=0.04,
                        help="seconds before every reply; ~0.03-0.06 is typical for CAN via WLAN ELM327")
    args = parser.parse_args()
    if not 0.0 <= args.reply_delay <= 2.0:
        parser.error("--reply-delay must be between 0 and 2 seconds")
    if not 0.0 <= args.search_delay <= 10.0:
        parser.error("--search-delay must be between 0 and 10 seconds")
    with Server((args.bind, args.port), ElmCrHandler) as server:
        server.fault_file = args.fault_file
        server.rpm_file = args.rpm_file
        server.dtc_file = args.dtc_file
        server.dtc_transport_file = args.dtc_transport_file
        server.search_delay = args.search_delay
        server.reply_delay = args.reply_delay
        print(f"[LAB] ELM327 simulator listening on {args.bind}:{args.port}", flush=True)
        if server.fault_file:
            print("[LAB] PID fault injection enabled (inactive until file exists)", flush=True)
        if server.rpm_file:
            print("[LAB] RPM fixture override enabled (inactive until file exists)", flush=True)
        if server.dtc_file:
            print("[LAB] DTC fixture selector enabled (inactive until file exists)", flush=True)
        if server.dtc_transport_file:
            print("[LAB] DTC transport fixtures enabled; persistent until selector removed", flush=True)
        print("[LAB] No vehicle/OBD adapter required; Ctrl+C to stop", flush=True)
        try:
            server.serve_forever(poll_interval=0.2)
        except KeyboardInterrupt:
            print("[LAB] stopped", flush=True)


if __name__ == "__main__":
    main()

