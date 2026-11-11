#!/usr/bin/env bash
# Build the stick-calibration APK. No Gradle, no Kotlin -- aapt2 + javac + d8,
# the same recipe as the other apps here.
#
# Runs the host unit tests first and refuses to package if they fail. That is
# not ceremony: this app writes a hardware calibration, and the solver is pure
# arithmetic, so a wrong limit at one angle has no business reaching the device
# when it can be caught here in under a second.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

SDK="${ANDROID_SDK:-$HOME/android}"
BT="$SDK/build-tools/37.0.0"
AJ="$SDK/platforms/android-37.0/android.jar"   # Android 17, matching the device
PKG=com.thorfeatures.stickcal
MIN_SDK=31          # the system_* dynamic colour resources the theme uses
TARGET_SDK=37       # Android 17. Play Protect on this device refuses installs
                    # targeting anything older, reported as
                    # INSTALL_FAILED_VERIFICATION_FAILURE.

# versionCode must be an integer and must only ever increase, because Android
# refuses to install an APK whose code is lower than the installed one. Derived
# from the semver so the two cannot drift: 0.1.0 -> 100.
VERSION=0.1.0
VERSION_CODE=$(echo "$VERSION" | awk -F. '{ printf "%d", $1*10000 + $2*100 + $3 }')

for t in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$AJ"; do
    [ -e "$t" ] || { echo "missing: $t"; exit 1; }
done

rm -rf out; mkdir -p out/res out/classes out/dex out/test out/gen
PKG_PATH=com/thorfeatures/stickcal

# Borrowed from thor-screenpanel, compiled in rather than copied. RootProtocol
# is how a long-lived `su` process is fed commands and told where each reply
# ends -- one behaviour, and a second copy of it would be a second thing to
# keep right. The panel's version is installed, in daily use and host-tested.
# If it moves, this build fails loudly here rather than this app silently
# framing its commands a second way.
SHARED_SRC="../thor-screenpanel/src/com/thorfeatures/screenpanel/RootProtocol.java"
for f in $SHARED_SRC; do
    [ -f "$f" ] || { echo "missing shared source: $f"; exit 1; }
done

echo "== unit tests (host, no device) =="
# Only the pure classes. Anything that imports android.* is excluded by name
# rather than by a wildcard, so adding a class with a device dependency cannot
# quietly drop itself out of the test build.
PURE="RawSample Stick Calibration Transfer GateMap RestMeasure RestAnalysis \
      GateSolver VerifyMap PrefsXml DeviceCommands Phase AxisMap Report"
SRCS=""
for c in $PURE; do
    [ -f "src/$PKG_PATH/$c.java" ] || { echo "missing: src/$PKG_PATH/$c.java"; exit 1; }
    SRCS="$SRCS src/$PKG_PATH/$c.java"
done
javac --release 17 -nowarn -d out/test $SRCS \
    "test/$PKG_PATH/GateFixture.java" "test/$PKG_PATH/TestMain.java" \
    || { echo "test compile failed"; exit 1; }
java -cp out/test com.thorfeatures.stickcal.TestMain \
    || { echo "build aborted: unit tests failed"; exit 1; }

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

# --- signing ------------------------------------------------------------------
# Resolution order:
#   1. $THOR_KEYSTORE / $THOR_KEYSTORE_PASS -- CI, from encrypted secrets.
#   2. ../../thor/keystore/thor-release.jks -- the real release key, kept
#      OUTSIDE the repo: .gitignore is not protection, because `git clean -xfd`
#      deletes ignored files, and losing this key means these apps can never be
#      updated again. The relative depth is load-bearing -- this resolves only
#      when the component sits one level under Ayn/, which is why worktrees go
#      beside thor-features rather than nested below it.
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
            -dname "CN=thor-stickcal, O=local, C=US" >/dev/null 2>&1 \
            || { echo "keytool failed"; exit 1; }
    fi
    echo "  signing: throwaway DEV key -- not for release"
fi
# --key-pass is deliberately omitted: apksigner's file: reader consumes the
# file for --ks-pass and then hits EOF looking for a second line for
# --key-pass. Store and key passwords are identical in every path above.
"$BT/apksigner" sign --ks "$KS" --ks-pass "$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --out "out/thor-stickcal-v$VERSION.apk" out/aligned.apk || exit 1
"$BT/apksigner" verify "out/thor-stickcal-v$VERSION.apk" 2>/dev/null && echo "  signature verified"

mkdir -p dist && rm -f dist/thor-stickcal-v*.apk && cp "out/thor-stickcal-v$VERSION.apk" dist/
echo
echo "  dist/thor-stickcal-v$VERSION.apk ($(du -h "dist/thor-stickcal-v$VERSION.apk" | cut -f1))"
