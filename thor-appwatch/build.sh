#!/usr/bin/env bash
# Package the Magisk module. Sync, test, then zip - in that order.
#
# The order is load-bearing: a test asserting "the shipped copy matches
# source" can only pass if the sync has already happened, and a build that
# packaged before testing would ship a zip whose tests never ran.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

VERSION=$(awk -F= '/^version=/{print $2}' magisk/module.prop)

echo "== sync =="
mkdir -p magisk/system/bin
cp appwatch magisk/system/bin/appwatch
chmod +x magisk/system/bin/appwatch
echo "  appwatch -> magisk/system/bin/"

echo
echo "== tests =="
./tests/test_logic.sh  || { echo "build aborted: logic tests failed"; exit 1; }
./tests/test_module.sh || { echo "build aborted: module tests failed"; exit 1; }

echo
echo "== package =="
mkdir -p dist
ZIP="dist/thor-appwatch-$VERSION.zip"
rm -f "$ZIP"
( cd magisk && zip -qr "../$ZIP" . ) || exit 1
echo "  $ZIP ($(du -h "$ZIP" | cut -f1))"
