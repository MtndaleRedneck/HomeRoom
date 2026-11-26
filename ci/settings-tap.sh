#!/bin/sh
# Tap a Settings row. Look it up, scroll once and retry if unusable (missing or under the nav),
# and exit 1 if still unusable, so a bad lookup can never turn into a screenshot of the wrong screen.
# Lives in a checked-in file because the emulator action runs each script line in its own shell.
row="$1"
XY=$(python3 ci/ui-tap.py "$row") || {
  adb shell input swipe 540 1800 540 600 600
  sleep 2
  XY=$(python3 ci/ui-tap.py "$row") || { echo "settings row not tappable above the nav: $row"; exit 1; }
}
adb shell input tap $XY
