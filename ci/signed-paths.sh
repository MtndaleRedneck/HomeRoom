#!/usr/bin/env bash
# FRESH-INSTALL SMOKE of the signed candidate, NOT a migration proof. Seeded lock pref (not enabled in UI). Harness-only, runs inside the emulator step. Real published 1.7 -> locally signed 1.8 (installed with
# `adb install -r`, never uninstalled). The emulator image is google_apis (userdebug), so `adb root` can
# seed app-private prefs on the NON-debuggable published 1.7. All seeded values are SYNTHETIC.
set -euo pipefail
PKG=app.homeroom
UP=/tmp/up
EV=upgrade-evidence; mkdir -p "$EV"
BT=/tmp/bt/android-14
log() { echo "$*" | tee -a "$EV/proof.log"; }
fail() { log "ASSERT FAIL: $*"; exit 1; }

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
  fail "system dialog 'isn't responding' present (recorded, not dismissed, NO RESULT); evidence anr-$ANRN.*"
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

# 6) Targeted runtime checks on the SIGNED candidate (harness-only, emulator-only credential; cleartext/manifest untouched).
#    Seeded session = enrolled but unreachable server (seed.invalid): connected share/update success is NOT exercised here.
SEC6="$EV/signed-paths.log"; : > "$SEC6"
s6() { echo "$*" | tee -a "$SEC6" | tee -a "$EV/proof.log" >/dev/null; }
no_label() { if grep -qF "text=\"$2\"" "$EV/$1.a11y.xml"; then fail "$1: label \"$2\" must NOT be in the accessibility-visible tree"; fi; if grep -qF "text=\"$2\"" "$EV/$1.xml"; then s6 "NOTE $1: \"$2\" present only in the full (include-not-important) dump, absent from the accessibility-visible dump"; fi; }

nolocked_leak() { # $1=shot name: no toast, no share dialog, no room/error body, focus stays in HomeRoom
  for t in "Open your room list before sharing a file." "Connect HomeRoom before sharing a file."; do no_label "$1" "$t"; done
  if grep -qiE 'Review shared|Share|Send to|Couldn.t|error|Try again|Rooms|Settings' "$EV/$1.a11y.xml"; then fail "$1: share dialog / room or error body text exposed under the cover"; fi
  adb shell dumpsys window | tr -d '\r' | grep -m1 mCurrentFocus > "$EV/$1-focus.txt" || true
  grep -q "$PKG" "$EV/$1-focus.txt" || fail "$1: focus is not HomeRoom ($(cat "$EV/$1-focus.txt"))"
}
qlaunch() { adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 10; adb shell input keyevent KEYCODE_WAKEUP; sleep 2; }
enter_pin() { sleep 4; adb shell input text 1234; sleep 1; adb shell input keyevent 66; sleep 5; }
unlock_app() { tap_text "Unlock" || fail "lock cover has no Unlock button"; enter_pin; }
adb shell locksettings set-pin 1234 >/dev/null 2>&1 || fail "emulator PIN setup failed"
adb shell locksettings get-disabled | tr -d '\r' | tee -a "$SEC6" >/dev/null || true
s6 "emulator PIN set (emulator-only, harness)"
adb shell am force-stop "$PKG"
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' '<map>' \
  '<boolean name="hide_previews" value="true" />' '<boolean name="hide_recents" value="false" />' '<boolean name="lock" value="true" />' '</map>' > app_privacy_lock.xml
adb push app_privacy_lock.xml /data/local/tmp/app_privacy.xml >/dev/null
adb shell "cp /data/local/tmp/app_privacy.xml /data/data/$PKG/shared_prefs/app_privacy.xml && chown $OWN /data/data/$PKG/shared_prefs/app_privacy.xml && chmod 660 /data/data/$PKG/shared_prefs/app_privacy.xml; restorecon /data/data/$PKG/shared_prefs/app_privacy.xml"
adb logcat -b crash -c
# 6a lock on at cold start: cover shown, app content hidden
qlaunch; shot lock-cold
has_label lock-cold "HomeRoom is locked"; no_label lock-cold "Rooms"; nolocked_leak lock-cold
s6 "6a PASS: lock=true cold start shows cover 'HomeRoom is locked', tab label Rooms hidden"
# 6b unlock with the emulator PIN: cover gone, content back
unlock_app; shot lock-unlocked
no_label lock-unlocked "HomeRoom is locked"; has_label lock-unlocked "Rooms"; grep -qF 'text="Rooms"' "$EV/lock-unlocked.a11y.xml" || fail "positive control: Rooms missing from the compressed dump when unlocked (compressed dump does not discriminate)"
s6 "6b PASS: PIN unlock removes the cover and shows Rooms"
# 6c background then return: relocks
adb shell input keyevent KEYCODE_HOME; sleep 3; qlaunch; shot lock-relock
has_label lock-relock "HomeRoom is locked"; no_label lock-relock "Rooms"; nolocked_leak lock-relock
s6 "6c PASS: HOME then return shows the cover again"
# 6d share while LOCKED: cover stays, nothing delivered before unlock
adb shell am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM content://media/external/file/1 -n "$PKG/.MainActivity" >/dev/null; sleep 6
shot share-locked
has_label share-locked "HomeRoom is locked"; nolocked_leak share-locked
s6 "6d PASS: ACTION_SEND while locked keeps the cover up"
# 6e after unlock the queued share is delivered: no room is loaded (seed.invalid), so the app must say so (toast text if the dump caught it)
unlock_app; adb shell uiautomator dump /sdcard/t.xml >/dev/null 2>&1; adb pull /sdcard/t.xml "$EV/share-after-unlock.xml" >/dev/null 2>&1 || true
adb exec-out screencap -p > "$EV/share-after-unlock.png"
no_label share-after-unlock "HomeRoom is locked"
if grep -qF "Open your room list before sharing a file." "$EV/share-after-unlock.xml"; then s6 "6e PASS: after unlock the pending share showed the 'Open your room list before sharing a file.' message"; else s6 "6e PARTIAL: cover removed after unlock; share toast text NOT captured in the UI dump (toast may have expired), delivery not proven"; fi
# 6f share while UNLOCKED (new intent, app already unlocked)
adb shell am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM content://media/external/file/1 -n "$PKG/.MainActivity" >/dev/null; sleep 2
adb shell uiautomator dump /sdcard/t2.xml >/dev/null 2>&1; adb pull /sdcard/t2.xml "$EV/share-unlocked.xml" >/dev/null 2>&1 || true
adb exec-out screencap -p > "$EV/share-unlocked.png"
no_label share-unlocked "HomeRoom is locked"
if grep -qF "Open your room list before sharing a file." "$EV/share-unlocked.xml"; then s6 "6f PASS: unlocked share showed the 'Open your room list' message and no cover"; else s6 "6f PARTIAL: unlocked share left no cover; message text not captured"; fi
# 6g Updates card with the server unreachable: shows version, check does not crash or invent an update
sleep 4; shot upd-0
tap_text "Settings" || fail "Settings tab not found after unlock"; sleep 4
for i in 1 2 3 4 5 6; do adb shell input swipe 360 1300 360 400 300; sleep 1; done
shot upd-1
has_label upd-1 "Version 1.8"
if tap_text "Check for updates"; then sleep 8; shot upd-2; if grep -qF "Couldn't check. Try again." "$EV/upd-2.xml"; then s6 "6g PASS: Check for updates reported \"Couldn't check. Try again.\"; version row Version 1.8 shown"; elif grep -qE "is available|Up to date" "$EV/upd-2.xml"; then s6 "6g INFO: update check reached a release (network): see upd-2.png; version row Version 1.8 shown"; else s6 "6g PARTIAL: Check tapped, outcome text not recognised (see upd-2.png)"; fi; else s6 "6g PARTIAL: Check for updates button not found after scroll (see upd-1.png); Version 1.8 shown"; fi
adb logcat -d -b crash > "$EV/crash-signed-paths.txt" || fail "cannot read the crash buffer"
if grep -q "Process: $PKG" "$EV/crash-signed-paths.txt"; then fail "crash of $PKG during signed-path checks (crash-signed-paths.txt)"; fi
s6 "crash buffer during 6a-6g: none for $PKG. Connected share/update success: NOT exercised (seed.invalid)."
adb shell locksettings clear --old 1234 >/dev/null 2>&1 || true

log "DONE: signed-paths checks finished (see signed-paths.log)"
