#!/usr/bin/env bash
# Build the Quick Settings tile APK. No Gradle, no Kotlin -- aapt2 + javac + d8.
# Runs the host unit tests first and refuses to package if they fail.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

SDK="${ANDROID_SDK:-$HOME/android}"
BT="$SDK/build-tools/37.0.0"
AJ="$SDK/platforms/android-37.0/android.jar"   # Android 17, matching the device
PKG=com.thorfeatures.stickfx
MIN_SDK=24          # TileService was added in API 24
TARGET_SDK=37          # Android 17. Play Protect on this device blocks installs
                       # of anything older ("built for an older version of
                       # Android"), which is what targeting 33 hit.

for t in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$AJ"; do
    [ -e "$t" ] || { echo "missing: $t"; exit 1; }
done

rm -rf out; mkdir -p out/res out/classes out/dex

echo "== unit tests (host, no device) =="
mkdir -p out/test
PKG_PATH=com/thorfeatures/stickfx
javac --release 17 -nowarn -cp "$AJ" -d out/test \
    "src/$PKG_PATH/StickMode.java" "src/$PKG_PATH/StickfxClient.java" "src/$PKG_PATH/TileRender.java" \
    "test/$PKG_PATH/TestMain.java" || { echo "test compile failed"; exit 1; }
java -cp out/test com.thorfeatures.stickfx.TestMain || { echo "build aborted: unit tests failed"; exit 1; }

echo
echo "== resources =="
"$BT/aapt2" compile --dir res -o out/res.zip || exit 1
"$BT/aapt2" link -o out/base.apk \
    -I "$AJ" \
    --manifest AndroidManifest.xml \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --java out/gen \
    out/res.zip || exit 1
echo "  linked, R.java generated"

echo
echo "== compile =="
mkdir -p out/gen
javac --release 17 -nowarn -cp "$AJ" -d out/classes \
    $(find src out/gen -name '*.java') || exit 1
echo "  $(find out/classes -name '*.class' | wc -l) classes"

echo
echo "== dex =="
"$BT/d8" --release --lib "$AJ" --min-api "$MIN_SDK" --output out/dex \
    $(find out/classes -name '*.class') || exit 1
echo "  classes.dex $(stat -c%s out/dex/classes.dex) bytes"

echo
echo "== package =="
cp out/base.apk out/unsigned.apk
( cd out/dex && zip -q ../unsigned.apk classes.dex )
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk || exit 1

# Dev signing key, generated once. Not a release key.
KS=keystore/debug.jks
if [ ! -f "$KS" ]; then
    mkdir -p keystore
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias tilekey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=thor-stickfx-tile, O=local, C=US" >/dev/null 2>&1 \
        || { echo "keytool failed"; exit 1; }
    echo "  generated dev keystore"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out out/thor-stickfx-tile.apk out/aligned.apk 2>/dev/null || exit 1
"$BT/apksigner" verify out/thor-stickfx-tile.apk 2>/dev/null && echo "  signature verified"

mkdir -p dist && cp out/thor-stickfx-tile.apk dist/
echo
echo "  dist/thor-stickfx-tile.apk ($(du -h dist/thor-stickfx-tile.apk | cut -f1))"
