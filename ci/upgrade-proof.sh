#!/usr/bin/env bash
# Harness-only, runs inside the emulator step. Real published 1.7 -> locally signed 1.8 (installed with
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
clear_anr() { # ONLY the verified cold-emulator dialog "Pixel Launcher isn't responding" is dismissed (tap Wait, max 4, evidence saved
  # before each tap). Any OTHER "isn't responding" dialog (HomeRoom or any app) FAILS the proof with evidence preserved.
  for i in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/a.xml >/dev/null 2>&1 || return 0
    adb shell cat /sdcard/a.xml > "$EV/anr-probe.xml" 2>/dev/null || return 0
    grep -q "isn't responding" "$EV/anr-probe.xml" || return 0
    ANRN=$((ANRN+1))
    adb exec-out screencap -p > "$EV/anr-$ANRN.png"; cp "$EV/anr-probe.xml" "$EV/anr-$ANRN.xml"; adb logcat -d -b main,system -t 300 > "$EV/anr-$ANRN-logcat.txt" 2>/dev/null || true
    if ! grep -qF "text=\"Pixel Launcher isn't responding\"" "$EV/anr-probe.xml"; then fail "unexpected system ANR dialog (not the verified Pixel Launcher one); evidence anr-$ANRN.*"; fi
    [ "$i" -le 4 ] || fail "Pixel Launcher ANR dialog still present after 4 Wait taps; evidence anr-$ANRN.*"
    log "Pixel Launcher ANR dialog, tapping Wait ($i of 4), evidence anr-$ANRN.png/xml/logcat"
    xy=$(python3 ci/ui-tap.py "Wait") || fail "Wait button not found on the Pixel Launcher ANR dialog; evidence anr-$ANRN.*"
    adb shell input tap $xy; sleep 3
  done
}
shot() { # screenshot + UI dump; a missing dump fails the proof (labels are asserted from it)
  clear_anr
  adb exec-out screencap -p > "$EV/$1.png"
  adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1 && adb pull /sdcard/w.xml "$EV/$1.xml" >/dev/null 2>&1 || fail "no UI dump for $1"
  [ -s "$EV/$1.xml" ] || fail "empty UI dump for $1"
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

# 1) published 1.7, clean install
adb install "$UP/HomeRoom-1.7.apk" | tee -a "$EV/proof.log"
[ "$(field versionCode | cut -d' ' -f1)" = 12 ] || fail "installed versionCode is not 12"
FIT_BEFORE=$(field firstInstallTime); LUT_BEFORE=$(field lastUpdateTime)
log "1.7 installed: firstInstallTime=$FIT_BEFORE lastUpdateTime=$LUT_BEFORE"

# 2) seed synthetic state as the app's uid (published 1.7 is not debuggable, so run-as cannot do this)
OWN=$(adb shell stat -c %u:%g "/data/data/$PKG" | tr -d '\r')
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' '<map>' \
  '<string name="server_url">https://seed.invalid</string>' '<string name="cookie">SYNTHETIC-seed-cookie</string>' \
  '<string name="csrf">SYNTHETIC-csrf</string>' '<string name="owner_name">Seed Owner</string>' \
  '<string name="last_room_id">seed-room-1</string>' '<string name="skipped_update_tag">none</string>' '</map>' > homeroom_session.xml
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' '<map>' \
  '<boolean name="hide_previews" value="true" />' '<boolean name="hide_recents" value="false" />' '<boolean name="lock" value="false" />' '</map>' > app_privacy.xml
adb shell am force-stop "$PKG"
adb shell mkdir -p "/data/data/$PKG/shared_prefs"
for f in homeroom_session.xml app_privacy.xml; do
  adb push "$f" "/data/local/tmp/$f" >/dev/null
  adb shell "cp /data/local/tmp/$f /data/data/$PKG/shared_prefs/$f && chown $OWN /data/data/$PKG/shared_prefs/$f && chmod 660 /data/data/$PKG/shared_prefs/$f"
done
adb shell "chown $OWN /data/data/$PKG/shared_prefs; restorecon -R /data/data/$PKG/shared_prefs"

# 3) 1.7 reads the seed: main shell (not setup) and the Privacy screen
launch
shot old-main
tap_text "Settings" || fail "1.7: Settings tab not found (seed not recognised?)"; sleep 4
tap_text "App lock and privacy" || fail "1.7: privacy row not found"; sleep 4
shot old-privacy
has_label old-main "Rooms"   # GlassNav tab label (1.7 source GlassNav.kt); a setup screen has no tab bar
# Privacy screen (AppPrivacy.kt, identical in 1.7 and 1.8): buttons read "<title>: On|Off" from the seeded prefs
has_label old-privacy "Hide lock-screen alerts: On"
has_label old-privacy "Hide in recent apps: Off"
prefs_state old
cat "$EV/map-old.txt" | tee -a "$EV/proof.log"
for k in server_url cookie csrf owner_name last_room_id skipped_update_tag; do grep -q "^homeroom_session.xml:$k=" "$EV/map-old.txt" || fail "1.7 dropped seeded key $k"; done

# 4) in-place upgrade to the locally signed 1.8, no uninstall
adb install -r "$UP/HomeRoom-1.8-signed.apk" | tee -a "$EV/proof.log"
FIT_AFTER=$(field firstInstallTime); LUT_AFTER=$(field lastUpdateTime)
log "1.8 installed over 1.7: firstInstallTime=$FIT_AFTER lastUpdateTime=$LUT_AFTER"
[ "$FIT_BEFORE" = "$FIT_AFTER" ] || fail "firstInstallTime changed ($FIT_BEFORE -> $FIT_AFTER)"
[ "$LUT_BEFORE" != "$LUT_AFTER" ] || fail "lastUpdateTime did not change"
[ "$(field versionCode | cut -d' ' -f1)" = 13 ] || fail "versionCode after upgrade is not 13"
adb shell am force-stop "$PKG"
# data untouched by the install itself: raw bytes, before any 1.8 launch
adb exec-out cat "/data/data/$PKG/shared_prefs/homeroom_session.xml" | sha256sum | cut -d' ' -f1 > "$EV/sha-new-prelaunch-session.txt"
adb exec-out cat "/data/data/$PKG/shared_prefs/app_privacy.xml" | sha256sum | cut -d' ' -f1 > "$EV/sha-new-prelaunch-privacy.txt"
[ "$(cut -d' ' -f1 "$EV/sha-old.txt" | sed -n 1p)" = "$(cat "$EV/sha-new-prelaunch-session.txt")" ] || fail "homeroom_session.xml bytes changed by install -r"
[ "$(cut -d' ' -f1 "$EV/sha-old.txt" | sed -n 2p)" = "$(cat "$EV/sha-new-prelaunch-privacy.txt")" ] || fail "app_privacy.xml bytes changed by install -r"
log "prefs bytes identical after install -r, before first 1.8 launch"

# installed APK is the signed candidate and keeps the signer
BASE=$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)
adb pull "$BASE" /tmp/installed-base.apk >/dev/null
INST=$(sha256sum /tmp/installed-base.apk | cut -d' ' -f1)
[ "$INST" = "$SIGNED_SHA256" ] || fail "installed base.apk sha256 $INST != candidate $SIGNED_SHA256"
CERT=$("$BT/apksigner" verify --print-certs /tmp/installed-base.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$CERT" = "$TRUSTED_CERT" ] || fail "installed signer $CERT"
log "installed base.apk sha256=$INST signer=$CERT"

# 5) 1.8 launches on the preserved state (crash buffer cleared first, so only this launch is judged)
adb logcat -b crash -c
launch
shot new-main
tap_text "Settings" || fail "1.8: Settings tab not found"; sleep 4
shot new-settings
tap_text "App lock and privacy" || fail "1.8: privacy row not found"; sleep 4
shot new-privacy
has_label new-main "Rooms"
has_label new-privacy "Hide lock-screen alerts: On"
has_label new-privacy "Hide in recent apps: Off"
prefs_state new
cat "$EV/map-new.txt" | tee -a "$EV/proof.log"
while IFS= read -r line; do grep -qxF "$line" "$EV/map-new.txt" || fail "seeded value changed or lost after 1.8 launch: ${line%%=*}"; done < "$EV/map-old.txt"
adb logcat -d -b crash > "$EV/crash-new.txt" || fail "cannot read the crash buffer"
if grep -q "Process: $PKG" "$EV/crash-new.txt"; then fail "crash of $PKG after the 1.8 launch (see crash-new.txt)"; fi
log "crash buffer since the 1.8 launch: $(wc -l < "$EV/crash-new.txt") lines, none for $PKG"
# 6) Targeted runtime checks on the SIGNED candidate (harness-only, emulator-only credential; cleartext/manifest untouched).
#    Seeded session = enrolled but unreachable server (seed.invalid): connected share/update success is NOT exercised here.
SEC6="$EV/signed-paths.log"; : > "$SEC6"
s6() { echo "$*" | tee -a "$SEC6" | tee -a "$EV/proof.log" >/dev/null; }
no_label() { if grep -qF "text=\"$2\"" "$EV/$1.xml"; then fail "$1: label \"$2\" must NOT be on screen"; fi; }
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
has_label lock-cold "HomeRoom is locked"; no_label lock-cold "Rooms"
s6 "6a PASS: lock=true cold start shows cover 'HomeRoom is locked', tab label Rooms hidden"
# 6b unlock with the emulator PIN: cover gone, content back
unlock_app; shot lock-unlocked
no_label lock-unlocked "HomeRoom is locked"; has_label lock-unlocked "Rooms"
s6 "6b PASS: PIN unlock removes the cover and shows Rooms"
# 6c background then return: relocks
adb shell input keyevent KEYCODE_HOME; sleep 3; qlaunch; shot lock-relock
has_label lock-relock "HomeRoom is locked"; no_label lock-relock "Rooms"
s6 "6c PASS: HOME then return shows the cover again"
# 6d share while LOCKED: cover stays, nothing delivered before unlock
adb shell am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM content://media/external/file/1 -n "$PKG/.MainActivity" >/dev/null; sleep 6
shot share-locked
has_label share-locked "HomeRoom is locked"
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

adb shell cmd uimode night auto
log "PASS: 1.7(12) -> 1.8(13) in place; same firstInstallTime; seeded values preserved; installed bytes == candidate; signer unchanged"
