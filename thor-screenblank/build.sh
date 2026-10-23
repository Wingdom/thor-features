#!/usr/bin/env bash
# Package the Magisk module. Syncs the source script into magisk/, runs the
# host tests, and only then zips - in that order, because a test asserting the
# shipped copy matches source can only pass if the sync happened first.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

VERSION=$(awk -F= '/^version=/{print $2}' magisk/module.prop)

echo "== sync =="
cp screenblank magisk/system/bin/screenblank
chmod +x magisk/system/bin/screenblank
echo "  screenblank -> magisk/system/bin/"

echo
echo "== tests =="
./tests/test_parse.sh || { echo "build aborted: tests failed"; exit 1; }
./tests/test_module.sh || { echo "build aborted: module tests failed"; exit 1; }

echo
echo "== package =="
mkdir -p dist
ZIP="dist/thor-screenblank-$VERSION.zip"
rm -f "$ZIP"
( cd magisk && zip -qr "../$ZIP" . ) || exit 1
echo "  $ZIP ($(du -h "$ZIP" | cut -f1))"
