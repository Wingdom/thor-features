#!/usr/bin/env bash
# Build the per-app profiles APK. No Gradle, no Kotlin -- aapt2 + javac + d8.
# Runs the host unit tests first and refuses to package if they fail.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

SDK="${ANDROID_SDK:-$HOME/android}"
BT="$SDK/build-tools/37.0.0"
AJ="$SDK/platforms/android-37.0/android.jar"   # Android 17, matching the device
PKG_PATH=com/thorfeatures/appprofiles
MIN_SDK=31             # the system_* dynamic colour palette
TARGET_SDK=37          # Android 17. Play Protect blocks installs targeting
                       # older releases on this device.

for t in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$AJ"; do
    [ -e "$t" ] || { echo "missing: $t"; exit 1; }
done

# Compiled in, not copied. FanMode defines the exact words passed to fanctl and
# written into the profile table; a second copy would be a second source of
# truth, and a renamed tier would then fail silently at runtime instead of
# loudly here. Same arrangement thor-screenpanel uses.
SHARED_SRC="../thor-fan-tile/src/com/thorfeatures/fanctl/FanMode.java"
for f in $SHARED_SRC; do
    [ -f "$f" ] || { echo "missing shared source: $f"; exit 1; }
done

rm -rf out; mkdir -p out/res out/classes out/dex out/test out/gen

echo "== unit tests (host, no device) =="
javac --release 17 -nowarn -d out/test \
    $SHARED_SRC \
    "src/$PKG_PATH/ProfileTable.java" \
    "src/$PKG_PATH/AppwatchCommands.java" \
    "src/$PKG_PATH/AppEntry.java" \
    "test/$PKG_PATH/TestMain.java" || { echo "test compile failed"; exit 1; }
java -cp out/test com.thorfeatures.appprofiles.TestMain \
    || { echo "build aborted: unit tests failed"; exit 1; }

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
javac --release 17 -nowarn -cp "$AJ" -d out/classes \
    $SHARED_SRC $(find src out/gen -name '*.java') || exit 1
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
        -dname "CN=thor-appprofiles, O=local, C=US" >/dev/null 2>&1 \
        || { echo "keytool failed"; exit 1; }
    echo "  generated dev keystore"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out out/thor-appprofiles.apk out/aligned.apk 2>/dev/null || exit 1
"$BT/apksigner" verify out/thor-appprofiles.apk 2>/dev/null && echo "  signature verified"

mkdir -p dist && cp out/thor-appprofiles.apk dist/
echo
echo "  dist/thor-appprofiles.apk ($(du -h dist/thor-appprofiles.apk | cut -f1))"
