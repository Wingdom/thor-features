#!/usr/bin/env bash
# Build the Magisk module zip. Runs both suites first and refuses on failure.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

# Sync before testing: a module test asserts the shipped copy matches source.
cp display-match magisk/system/bin/display-match
chmod 755 magisk/system/bin/display-match magisk/*.sh

echo "== unit tests (pure logic, host) =="
./tests/test_logic.sh  || { echo "build aborted: unit tests failed"; exit 1; }
echo
echo "== module structure + behaviour tests =="
./tests/test_module.sh || { echo "build aborted: module tests failed"; exit 1; }
echo
echo "== packaging =="
VER=$(awk -F= '/^version=/{print $2}' magisk/module.prop)
OUT="$HERE/dist/thor-display-match-$VER.zip"
mkdir -p "$HERE/dist"; rm -f "$OUT"
( cd magisk && zip -qr "$OUT" . -x '.*' )
echo "  $OUT  ($(du -h "$OUT" | cut -f1))"
unzip -l "$OUT" | tail -n +4 | head -n -2 | awk '{printf "    %s\n",$4}'
