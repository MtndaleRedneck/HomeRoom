#!/usr/bin/env bash
# Harness-only. Rebuilds the locally-signed 1.7 APK from the gate run's unsigned artifact
# plus a signature delta (no private key, no signed APK is stored anywhere), and fetches the
# real published 1.6. Every input is hash-pinned; any mismatch fails the job.
# Env: UNSIGNED_SHA256 ALIGNED_SHA256 SIGNED_SHA256 PATCH_B64 BUILD_TOOLS_ZIP_SHA256 TRUSTED_CERT
set -euo pipefail
OUT=/tmp/up; mkdir -p "$OUT" /tmp/bt
cd /tmp/bt
curl -sfL -o bt.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip
echo "$BUILD_TOOLS_ZIP_SHA256  bt.zip" | sha256sum -c -
unzip -q bt.zip
BT=/tmp/bt/android-14
cd - >/dev/null

ls in/*UNSIGNED.apk | wc -l | grep -qx 1 || { echo "expected exactly one unsigned apk in the artifact"; ls -la in; exit 1; }
UNS=$(ls in/*UNSIGNED.apk)
echo "$UNSIGNED_SHA256  $UNS" | sha256sum -c -
"$BT/zipalign" -f -p 4 "$UNS" "$OUT/aligned.apk"
echo "$ALIGNED_SHA256  $OUT/aligned.apk" | sha256sum -c -
pip install --quiet bsdiff4==1.4.4
printf '%s' "$PATCH_B64" | base64 -d > "$OUT/sig.patch"
python3 -c "import bsdiff4; bsdiff4.file_patch('$OUT/aligned.apk', '$OUT/HomeRoom-1.8-signed.apk', '$OUT/sig.patch')"
echo "$SIGNED_SHA256  $OUT/HomeRoom-1.8-signed.apk" | sha256sum -c -
CERT=$("$BT/apksigner" verify --print-certs "$OUT/HomeRoom-1.8-signed.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$CERT" = "$TRUSTED_CERT" ] || { echo "ASSERT FAIL: 1.8 signer $CERT"; exit 1; }
"$BT/aapt2" dump badging "$OUT/HomeRoom-1.8-signed.apk" | head -1 | grep -q "name='app.homeroom' versionCode='13' versionName='1.8'" || { echo "ASSERT FAIL: 1.8 package/version"; exit 1; }

curl -sfL -o "$OUT/HomeRoom-1.7.apk" https://github.com/MtndaleRedneck/HomeRoom/releases/download/v1.7/HomeRoom-1.7.apk
echo "238bc7558cc29c91959975e41e9f2a5e0a3a7ebad3c0a28c7066c90e062b9fa1  $OUT/HomeRoom-1.7.apk" | sha256sum -c -
"$BT/aapt2" dump badging "$OUT/HomeRoom-1.7.apk" | head -1 | grep -q "name='app.homeroom' versionCode='12' versionName='1.7'" || { echo "ASSERT FAIL: 1.7 package/version"; exit 1; }
C17=$("$BT/apksigner" verify --print-certs "$OUT/HomeRoom-1.7.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$C17" = "$TRUSTED_CERT" ] || { echo "ASSERT FAIL: 1.7 signer $C17"; exit 1; }
echo "prepared: 1.8 signed $SIGNED_SHA256 (cert $CERT, versionCode 13); 1.7 published 238bc755... (cert $C17, versionCode 12)"
