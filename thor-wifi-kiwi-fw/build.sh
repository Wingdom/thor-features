#!/usr/bin/env bash
# Package the kiwi firmware overlay as a flashable Magisk zip.
#
# Runs the tests first and refuses to package if they fail, like every other
# component. There is no compile step: the payload is a firmware blob, so the
# tests are the only thing standing between a typo and a module that installs
# cleanly and fixes nothing.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

echo "== module structure + payload tests =="
./tests/test_module.sh || { echo "build aborted: module tests failed"; exit 1; }
echo
echo "== packaging =="
VER=$(awk -F= '/^version=/{print $2}' magisk/module.prop)
OUT="$HERE/dist/thor-wifi-kiwi-fw-$VER.zip"
mkdir -p "$HERE/dist"; rm -f "$OUT"
( cd magisk && zip -qr "$OUT" . -x '.*' )
echo "  $OUT  ($(du -h "$OUT" | cut -f1))"
