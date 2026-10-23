#!/usr/bin/env bash
# Build the Magisk module zip. Runs both test suites first and refuses on failure.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

# Sync the script into the module BEFORE testing: one of the module tests
# asserts the shipped copy matches the source, which can only pass post-sync.
cp stickfx magisk/system/bin/stickfx
chmod 755 magisk/system/bin/stickfx magisk/*.sh

echo "== unit tests (pure logic, host) =="
./tests/test_logic.sh  || { echo "build aborted: unit tests failed"; exit 1; }
echo
echo "== module structure tests =="
./tests/test_module.sh || { echo "build aborted: module tests failed"; exit 1; }
echo
echo "== packaging =="
VER=$(awk -F= '/^version=/{print $2}' magisk/module.prop)
OUT="$HERE/dist/thor-stickfx-$VER.zip"
mkdir -p "$HERE/dist"; rm -f "$OUT"
( cd magisk && zip -qr "$OUT" . -x '.*' )
echo "  $OUT  ($(du -h "$OUT" | cut -f1))"
unzip -l "$OUT" | tail -n +4 | head -n -2 | awk '{printf "    %s\n",$4}'
