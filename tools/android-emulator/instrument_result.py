#!/usr/bin/env python3
"""Reads `am instrument -w -r` output on stdin: one line per test, for failures the first line of the message and
the line in the test class. Exit 0 only if at least one test passed and none failed."""
import sys

ok = fail = skip = 0
test = None
stack = []
inst_fail = None
other = []   # output that is no status line, e.g. a shell or runner error
for line in sys.stdin:
    line = line.rstrip("\r\n")
    # keys come sorted (… stack, stream, test): test= follows stack=, so stack is not cleared here
    if line.startswith("INSTRUMENTATION_STATUS: test="):
        test = line.split("=", 1)[1]
    elif line.startswith("INSTRUMENTATION_STATUS: stack="):
        stack = [line.split("=", 1)[1]]
    elif stack and not line.startswith("INSTRUMENTATION_"):
        stack.append(line)
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        code = int(line.split(": ")[1])
        if code == 0:
            ok += 1
            print("  OK      ", test)
        elif code in (-3, -4):
            skip += 1
            print("  SKIP    ", test)
        elif code in (-1, -2):
            fail += 1
            print("  FEHLER  ", test)
            print("           " + (stack[0][:300] if stack else ""))
            for w in list(dict.fromkeys(l.strip() for l in stack if "UiTest.java" in l))[:2]:
                print("           " + w)
        stack = []
    elif line.startswith("INSTRUMENTATION_RESULT: shortMsg=") or line.startswith("INSTRUMENTATION_FAILED"):
        inst_fail = line
    elif not line.startswith("INSTRUMENTATION_") and line.strip():
        other.append(line)
if ok + fail + skip == 0:
    print("  keine Tests gemeldet – Ausgabe von am instrument:")
    for l in other[:15]:
        print("           " + l[:300])
print(f"{ok} bestanden, {fail} fehlgeschlagen, {skip} übersprungen" + (f" – {inst_fail}" if inst_fail else ""))
sys.exit(0 if fail == 0 and ok > 0 and not inst_fail else 1)
