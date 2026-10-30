#!/usr/bin/env bash
# Package the exFAT fsck module as a flashable Magisk zip.
#
# Runs the tests first and refuses to package if they fail, like every other
# component. The binary itself is built separately by ./build-fsck.sh, which
# needs the NDK -- it is not part of this build, so a clean checkout (and CI)
# packages the committed binary rather than re-downloading upstream.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

[ -f fsck.exfat ] || { echo "no fsck.exfat here -- run ./build-fsck.sh first"; exit 1; }
mkdir -p magisk/system/bin
cp fsck.exfat magisk/system/bin/fsck.exfat
chmod 755 magisk/system/bin/fsck.exfat magisk/repair.sh

echo "== module structure + payload tests =="
./tests/test_module.sh || { echo "build aborted: module tests failed"; exit 1; }
echo
echo "== packaging =="
VER=$(awk -F= '/^version=/{print $2}' magisk/module.prop)
OUT="$HERE/dist/thor-sdcard-fsck-$VER.zip"
mkdir -p "$HERE/dist"; rm -f "$OUT"
( cd magisk && zip -qr "$OUT" . -x '.*' )
echo "  $OUT  ($(du -h "$OUT" | cut -f1))"
