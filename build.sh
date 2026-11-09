#!/usr/bin/env bash
# Builds the HomeRoom Android APK without Gradle.
# Needs: a JDK 8+ (javac), and ANDROID_SDK pointing at an SDK with
# platforms;android-34 and build-tools;34.0.0.
set -euo pipefail

SDK="${ANDROID_SDK:-$HOME/build/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-$HOME/build/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/out"
VERSION="1.0.2"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  "$OUT/res.zip"

echo "== javac =="
find "$ROOT/src" "$OUT/gen" -name '*.java' > "$OUT/sources.list"
javac -source 8 -target 8 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.list"

echo "== d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.list"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.list"

echo "== package =="
(cd "$OUT/dex" && zip -q "$OUT/app-unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

KS="${HOMEROOM_KEYSTORE:-$HOME/build/homeroom.keystore}"
PASS_FILE="${HOMEROOM_KEYSTORE_PASS_FILE:-$HOME/build/keystore.pass}"
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PASS_FILE" \
  --out "$OUT/HomeRoom-$VERSION.apk" "$OUT/app-aligned.apk"

echo "== verify =="
"$BT/apksigner" verify "$OUT/HomeRoom-$VERSION.apk"
echo "BUILT: $OUT/HomeRoom-$VERSION.apk"
