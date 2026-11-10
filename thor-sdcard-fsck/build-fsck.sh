#!/usr/bin/env bash
# Build fsck.exfat (exfatprogs 1.1.1, arm64) from upstream source.
#
# Why this exists: the module used to ship AYN's binary, lifted from the stock
# OTA. exfatprogs is GPL-2.0, so distributing it carries an obligation to make
# the corresponding source available -- and we could not honestly claim AYN's
# build was reproducible from upstream, because it was not built here and AYN
# publishes no source. Building it ourselves makes the source pointer simply
# true.
#
# Verified 2026-10-29 on the Thor's 461 GB / 161k-file card: our build and
# AYN's are a dead heat (8.91 s / 8.88 s vs 9.22 s / 8.79 s, cold cache, -n),
# produce byte-identical reports, and both report "exfatprogs version : 1.1.1".
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VER=1.1.1
TARBALL_SHA=2a1574110e0d9ba11524744cac39454fb4084277cfa8ae53406a066600b825c8
# $ANDROID_NDK wins if set; otherwise take the newest NDK installed under the
# SDK. Do not pin a version here. This script hardcoded r26's path and an SDK
# cleanup on 2026-11-09 removed exactly that directory, leaving only r29 --
# discovery means the next cleanup cannot strand the build again.
SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/android}}"
if [ -n "${ANDROID_NDK:-}" ]; then
    NDK="$ANDROID_NDK"
else
    NDK=$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1)
    NDK="${NDK%/}"
fi
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

# Our own floor, not the NDK's -- r29 goes down to API 21. 24 is what the
# binary has always been built against and it runs on Android 7 up, so there
# is no reason to move it.
API=24

if [ -z "$NDK" ]; then
    echo "no NDK found under $SDK/ndk -- install one with:"
    echo "  sdkmanager 'ndk;<version>'"
    echo "or point \$ANDROID_NDK at an existing install"
    exit 1
fi
if [ ! -x "$TC/aarch64-linux-android$API-clang" ]; then
    echo "no API-$API clang in $NDK"
    echo "  looked for: $TC/aarch64-linux-android$API-clang"
    echo "  this NDK offers:" $(ls "$TC" 2>/dev/null \
        | sed -n 's/^aarch64-linux-android\([0-9]\{1,\}\)-clang$/\1/p' \
        | sort -n | tr '\n' ' ')
    exit 1
fi
echo "== NDK: $NDK (API $API)"

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
URL="https://github.com/exfatprogs/exfatprogs/releases/download/$VER/exfatprogs-$VER.tar.xz"
echo "== fetching $URL"
curl -fsSL --max-time 120 -o src.tar.xz "$URL" || { echo "download failed"; exit 1; }
GOT=$(sha256sum src.tar.xz | cut -d' ' -f1)
[ "$GOT" = "$TARBALL_SHA" ] || { echo "sha256 mismatch: got $GOT want $TARBALL_SHA"; exit 1; }
echo "   sha256 ok"

tar -xJf src.tar.xz && cd "exfatprogs-$VER"
export CC="$TC/aarch64-linux-android$API-clang"
export AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip"
export CFLAGS="-O2 -fPIE" LDFLAGS="-pie"
echo "== configure"
./configure --host=aarch64-linux-android --disable-shared >/dev/null || { echo "configure failed"; exit 1; }
echo "== make"
make -j"$(nproc)" >/dev/null || { echo "make failed"; exit 1; }
"$STRIP" fsck/fsck.exfat
cp fsck/fsck.exfat "$HERE/fsck.exfat"
chmod 755 "$HERE/fsck.exfat"
echo "== done: $HERE/fsck.exfat  ($(stat -c%s "$HERE/fsck.exfat") bytes)"
sha256sum "$HERE/fsck.exfat"
echo
echo "Note: the binary is not bit-for-bit reproducible -- the NDK embeds a build"
echo "id. Rebuilding gives a working, equivalent binary, not the same sha256."
