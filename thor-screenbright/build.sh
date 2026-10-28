#!/usr/bin/env bash
# Package the Magisk module. Test, then zip.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

VERSION=$(awk -F= '/^version=/{print $2}' magisk/module.prop)

echo "== tests =="
./tests/test_overlay.sh || { echo "build aborted: tests failed"; exit 1; }

echo
echo "== package =="
mkdir -p dist
ZIP="dist/thor-screenbright-$VERSION.zip"
rm -f "$ZIP"
( cd magisk && zip -qr "../$ZIP" . ) || exit 1
echo "  $ZIP ($(du -h "$ZIP" | cut -f1))"
