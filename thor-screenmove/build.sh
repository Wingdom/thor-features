#!/usr/bin/env bash
# Build thor-screenmove.
#
# Order matters: sync first, then test, then package. A test asserting "the
# shipped copy matches source" can only pass if the sync has already happened,
# and packaging before testing would ship a zip the tests never saw.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"

VERSION=$(sed -n 's/^version=//p' magisk/module.prop)
[ -n "$VERSION" ] || { echo "build: no version in module.prop" >&2; exit 1; }

echo "== sync =="
mkdir -p magisk/system/bin
cp screenmove magisk/system/bin/screenmove
chmod 755 magisk/system/bin/screenmove
echo "   screenmove -> magisk/system/bin/screenmove"

echo "== test =="
bash tests/test_parse.sh  || { echo "build: parser tests failed" >&2; exit 1; }
bash tests/test_module.sh || { echo "build: module tests failed" >&2; exit 1; }

echo "== package =="
mkdir -p dist
ZIP="dist/thor-screenmove-$VERSION.zip"
rm -f "$ZIP"
( cd magisk && zip -qr "../$ZIP" . -x '.*' ) || { echo "build: zip failed" >&2; exit 1; }

# Verify the archive rather than trusting zip's exit code.
for need in module.prop customize.sh uninstall.sh system/bin/screenmove; do
    unzip -l "$ZIP" | grep -q " $need\$" \
      || { echo "build: $need missing from $ZIP" >&2; exit 1; }
done
echo "   $ZIP"
unzip -l "$ZIP" | tail -n +4 | head -n -2 | awk '{printf "     %s\n", $4}'
echo "== done =="
