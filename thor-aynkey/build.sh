#!/usr/bin/env bash
# Package the Magisk module. Sync, test, then zip - in that order.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE"

VERSION=$(awk -F= '/^version=/{print $2}' magisk/module.prop)

echo "== sync =="
cp aynkeyd magisk/system/bin/aynkeyd
chmod +x magisk/system/bin/aynkeyd
echo "  aynkeyd -> magisk/system/bin/"

echo
echo "== tests =="
./tests/test_parse.sh  || { echo "build aborted: parser tests failed"; exit 1; }
./tests/test_log.sh    || { echo "build aborted: log tests failed"; exit 1; }
# Runs the real daemon against a fake getevent, so it takes ~16s - the long
# press alone is 2.6s of wall clock and there are several. Worth it: it is the
# only coverage of the timer, the arbiter and the dispatch.
./tests/test_live.sh   || { echo "build aborted: live-path tests failed"; exit 1; }
./tests/test_module.sh || { echo "build aborted: module tests failed"; exit 1; }

echo
echo "== package =="
mkdir -p dist
ZIP="dist/thor-aynkey-$VERSION.zip"
rm -f "$ZIP"
( cd magisk && zip -qr "../$ZIP" . ) || exit 1
echo "  $ZIP ($(du -h "$ZIP" | cut -f1))"
