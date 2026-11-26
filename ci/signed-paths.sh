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


# 6) Targeted runtime checks on the SIGNED candidate (harness-only, emulator-only credential; cleartext/manifest untouched).
#    Seeded session = enrolled but unreachable server (seed.invalid): connected share/update success is NOT exercised here.
#    Structure: each section runs in its own subshell. exit 1 = harness/metadata failure (recorded, later sections still run);
#    exit 3 = privacy finding (run STOPS after recording, nothing relabelled); exit 4 = ANR dialog (NO RESULT, stop).
SEC6="$EV/signed-paths.log"; : > "$SEC6"; HFAILS=""; : > "$EV/control-failed"
s6() { echo "$*" | tee -a "$SEC6" | tee -a "$EV/proof.log" >/dev/null; }
need() { [ -s "$EV/$1" ] || fail "missing dump $1 (a negative assert on a missing file would be vacuous)"; }
# accessibility-visible (compressed) dump = importance-filtered tree; stock dump = includes not-important views.
no_label() { need "$1.a11y.xml"; need "$1.xml"; if grep -qF "text=\"$2\"" "$EV/$1.a11y.xml"; then pfail "$1: text \"$2\" in the accessibility-visible tree"; fi; if grep -qF "text=\"$2\"" "$EV/$1.xml"; then s6 "NOTE $1: text \"$2\" only in the stock (include-not-important) dump"; fi; }
no_desc() { need "$1.a11y.xml"; need "$1.xml"; if grep -qF "content-desc=\"$2\"" "$EV/$1.a11y.xml"; then pfail "$1: content-desc \"$2\" in the accessibility-visible tree"; fi; if grep -qF "content-desc=\"$2\"" "$EV/$1.xml"; then s6 "NOTE $1: content-desc \"$2\" only in the stock dump"; fi; }
cover() { need "$1.a11y.xml"; grep -qF 'text="HomeRoom is locked"' "$EV/$1.a11y.xml" || pfail "$1: lock cover text missing from the accessibility-visible tree"; }
has_a11y() { need "$1.a11y.xml"; grep -qF "$2" "$EV/$1.a11y.xml"; }
state() { # $1=tag: record window/activity/lock state (evidence only)
  adb shell dumpsys window | tr -d '\r' > "$EV/$1-window.txt" || true
  adb shell dumpsys activity activities | tr -d '\r' > "$EV/$1-activity.txt" || true
  grep -E "mCurrentFocus|mFocusedApp|mFocusedWindow|isKeyguardShowing|mDreamingLockscreen|keyguardShowing" "$EV/$1-window.txt" | sort -u > "$EV/$1-focus.txt" || true
  grep -E "topResumedActivity|mResumedActivity|ResumedActivity" "$EV/$1-activity.txt" | head -5 > "$EV/$1-resumed.txt" || true
}
nolocked_leak() { # $1=shot name: no toast, no share dialog, no room/error body in the accessibility-visible tree; foreground is HomeRoom
  need "$1.a11y.xml"
  for t in "Open your room list before sharing a file." "Connect HomeRoom before sharing a file."; do no_label "$1" "$t"; done
  if grep -qiE 'Review shared|Share|Send to|Couldn.t|error|Try again|Rooms|Settings|Search messages' "$EV/$1.a11y.xml"; then pfail "$1: share dialog / room or error body text in the accessibility-visible tree"; fi
  for i in 1 2 3 4 5; do state "$1"; grep -q "mCurrentFocus=null" "$EV/$1-focus.txt" || break; sleep 2; done  # bounded 10 s poll for window focus
  if ! grep -q "$PKG/" "$EV/$1-resumed.txt"; then fail "$1: resumed activity is not HomeRoom ($(tr '\n' ' ' < "$EV/$1-resumed.txt")); window state saved in $1-window.txt/$1-activity.txt"; fi
  if grep -q "mCurrentFocus=null" "$EV/$1-focus.txt"; then s6 "UNVERIFIED $1: window focus is null (mCurrentFocus=null); HomeRoom is the resumed activity; no other package holds focus. Not counted as a pass for focus."
  elif ! grep "mCurrentFocus" "$EV/$1-focus.txt" | grep -q "$PKG"; then fail "$1: focused window is not HomeRoom ($(grep mCurrentFocus "$EV/$1-focus.txt" | head -1))"; fi
}
snap() { # like shot, but no ANR probe first; stock dump FIRST so a short-lived toast has the best chance; $1=name
  adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1 && adb pull /sdcard/w.xml "$EV/$1.xml" >/dev/null 2>&1 || fail "no UI dump for $1"
  adb shell uiautomator dump --compressed /sdcard/wa.xml >/dev/null 2>&1 && adb pull /sdcard/wa.xml "$EV/$1.a11y.xml" >/dev/null 2>&1 || fail "no compressed UI dump for $1"
  adb exec-out screencap -p > "$EV/$1.png"; need "$1.xml"; need "$1.a11y.xml"
}
resumed_ok() { adb shell dumpsys activity activities | tr -d '\r' | grep -E "topResumedActivity|mResumedActivity" | grep -q "$PKG/"; }
wait_resumed() { for i in $(seq 1 15); do resumed_ok && return 0; sleep 2; done; return 1; }  # bounded 30 s
qlaunch() { adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 10; adb shell input keyevent KEYCODE_WAKEUP; sleep 2; wait_resumed || log "NOTE qlaunch: HomeRoom not resumed within 30 s (state recorded at the next assert)"; }
enter_pin() { sleep 4; adb shell input text 1234; sleep 1; adb shell input keyevent 66; sleep 5; }
unlock_app() { tap_text "Unlock" || fail "lock cover has no Unlock button"; enter_pin; }
write_lock_pref() {
  printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' '<map>' \
    '<boolean name="hide_previews" value="true" />' '<boolean name="hide_recents" value="false" />' "<boolean name=\"lock\" value=\"$1\" />" '</map>' > app_privacy_lock.xml
  adb push app_privacy_lock.xml /data/local/tmp/app_privacy.xml >/dev/null
  adb shell "cp /data/local/tmp/app_privacy.xml /data/data/$PKG/shared_prefs/app_privacy.xml && chown $OWN /data/data/$PKG/shared_prefs/app_privacy.xml && chmod 660 /data/data/$PKG/shared_prefs/app_privacy.xml; restorecon /data/data/$PKG/shared_prefs/app_privacy.xml"
}
finish() { # $1=exit code; always clear the emulator PIN, record crash buffer, summarize
  FINISHED=1
  adb logcat -d -b crash > "$EV/crash-signed-paths.txt" 2>/dev/null || true
  if grep -q "Process: $PKG" "$EV/crash-signed-paths.txt" 2>/dev/null; then s6 "CRASH of $PKG recorded in crash-signed-paths.txt"; HFAILS="$HFAILS crash"; fi
  adb shell locksettings clear --old 1234 >/dev/null 2>&1 || true
  if [ -s "$EV/control-failed" ]; then s6 "CONTROL FAILED ($(cat "$EV/control-failed")): clean negatives in 6a/6c are VACUOUS, not a pass"; fi
  local rc=$1; if [ "$rc" = 0 ] && [ -n "${HFAILS# }" ]; then rc=1; fi  # crash/harness failures recorded above must not leave a green exit
  s6 "SUMMARY exit=$rc harness_failures=[${HFAILS# }]; Connected share/update success: NOT exercised (seed.invalid)."
  log "DONE: signed-paths checks finished (see signed-paths.log)"
  exit "$rc"
}
run_sec() { # name fn
  local n=$1 rc; shift
  set +e; ( set -e; "$@" ); rc=$?; set -e
  case $rc in
    0) ;;
    3) s6 "STOP: privacy finding in $n (see log, dumps and pixels); later sections not run"; finish 3;;
    4) s6 "STOP: ANR dialog during $n, NO RESULT"; finish 4;;
    *) HFAILS="$HFAILS $n"; s6 "HARNESS FAIL in $n (recorded; later sections continue)";;
  esac
}
FINISHED=0
trap '[ "$FINISHED" = 1 ] || { adb shell locksettings clear --old 1234 >/dev/null 2>&1 || true; }' EXIT
adb shell locksettings set-pin 1234 >/dev/null 2>&1 || fail "emulator PIN setup failed"
s6 "emulator PIN set (emulator-only, harness)"
adb logcat -b crash -c

# 6.0 positive control BEFORE the lock pref exists: the compressed dump of the unlocked app must contain content-desc Rooms and Search messages
sec_control() {
  adb shell am force-stop "$PKG"
  write_lock_pref false
  qlaunch; shot control-unlocked
  for d in "Rooms" "Search messages"; do has_a11y control-unlocked "content-desc=\"$d\"" || { echo "pre-lock:$d" >> "$EV/control-failed"; fail "control: content-desc \"$d\" missing from the compressed dump with no lock (compressed dump does not discriminate)"; }; done
  s6 "6.0 CONTROL PASS: compressed dump of the unlocked app contains content-desc Rooms and Search messages"
}
sec_6a() { # lock on at cold start: cover shown, app content hidden
  adb shell am force-stop "$PKG"; write_lock_pref true
  qlaunch; shot lock-cold
  cover lock-cold; no_label lock-cold "Rooms"; no_desc lock-cold "Rooms"; no_desc lock-cold "Search messages"; nolocked_leak lock-cold
  s6 "6a PASS: lock=true cold start: cover 'HomeRoom is locked' in the accessibility-visible tree, Rooms/Search messages absent from it (stock dump may still list them, see NOTE lines)"
}
sec_6b() { # unlock with the emulator PIN: cover gone, content back
  unlock_app; shot lock-unlocked
  need lock-unlocked.a11y.xml
  if grep -qF 'text="HomeRoom is locked"' "$EV/lock-unlocked.a11y.xml"; then fail "lock-unlocked: cover still present after PIN"; fi
  has_label lock-unlocked "Rooms"; : > "$EV/unlock-ok"
  for d in "Rooms" "Search messages"; do has_a11y lock-unlocked "content-desc=\"$d\"" || { echo "unlocked:$d" >> "$EV/control-failed"; fail "positive control: content-desc \"$d\" missing from the compressed dump when unlocked"; }; done
  s6 "6b PASS: PIN unlock removes the cover; Rooms + Search messages back in the accessibility-visible tree (positive control)"
}
sec_6c() { # background then return: relocks
  adb shell input keyevent KEYCODE_HOME; sleep 3; qlaunch; shot lock-relock
  cover lock-relock; no_label lock-relock "Rooms"; no_desc lock-relock "Rooms"; no_desc lock-relock "Search messages"; nolocked_leak lock-relock
  s6 "6c PASS: HOME then return shows the cover again (accessibility-visible tree)"
}
sec_6d() { # share while LOCKED: cover stays, nothing delivered before unlock
  adb shell am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM content://media/external/file/1 -n "$PKG/.MainActivity" >/dev/null; sleep 6
  shot share-locked
  cover share-locked; nolocked_leak share-locked
  s6 "6d PASS: ACTION_SEND while locked keeps the cover up; no toast/share dialog/room body in the accessibility-visible tree"
}
gate() { [ -e "$EV/unlock-ok" ] || { s6 "NOT RUN: $1 (needs a successful PIN unlock in 6b; unlock did not succeed)"; exit 0; }; }
sec_6e() { gate 6e; # after unlock the queued share is delivered: no room is loaded (seed.invalid), so the app must say so (toast text if the dump caught it)
  unlock_app; snap share-after-unlock
  if grep -qF 'text="HomeRoom is locked"' "$EV/share-after-unlock.a11y.xml"; then rm -f "$EV/unlock-ok"; fail "share-after-unlock: cover still present (unlock failed; 6f/6g will be NOT RUN)"; fi
  if grep -qF "Open your room list before sharing a file." "$EV/share-after-unlock.xml"; then s6 "6e PASS: after unlock the pending share showed the 'Open your room list before sharing a file.' message"; else s6 "6e PARTIAL: cover removed after unlock; share toast text NOT captured (toast may have expired), delivery not proven"; fi
}
sec_6f() { gate 6f; # share intent to the already-unlocked app: OBSERVE (cover vs message). Run 45862607794 showed the cover returns (singleTask onNewIntent pauses the activity, AppLock.onPause relocks); not asserted either way.
  adb shell am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM content://media/external/file/1 -n "$PKG/.MainActivity" >/dev/null; sleep 2
  snap share-unlocked
  if grep -qF 'text="HomeRoom is locked"' "$EV/share-unlocked.a11y.xml"; then s6 "6f OBSERVED: SEND intent to the unlocked app brought the lock cover back (share-unlocked.png); relock-on-pause behavior, not a pass/fail assertion"
  elif grep -qF "Open your room list before sharing a file." "$EV/share-unlocked.xml"; then s6 "6f OBSERVED: unlocked share showed the 'Open your room list' message and no cover"
  else s6 "6f OBSERVED: no cover and no message text captured (share-unlocked.png)"; fi
}
ensure_unlocked() { # $1=tag; unlock again if the cover is up, then require the cover gone
  snap "$1-pre"
  if grep -qF 'text="HomeRoom is locked"' "$EV/$1-pre.a11y.xml"; then unlock_app; snap "$1-post"; if grep -qF 'text="HomeRoom is locked"' "$EV/$1-post.a11y.xml"; then fail "$1: still locked after re-unlock"; fi; s6 "NOTE $1: app was locked, unlocked again with the emulator PIN"; fi
}
sec_6g() { gate 6g; # Updates card with the server unreachable: shows version, check does not crash or invent an update
  ensure_unlocked upd; sleep 2; shot upd-0
  tap_text "Settings" || fail "Settings tab not found after unlock"; sleep 4
  for i in 1 2 3 4 5 6; do adb shell input swipe 360 1300 360 400 300; sleep 1; done
  shot upd-1
  has_label upd-1 "Version 1.8"
if tap_text "Check for updates"; then sleep 8; shot upd-2; if grep -qF "Couldn't check. Try again." "$EV/upd-2.xml"; then s6 "6g PASS: Check for updates reported \"Couldn't check. Try again.\"; version row Version 1.8 shown"; elif grep -qE "is available|Up to date" "$EV/upd-2.xml"; then s6 "6g INFO: update check reached a release (network): see upd-2.png; version row Version 1.8 shown"; else s6 "6g PARTIAL: Check tapped, outcome text not recognised (see upd-2.png)"; fi; else s6 "6g PARTIAL: Check for updates button not found after scroll (see upd-1.png); Version 1.8 shown"; fi
}
run_sec control sec_control
run_sec 6a sec_6a
run_sec 6b sec_6b
run_sec 6c sec_6c
run_sec 6d sec_6d
run_sec 6e sec_6e
run_sec 6f sec_6f
run_sec 6g sec_6g
if [ -n "$HFAILS" ]; then finish 1; else finish 0; fi
