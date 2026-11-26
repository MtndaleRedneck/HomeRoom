#!/usr/bin/env bash
# FRESH-INSTALL, UPDATE-CHECK-ONLY smoke of the signed candidate (NOT migration, NOT the lock matrix). Harness-only; all seeded values SYNTHETIC.
# `adb install -r`, never uninstalled). The emulator image is google_apis (userdebug), so `adb root` can
# seed app-private prefs on the NON-debuggable published 1.7. All seeded values are SYNTHETIC.
set -euo pipefail
PKG=app.homeroom
UP=/tmp/up
EV=upgrade-evidence; mkdir -p "$EV"
BT=/tmp/bt/android-14
log() { echo "$*" | tee -a "$EV/proof.log"; }
fail() { log "ASSERT FAIL (harness/metadata): $*"; exit 1; }
pfail() { log "PRIVACY FINDING (inspect nodes before any defect label): $*"; exit 3; }

while [ "$(adb shell service check package 2>/dev/null | tr -d '\r')" != "Service package: found" ]; do sleep 5; done
adb root >/dev/null; adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]; do sleep 3; done
adb shell settings put global verifier_verify_adb_installs 0
adb shell cmd uimode night yes

pkginfo() { adb shell dumpsys package "$PKG" | tr -d '\r'; }
field() { pkginfo | sed -n "s/^ *$1=//p" | head -1; }
ANRN=0
clear_anr() { # Record-only: ANY "isn't responding" dialog saves screenshot/UI dump/logcat and STOPS the run with no result. Nothing is dismissed.
  adb shell uiautomator dump /sdcard/a.xml >/dev/null 2>&1 || return 0
  adb shell cat /sdcard/a.xml > "$EV/anr-probe.xml" 2>/dev/null || return 0
  grep -q "isn't responding" "$EV/anr-probe.xml" || return 0
  ANRN=$((ANRN+1))
  adb exec-out screencap -p > "$EV/anr-$ANRN.png"; cp "$EV/anr-probe.xml" "$EV/anr-$ANRN.xml"; adb logcat -d -b main,system -t 600 > "$EV/anr-$ANRN-logcat.txt" 2>/dev/null || true
  adb shell dumpsys activity processes 2>/dev/null | head -60 > "$EV/anr-$ANRN-procs.txt" || true
  log "ASSERT FAIL: system dialog 'isn't responding' present (recorded, not dismissed, NO RESULT); evidence anr-$ANRN.*"; exit 4
}
shot() { # screenshot + UI dump; a missing dump fails the proof (labels are asserted from it)
  clear_anr
  adb exec-out screencap -p > "$EV/$1.png"
  adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1 && adb pull /sdcard/w.xml "$EV/$1.xml" >/dev/null 2>&1 || fail "no UI dump for $1"
  [ -s "$EV/$1.xml" ] || fail "empty UI dump for $1"
  # Accessibility-service view: --compressed drops views NOT important for accessibility (e.g. under NO_HIDE_DESCENDANTS). The full dump above includes them, so it is NOT what TalkBack reads.
  adb shell uiautomator dump --compressed /sdcard/wa.xml >/dev/null 2>&1 && adb pull /sdcard/wa.xml "$EV/$1.a11y.xml" >/dev/null 2>&1 || fail "no compressed UI dump for $1"
  [ -s "$EV/$1.a11y.xml" ] || fail "empty compressed UI dump for $1"
}
has_label() { grep -qF "text=\"$2\"" "$EV/$1.xml" || fail "$1: label \"$2\" not on screen"; }
tap_text() { # tap a node by text via ci/ui-tap.py, retry (scroll once) before failing
  for i in 1 2 3 4; do
    clear_anr
    if xy=$(python3 ci/ui-tap.py "$1"); then adb shell input tap $xy; return 0; fi
    sleep 3
  done
  return 1
}
prefs_state() { # $1=tag; force-stopped state, synthetic values only
  adb shell am force-stop "$PKG"; sleep 2
  rm -rf "$EV/prefs-$1"; mkdir -p "$EV/prefs-$1"
  for f in homeroom_session.xml app_privacy.xml; do
    adb exec-out cat "/data/data/$PKG/shared_prefs/$f" > "$EV/prefs-$1/$f" || fail "cannot read $f ($1)"
  done
  python3 ci/prefs_map.py "$EV/prefs-$1/homeroom_session.xml" "$EV/prefs-$1/app_privacy.xml" > "$EV/map-$1.txt"
  (cd "$EV/prefs-$1" && sha256sum homeroom_session.xml app_privacy.xml) > "$EV/sha-$1.txt"
}
launch() { adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 30; adb shell input keyevent KEYCODE_WAKEUP; sleep 5; }

# 1) FRESH install of the exact signed candidate (no 1.7 stage; the in-place migration is proven by upgrade-proof run 45854445342)
adb install "$UP/HomeRoom-1.8-signed.apk" | tee -a "$EV/proof.log"
[ "$(field versionCode | cut -d' ' -f1)" = 13 ] || fail "installed versionCode is not 13"
BASE=$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)
adb pull "$BASE" /tmp/installed-base.apk >/dev/null
INST=$(sha256sum /tmp/installed-base.apk | cut -d' ' -f1)
[ "$INST" = "$SIGNED_SHA256" ] || fail "installed base.apk sha256 $INST != candidate $SIGNED_SHA256"
CERT=$("$BT/apksigner" verify --print-certs /tmp/installed-base.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$CERT" = "$TRUSTED_CERT" ] || fail "installed signer $CERT"
log "installed base.apk sha256=$INST signer=$CERT"
OWN=$(adb shell stat -c %u:%g "/data/data/$PKG" | tr -d '\r')
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' '<map>' \
  '<string name="server_url">https://seed.invalid</string>' '<string name="cookie">SYNTHETIC-seed-cookie</string>' \
  '<string name="csrf">SYNTHETIC-csrf</string>' '<string name="owner_name">Seed Owner</string>' \
  '<string name="last_room_id">seed-room-1</string>' '<string name="skipped_update_tag">none</string>' '</map>' > homeroom_session.xml
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 15; clear_anr; adb exec-out screencap -p > "$EV/first-launch.png"
adb shell am force-stop "$PKG"; sleep 2
OWN=$(adb shell stat -c %u:%g "/data/data/$PKG" | tr -d '\r')
adb shell mkdir -p "/data/data/$PKG/shared_prefs"
adb push homeroom_session.xml /data/local/tmp/homeroom_session.xml >/dev/null
adb shell "cp /data/local/tmp/homeroom_session.xml /data/data/$PKG/shared_prefs/homeroom_session.xml && chown $OWN /data/data/$PKG/shared_prefs/homeroom_session.xml && chmod 660 /data/data/$PKG/shared_prefs/homeroom_session.xml; chown $OWN /data/data/$PKG/shared_prefs; restorecon -R /data/data/$PKG/shared_prefs"


# UPDATE-CHECK PATH, targeted (no lock pref, no PIN). The app's manual check calls the GitHub releases API (NOT the seeded server). Result is shown in the Updates card:
# CHECKING -> button text "Checking..."; UP_TO_DATE -> "You're up to date." (auto-hides after ~5 s); CHECK_FAILED -> "Couldn't check. Try again." (persists); AVAILABLE -> "Version X is available." + button "Update X" (persists; the app then starts its own download/installer flow).
# This script never taps an Update/Install button. Poll the stock UI dump right after the tap to catch the transient states.
SEC6="$EV/signed-update.log"; : > "$SEC6"
s6() { echo "$*" | tee -a "$SEC6" | tee -a "$EV/proof.log" >/dev/null; }
resumed_ok() { adb shell dumpsys activity activities | tr -d '\r' | grep -E "topResumedActivity|mResumedActivity" | grep -q "$PKG/"; }
wait_resumed() { for i in $(seq 1 15); do resumed_ok && return 0; sleep 2; done; return 1; }
classify() { # $1=stock dump file -> state word
  if grep -qF "You're up to date." "$1"; then echo UP_TO_DATE
  elif grep -qF "Couldn't check. Try again." "$1"; then echo CHECK_FAILED
  elif grep -qF "is available." "$1"; then echo AVAILABLE
  elif grep -qF 'text="Checking..."' "$1"; then echo CHECKING
  elif grep -qF "The download didn't finish" "$1"; then echo DOWNLOAD_FAILED
  elif grep -qF 'text="Check for updates"' "$1"; then echo IDLE
  else echo UNKNOWN; fi
}
rm -f "$EV/app_privacy.xml"; adb shell "rm -f /data/data/$PKG/shared_prefs/app_privacy.xml" || true
adb logcat -b crash -c
# environment evidence (informational, not asserted): what the host sees as latest release and whether the emulator resolves/pings GitHub
HOSTTAG=$(curl -s -m 15 https://api.github.com/repos/MtndaleRedneck/HomeRoom/releases/latest | jq -r '.tag_name // "none"' 2>/dev/null || echo unknown)
s6 "ENV host view: releases/latest tag=$HOSTTAG (candidate is 1.8, so UP_TO_DATE is the expected app result if the emulator reaches GitHub)"
adb shell ping -c 2 -W 4 api.github.com > "$EV/emu-ping.txt" 2>&1 || true
s6 "ENV emulator ping api.github.com: $(tr '\n' ' ' < "$EV/emu-ping.txt" | cut -c1-200)"
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 12; adb shell input keyevent KEYCODE_WAKEUP; sleep 2
wait_resumed || fail "HomeRoom not resumed within 30 s"
shot upd-main
grep -qF 'content-desc="Rooms"' "$EV/upd-main.a11y.xml" || fail "precondition: main UI (content-desc Rooms) not visible in the unlocked app"
grep -qF 'text="HomeRoom is locked"' "$EV/upd-main.xml" && fail "precondition: lock cover present although no lock pref was seeded"
s6 "CONTROL PASS: unlocked main UI visible (content-desc Rooms), no cover"
tap_text "Settings" || fail "Settings tab not found"; sleep 3
for i in 1 2 3 4 5 6; do adb shell input swipe 360 1300 360 400 300; sleep 1; done
shot upd-pre
has_label upd-pre "Version 1.8"; has_label upd-pre "Check for updates"
PRE=$(classify "$EV/upd-pre.xml"); [ "$PRE" = IDLE ] || fail "precondition: updates card not IDLE before the tap (state $PRE)"
s6 "CONTROL PASS: Settings updates card shows Version 1.8 and the idle button before the tap (state $PRE)"
adb logcat -b main -b system -c   # NOT the crash buffer: it keeps everything since the baseline clear above
tap_text "Check for updates" || fail "Check for updates button not found"
T0=$(date +%s.%N); : > "$EV/upd-poll.log"
SEEN=""; LAST=""; TERM=""; REVERTED=0
for i in $(seq 1 30); do
  adb shell uiautomator dump /sdcard/p.xml >/dev/null 2>&1 && adb pull /sdcard/p.xml "$EV/upd-poll-$i.xml" >/dev/null 2>&1 || { echo "poll $i: dump failed" >> "$EV/upd-poll.log"; continue; }
  if grep -q "isn't responding" "$EV/upd-poll-$i.xml"; then adb exec-out screencap -p > "$EV/upd-anr-$i.png"; cp "$EV/upd-poll-$i.xml" "$EV/upd-anr-$i.xml"; adb logcat -d -b main,system -t 600 > "$EV/upd-anr-$i-logcat.txt" 2>/dev/null || true; s6 "STOP: system 'isn't responding' dialog at poll $i, NO RESULT (upd-anr-$i.*)"; exit 4; fi
  S=$(classify "$EV/upd-poll-$i.xml"); T=$(awk -v a="$(date +%s.%N)" -v b="$T0" 'BEGIN{printf "%.1f",a-b}')
  echo "poll $i t=${T}s state=$S" >> "$EV/upd-poll.log"
  if [ "$S" != "$LAST" ]; then adb exec-out screencap -p > "$EV/upd-state-$i-$S.png"; SEEN="$SEEN $S"; LAST=$S; else rm -f "$EV/upd-poll-$i.xml"; fi
  case $S in UP_TO_DATE|CHECK_FAILED|AVAILABLE|DOWNLOAD_FAILED) [ -z "$TERM" ] && TERM=$S;; esac
  if [ "$TERM" = UP_TO_DATE ] && [ "$S" = IDLE ]; then REVERTED=1; break; fi
  if [ -n "$TERM" ] && [ "$TERM" != UP_TO_DATE ] && [ "$i" -ge 4 ]; then break; fi
done
adb logcat -d -v threadtime -t 400 > "$EV/upd-logcat.txt" 2>/dev/null || true
s6 "POLL states in order:$SEEN (details upd-poll.log, frames upd-state-*.png)"
adb logcat -d -b crash > "$EV/crash-signed-update.txt" 2>/dev/null || true
grep -q "Process: $PKG" "$EV/crash-signed-update.txt" && { s6 "CRASH of $PKG recorded (crash-signed-update.txt)"; exit 1; }
case "$TERM" in
  UP_TO_DATE) s6 "RESULT UP_TO_DATE observed after the real tap; auto-hide to IDLE within the window: $([ $REVERTED = 1 ] && echo yes || echo not-observed). The app reported up to date, i.e. its lookup returned a release not newer than 1.8 (host tag above is context only, not emulator proof). No update flow started.";;
  CHECK_FAILED) s6 "RESULT CHECK_FAILED observed: the app reported 'Couldn't check' (fetch returned null: no network/DNS, non-2xx, bad JSON or no tag). Environment evidence emu-ping.txt / host tag above. Not labelled an app defect."; exit 1;;
  AVAILABLE) s6 "RESULT AVAILABLE observed (a release newer than 1.8 exists); the app starts its own download flow; no Update/Install tap made by this script. See upd-state-*.png.";;
  DOWNLOAD_FAILED) s6 "RESULT DOWNLOAD_FAILED observed after an AVAILABLE flow; see frames."; exit 1;;
  *) s6 "RESULT UNOBSERVED: no terminal state within 30 polls (states seen:${SEEN:- none}); tap registration or network timeout unproven. Not a pass."; exit 1;;
esac
log "DONE: signed-update check finished (see signed-update.log)"
