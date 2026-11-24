#!/usr/bin/env python3
"""Print the center coordinates of the first visible UI node whose text or
content-description contains the argument, for `adb shell input tap`.

Dumps the current UI hierarchy from the attached emulator via uiautomator.
Exit 1 (no output) when the text is not on screen, so the caller can scroll
and retry. Harness-only helper for the debug-interaction CI job.

Usage: ui-tap.py "Room tools"   ->   "412 903"
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    if len(sys.argv) != 2:
        sys.exit(2)
    target = sys.argv[1]
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui-dump.xml"],
                   check=True, capture_output=True)
    xml = subprocess.run(["adb", "shell", "cat", "/sdcard/ui-dump.xml"],
                         check=True, capture_output=True).stdout.decode("utf-8", "replace")
    root = ET.fromstring(xml)
    for node in root.iter("node"):
        text = node.get("text") or ""
        desc = node.get("content-desc") or ""
        if target in text or target in desc:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
            if m:
                x1, y1, x2, y2 = (int(g) for g in m.groups())
                print((x1 + x2) // 2, (y1 + y2) // 2)
                return
    sys.exit(1)


if __name__ == "__main__":
    main()
