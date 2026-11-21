#!/usr/bin/env python3
"""Fault proxy between an app and the ELM327 emulator: rewrites or delays answers for UI checks.

The emulator's 'car' scenario only has normal values. This proxy sits in front of it and changes
complete answers (everything up to the ELM327 prompt '>') before they reach the app:

    hot    coolant 01 05 → 120 °C (temperature warning)
    weak   control module voltage 01 42 → 11.5 V and RPM 01 0C → 0 (battery warning, engine off)
    dtc    stored DTCs 03: "no codes" → P0171 and P0302 (M272 hints, explanations)
    slow   every answer 0.7 s late (or slow=<seconds>; loading states, poll timing)

Modes can be combined: hot,slow. The app connects to the proxy port instead of 35000.

    python3 tools/elm327-emulator/proxy.py hot                       # listens on 192.168.178.50:35010
    python3 tools/elm327-emulator/proxy.py weak,slow=1.5 --listen 0.0.0.0:35010 --upstream 127.0.0.1:35000

Standard library only. Read-only like the apps: requests pass through unchanged.
"""
import argparse
import re
import socket
import sys
import threading
import time

PROMPT = b">"


HEX = re.compile(r"[0-9A-F]+")


def _payload_start(h):
    """Index of the first data byte in a hex line: after an 11-bit (7E8 + PCI) or 29-bit (18DAF1xx + PCI) header."""
    if len(h) % 2 == 1 and h[0] == "7":
        return 5
    if h.startswith("18DA") and len(h) > 10:
        return 10
    return 0


def _set_bytes(fix):
    """Line rewrite: fix(data_hex) returns new data hex of the same length, or None to keep the line."""
    def apply(line):
        h = line.replace(" ", "")
        if not h or not HEX.fullmatch(h):
            return line
        start = _payload_start(h)
        new = fix(h[start:])
        if new is None:
            return line
        new = h[:start] + new
        # Same length: put the hex digits back into the original layout (spaces on or off).
        it = iter(new)
        return "".join(next(it) if c != " " else c for c in line)
    return apply


def _hot(d):  # coolant A0 - 40 = 120 °C
    return d[:4] + "A0" + d[6:] if d.startswith("4105") and len(d) >= 6 else None


def _weak(d):
    if d.startswith("4142") and len(d) >= 8:
        return d[:4] + "2CEC" + d[8:]  # 0x2CEC / 1000 = 11.5 V
    if d.startswith("410C") and len(d) >= 8:
        return d[:4] + "0000" + d[8:]  # 0 rpm
    return None


def _dtc(line):
    """Stored DTCs: an empty answer (43 00, maybe padded) becomes P0171 and P0302; the PCI byte follows the length."""
    h = line.replace(" ", "")
    if not h or not HEX.fullmatch(h):
        return line
    start = _payload_start(h)
    d = h[start:]
    if not (d.startswith("4300") and set(d[4:]) <= {"0"}):
        return line
    data = "430201710302"
    parts = []
    if start:  # CAN id (29 bit in pairs), then the PCI byte with the new length
        can_id = h[:start - 2]
        parts = [can_id] if len(can_id) == 3 else [can_id[k:k + 2] for k in range(0, len(can_id), 2)]
        parts.append("%02X" % (len(data) // 2))
    parts += [data[k:k + 2] for k in range(0, len(data), 2)]
    return (" " if " " in line else "").join(parts)


REWRITES = {"hot": _set_bytes(_hot), "weak": _set_bytes(_weak), "dtc": _dtc}


def parse_modes(text):
    rewrites, delay = [], 0.0
    for mode in filter(None, (m.strip() for m in text.split(","))):
        name, _, value = mode.partition("=")
        if name == "slow":
            delay = float(value) if value else 0.7
        elif name in REWRITES and not value:
            rewrites.append(REWRITES[name])
        else:
            raise argparse.ArgumentTypeError(f"unknown mode '{mode}' (hot, weak, dtc, slow[=s])")
    if not rewrites and not delay:
        raise argparse.ArgumentTypeError("no mode given")
    return rewrites, delay


def rewrite(answer, rewrites):
    """Applies every rewrite to each line of one answer; line ends and the prompt stay as they are."""
    if not rewrites:
        return answer
    parts = re.split(r"([\r\n>]+)", answer.decode("latin1"))
    for f in rewrites:
        parts = [p if k % 2 else f(p) for k, p in enumerate(parts)]
    return "".join(parts).encode("latin1")


def address(text):
    host, _, port = text.rpartition(":")
    return host, int(port)


def pipe(src, dst, rewrites=(), delay=0.0):
    """Copies src → dst; with rewrites or delay it forwards whole answers (up to and including the prompt)."""
    buf = b""
    try:
        while True:
            data = src.recv(4096)
            if not data:
                break
            if not rewrites and not delay:
                dst.sendall(data)
                continue
            buf += data
            while PROMPT in buf:
                end = buf.index(PROMPT) + 1
                answer, buf = buf[:end], buf[end:]
                if delay:
                    time.sleep(delay)
                dst.sendall(rewrite(answer, rewrites))
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.close()
            except OSError:
                pass


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("modes", type=parse_modes, help="hot, weak, dtc, slow[=seconds]; comma-separated")
    p.add_argument("--listen", type=address, default=("192.168.178.50", 35010), help="host:port for the app")
    p.add_argument("--upstream", type=address, default=("192.168.178.50", 35000), help="ELM327 emulator host:port")
    a = p.parse_args()
    rewrites, delay = a.modes
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(a.listen)
    srv.listen(4)
    print(f"proxy {a.listen[0]}:{a.listen[1]} → {a.upstream[0]}:{a.upstream[1]}", file=sys.stderr, flush=True)
    while True:
        client, peer = srv.accept()
        try:
            upstream = socket.create_connection(a.upstream, 3)
            upstream.settimeout(None)  # 3 s only for connecting; an idle app or a slow phase must not end the session
        except OSError as e:
            print(f"upstream unreachable: {e}", file=sys.stderr, flush=True)
            client.close()
            continue
        print(f"client {peer[0]}:{peer[1]}", file=sys.stderr, flush=True)
        threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
        threading.Thread(target=pipe, args=(upstream, client, rewrites, delay), daemon=True).start()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
