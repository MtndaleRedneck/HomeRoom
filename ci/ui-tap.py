#!/usr/bin/env python3
"""Print the center coordinates of the best UI node to tap for the given
text/content-description, for `adb shell input tap`.

Dumps the current UI hierarchy from the attached emulator via uiautomator.
Matching rules, in order:
  1. Ignore zero-area nodes (off-screen/scrap entries can carry the same
     text and previously produced a tap at 0,0).
  2. If a matching node or one of its ancestors is clickable="true", tap the
     center of the NEAREST clickable ancestor (row cells own the click in
     both RecyclerView cells and Compose semantics; the TextView inside is
     only a label).
  3. Otherwise tap the center of the largest-area matching node.

Exit 1 (no output) when no usable match is on screen, so the caller can
scroll and retry. Harness-only helper for the debug-interaction CI job.

Usage: ui-tap.py "Room tools"   ->   "412 903"
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def bounds_of(node):
    m = BOUNDS.match(node.get("bounds") or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1, y1, x2, y2)


def center(b):
    x1, y1, x2, y2 = b
    return (x1 + x2) // 2, (y1 + y2) // 2


def area(b):
    x1, y1, x2, y2 = b
    return (x2 - x1) * (y2 - y1)


def main():
    if len(sys.argv) != 2:
        sys.exit(2)
    target = sys.argv[1]
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui-dump.xml"],
                   check=True, capture_output=True)
    xml = subprocess.run(["adb", "shell", "cat", "/sdcard/ui-dump.xml"],
                         check=True, capture_output=True).stdout.decode("utf-8", "replace")
    root = ET.fromstring(xml)

    parent = {child: node for node in root.iter("node") for child in node}
    matches = []
    for node in root.iter("node"):
        text = node.get("text") or ""
        desc = node.get("content-desc") or ""
        if target in text or target in desc:
            b = bounds_of(node)
            if b is not None:
                matches.append((node, b))
    if not matches:
        # Diagnosability (de0a8d7 "Needs you" miss): keep the hierarchy that
        # produced the failure as a CI artifact - without it a miss is
        # undiagnosable from the log alone.
        safe = re.sub(r"[^A-Za-z0-9]+", "-", target).strip("-")[:40] or "target"
        try:
            with open("ui-dump-failure-" + safe + ".xml", "w") as f:
                f.write(xml)
        except OSError:
            pass
        sys.exit(1)

    # Prefer the nearest clickable ancestor of any match.
    best = None
    for node, b in matches:
        cur = node
        while cur is not None:
            if cur.get("clickable") == "true":
                cb = bounds_of(cur)
                if cb is not None:
                    cand = center(cb)
                    if best is None or area(cb) < area(best[1]):
                        best = (cand, cb)
                    break
            cur = parent.get(cur)
    if best is not None:
        print(*best[0])
        return

    # Fallback: largest matching node.
    node, b = max(matches, key=lambda nb: area(nb[1]))
    print(*center(b))


if __name__ == "__main__":
    main()
