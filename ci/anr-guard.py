#!/usr/bin/env python3
"""Harness-only: dismiss the emulator's "Pixel Launcher isn't responding" dialog.

That dialog dims the screen over the app, so screenshots and taps cannot pass.
Rules (no product code involved):
  * Only the exact dialog title "Pixel Launcher isn't responding" is handled,
    and only by tapping its own "Wait" button. Any other "isn't responding"
    dialog (including one for app.homeroom) fails the job at once.
  * At most 3 Wait taps per job (counter in /tmp/anr-guard-count); the 4th
    occurrence fails closed.
  * Before and after each tap a screenshot is saved (anr-before-*.png,
    anr-after-*.png) so the evidence shows what was on screen.
  * After handling, the dialog must be gone and app.homeroom must hold window
    focus; otherwise exit 1.
Usage: anr-guard.py <label>          before a capture/tap: exit 0 = clean (or recovered), exit 1 = fail
       anr-guard.py --check <label>  after a capture: NO tap, NO counter; exit 1 if any "isn't responding"
                                     dialog is on screen (the saved frame is then a failed capture)
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

MAX_TAPS = 3
COUNTER = "/tmp/anr-guard-count"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*a, **k):
    return subprocess.run(["adb", *a], capture_output=True, **k)


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/anr-dump.xml")
    out = adb("shell", "cat", "/sdcard/anr-dump.xml").stdout.decode("utf-8", "replace")
    return ET.fromstring(out)


def titles(root):
    return [(n.get("text") or "").replace("\u2019", "'") for n in root.iter("node")
            if "isn't responding" in (n.get("text") or "").replace("\u2019", "'")]


def title_nodes(root):
    return [n for n in root.iter("node")
            if "isn't responding" in (n.get("text") or "").replace("\u2019", "'")]


def shot(path):
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p").stdout)


def count():
    try:
        return int(open(COUNTER).read().strip())
    except (OSError, ValueError):
        return 0


def wait_center(root):
    # Only the system dialog's own Wait: package must be "android"; the aerr_wait
    # resource id is preferred, exact text "Wait" in package android is accepted.
    cands = [n for n in root.iter("node") if (n.get("package") or "") == "android"
             and ((n.get("resource-id") or "").endswith("aerr_wait") or (n.get("text") or "") == "Wait")]
    cands.sort(key=lambda n: 0 if (n.get("resource-id") or "").endswith("aerr_wait") else 1)
    for n in cands:
        if True:
            m = BOUNDS.match(n.get("bounds") or "")
            if m:
                x1, y1, x2, y2 = (int(g) for g in m.groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def focus_ok():
    out = adb("shell", "dumpsys", "window").stdout.decode("utf-8", "replace")
    line = next((l for l in out.splitlines() if "mCurrentFocus" in l), "")
    print("anr-guard focus:", line.strip())
    return "app.homeroom" in line


def check_only(label):
    found = titles(dump())
    if found:
        shot("anr-in-capture-%s.png" % label)
        print("anr-guard --check: dialog on screen after capture of %s: %s" % (label, found))
        return 1
    return 0


def main():
    if len(sys.argv) > 2 and sys.argv[1] == "--check":
        return check_only(re.sub(r"[^A-Za-z0-9]+", "-", sys.argv[2]).strip("-"))
    label = re.sub(r"[^A-Za-z0-9]+", "-", sys.argv[1] if len(sys.argv) > 1 else "x").strip("-")
    root = dump()
    found = titles(root)
    if not found:
        return 0
    pkgs = {n.get("package") for n in title_nodes(root)}
    if any(t != "Pixel Launcher isn't responding" for t in found) or pkgs != {"android"}:
        shot("anr-unexpected-%s.png" % label)
        print("anr-guard: unexpected ANR dialog:", found)
        return 1
    n = count()
    if n >= MAX_TAPS:
        shot("anr-exhausted-%s.png" % label)
        print("anr-guard: launcher ANR dialog again after %d Wait taps; failing closed" % MAX_TAPS)
        return 1
    xy = wait_center(root)
    shot("anr-before-%s-%d.png" % (label, n + 1))
    if xy is None:
        print("anr-guard: dialog has no Wait button")
        return 1
    adb("shell", "input", "tap", str(xy[0]), str(xy[1]))
    open(COUNTER, "w").write(str(n + 1))
    time.sleep(3)
    shot("anr-after-%s-%d.png" % (label, n + 1))
    if titles(dump()):
        print("anr-guard: dialog still present after Wait tap")
        return 1
    if not focus_ok():
        print("anr-guard: app.homeroom does not hold focus after dismissing")
        return 1
    print("anr-guard: launcher ANR dismissed with Wait (tap %d of max %d) before %s" % (n + 1, MAX_TAPS, label))
    return 0


if __name__ == "__main__":
    sys.exit(main())
