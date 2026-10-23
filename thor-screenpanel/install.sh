#!/usr/bin/env bash
# Install the panel: the APK, and the AYN button action script that opens it.
#
# The APK is a normal user-app install rather than a Magisk system app, the
# same as thor-screenmove-tile and thor-fan-tile. The action script is the
# join between this app and thor-aynkey: aynkeyd detects the press, this
# script decides what a press means. It lives in /data/adb rather than in
# either module's overlay so the two modules never contend for a path.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

APK=dist/thor-screenpanel.apk
[ -f "$APK" ] || { echo "no $APK - run ./build.sh first"; exit 1; }

echo "== prerequisites =="
for cmd in screenblank screenmove aynkeyd; do
    if adb shell "su -c 'which $cmd'" </dev/null 2>/dev/null | grep -q .; then
        echo "  ok      $cmd"
    else
        echo "  MISSING $cmd - install its module first"
        exit 1
    fi
done

echo
echo "== apk =="
adb install -r "$APK" </dev/null 2>&1 | tail -1

echo
echo "== action script =="
adb push action.sh /data/local/tmp/thor-action.sh </dev/null >/dev/null 2>&1 || exit 1
adb shell "su -c '
    mkdir -p /data/adb/thor-aynkey
    cp /data/local/tmp/thor-action.sh /data/adb/thor-aynkey/action.sh
    chmod 755 /data/adb/thor-aynkey/action.sh
    rm -f /data/local/tmp/thor-action.sh
    sh -n /data/adb/thor-aynkey/action.sh'" </dev/null 2>&1 \
    || { echo "  action script failed to install or does not parse"; exit 1; }
echo "  installed /data/adb/thor-aynkey/action.sh"

echo
echo "== restart the key daemon so it picks the script up =="
adb shell "su -c 'aynkeyd stop; aynkeyd start; aynkeyd status'" </dev/null 2>&1

echo
echo "Short press: open/close the panel on the bottom screen."
echo "Hold 2s:     toggle the bottom screen."
