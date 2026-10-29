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
NDK="${ANDROID_NDK:-$HOME/android/ndk/26.0.10792818}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=24          # NDK minimum; the binary runs on everything from Android 7 up

[ -x "$TC/aarch64-linux-android$API-clang" ] || { echo "no NDK clang at $TC"; exit 1; }

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
