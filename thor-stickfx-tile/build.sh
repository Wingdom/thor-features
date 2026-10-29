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

# Version. versionCode must be an integer and must only ever increase, because
# Android refuses to install an APK whose code is lower than the installed one.
# Derived from the semver so the two can never drift: 0.5.0 -> 500.
VERSION=0.5.0
VERSION_CODE=$(echo "$VERSION" | awk -F. '{ printf "%d", $1*10000 + $2*100 + $3 }')
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
    --version-code "$VERSION_CODE" --version-name "$VERSION" \
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

# --- signing ------------------------------------------------------------------
# Resolution order:
#   1. $THOR_KEYSTORE / $THOR_KEYSTORE_PASS -- CI, injected from encrypted
#      secrets. Never written to disk in the repo.
#   2. ../../thor/keystore/thor-release.jks -- the real release key. Kept
#      OUTSIDE the repo on purpose: .gitignore is not protection, because
#      `git clean -xfd` deletes ignored files, and losing this key means these
#      apps can never be updated again -- only uninstalled and reinstalled,
#      losing their data and permission grants. Same reasoning as findings/
#      and reference/.
#   3. keystore/debug.jks -- throwaway dev key, generated on demand, so a fork
#      with neither of the above can still build and test. NOT for release.
REL_KS="$HERE/../../thor/keystore/thor-release.jks"
REL_PF="$HERE/../../thor/keystore/thor-release.pass"
if [ -n "${THOR_KEYSTORE:-}" ] && [ -f "${THOR_KEYSTORE:-}" ]; then
    KS="$THOR_KEYSTORE"; KS_PASS="pass:${THOR_KEYSTORE_PASS:-}"; KS_ALIAS=thor-release
    echo "  signing: release key (THOR_KEYSTORE)"
elif [ -f "$REL_KS" ] && [ -f "$REL_PF" ]; then
    KS="$REL_KS"; KS_PASS="file:$REL_PF"; KS_ALIAS=thor-release
    echo "  signing: release key"
else
    KS=keystore/debug.jks; KS_PASS="pass:android"; KS_ALIAS=tilekey
    if [ ! -f "$KS" ]; then
        mkdir -p keystore
        keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
            -alias tilekey -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=thor-stickfx-tile, O=local, C=US" >/dev/null 2>&1 \
            || { echo "keytool failed"; exit 1; }
    fi
    echo "  signing: throwaway DEV key -- not for release"
fi
# --key-pass is deliberately omitted: apksigner's file: reader consumes the
# file for --ks-pass and then hits EOF looking for a second line for
# --key-pass. Store and key passwords are identical in every path above, and
# apksigner defaults the key password to the keystore password.
"$BT/apksigner" sign --ks "$KS" --ks-pass "$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --out "out/thor-stickfx-tile-v$VERSION.apk" out/aligned.apk || exit 1
"$BT/apksigner" verify "out/thor-stickfx-tile-v$VERSION.apk" 2>/dev/null && echo "  signature verified"

mkdir -p dist && rm -f dist/thor-stickfx-tile-v*.apk && cp "out/thor-stickfx-tile-v$VERSION.apk" dist/
echo
echo "  dist/thor-stickfx-tile-v$VERSION.apk ($(du -h "dist/thor-stickfx-tile-v$VERSION.apk" | cut -f1))"
