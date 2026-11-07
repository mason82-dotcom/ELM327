#!/usr/bin/env python3
"""Checks one full cycle of the fault emulator (port 35001) like an app would poll it.

Polls 01 0C (RPM) and 01 05 (coolant) with the app's timeout (1.8 s) and verifies that every fault
of faults.sh really occurs: NO DATA, timeout and CAN ERROR on RPM *while coolant still answers*
(only the faulty value may turn '-'), engine off, and a silent adapter. Exit code 0 = all seen.

    python3 tools/elm327-emulator/check_faults.py [host] [port] [seconds]   # default 192.168.178.50 35001 110
Standard library only.
"""
import socket
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.178.50"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 35001
DURATION = float(sys.argv[3]) if len(sys.argv) > 3 else 110
TIMEOUT = 1.8  # same as the apps' poll timeout


class Link:
    def __init__(self):
        self.sock = None

    def connect(self):
        self.close()
        self.sock = socket.create_connection((HOST, PORT), 3)
        self.sock.settimeout(TIMEOUT)
        for c in ("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"):
            self.query(c, 4.0)

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None

    def resync(self):
        """Wait briefly for a late prompt, like the apps' resync after a timeout."""
        self.sock.settimeout(1.5)
        buf = b""
        try:
            while b">" not in buf:
                d = self.sock.recv(256)
                if not d:
                    raise OSError("closed")
                buf += d
        except socket.timeout:
            pass
        finally:
            self.sock.settimeout(TIMEOUT)

    def query(self, cmd, timeout=TIMEOUT):
        self.sock.settimeout(timeout)
        self.sock.sendall(cmd.encode() + b"\r")
        buf = b""
        while b">" not in buf:
            d = self.sock.recv(256)  # socket.timeout propagates
            if not d:
                raise OSError("closed")
            buf += d
        self.sock.settimeout(TIMEOUT)
        return buf.decode(errors="replace").replace("\r", " ").replace(">", "").strip()


def classify(pid, text):
    t = text.upper()
    if "CAN ERROR" in t or "BUS INIT" in t or "BUS ERROR" in t or "UNABLE TO CONNECT" in t:
        return "LINK_ERROR"
    if "NO DATA" in t:
        return "NO_DATA"
    # ATS0 (as the apps send it) removes the spaces: "410C1AF8".
    return "VALUE" if ("41" + pid) in t.replace("SEARCHING...", "").replace(" ", "") else "OTHER"


def main():
    link = Link()
    seen = {}
    rpm_fault_coolant_ok = set()
    silent_both = 0
    t0 = time.time()
    while time.time() - t0 < DURATION:
        try:
            if link.sock is None:
                link.connect()
            row = {}
            for pid in ("0C", "05"):
                try:
                    row[pid] = classify(pid, link.query("01" + pid))
                except socket.timeout:
                    row[pid] = "TIMEOUT"
                    link.resync()
            for pid, outcome in row.items():
                seen.setdefault((pid, outcome), 0)
                seen[(pid, outcome)] += 1
            if row["0C"] != "VALUE" and row["05"] == "VALUE":
                rpm_fault_coolant_ok.add(row["0C"])
            if row["0C"] == "TIMEOUT" and row["05"] == "TIMEOUT":
                silent_both += 1
        except OSError:
            link.close()
            seen.setdefault(("link", "RECONNECT"), 0)
            seen[("link", "RECONNECT")] += 1
            time.sleep(1)
        time.sleep(0.3)

    print(f"Abfragen über {DURATION:.0f} s an {HOST}:{PORT}:")
    for (pid, outcome), n in sorted(seen.items()):
        print(f"  {pid:4} {outcome:11} {n}")
    checks = [
        ("Drehzahl gültig", ("0C", "VALUE") in seen),
        ("Drehzahl NO DATA, Kühlmittel gültig", "NO_DATA" in rpm_fault_coolant_ok),
        ("Drehzahl Zeitüberschreitung, Kühlmittel gültig", "TIMEOUT" in rpm_fault_coolant_ok),
        ("Drehzahl CAN ERROR, Kühlmittel gültig", "LINK_ERROR" in rpm_fault_coolant_ok),
        ("Motor aus: Kühlmittel ohne Wert", ("05", "NO_DATA") in seen or ("05", "LINK_ERROR") in seen),
        ("Adapter stumm: beide ohne Antwort", silent_both > 0),
    ]
    ok = True
    for name, passed in checks:
        print(("  OK    " if passed else "  FEHLT ") + name)
        ok &= passed
    link.close()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
