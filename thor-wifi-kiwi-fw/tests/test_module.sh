#!/usr/bin/env bash
# Host tests for the thor-wifi-kiwi-fw module. No device, no root.
#
# This module has no logic -- its entire payload is one firmware blob at one
# path -- so the only things worth asserting are that the blob is the right
# blob and that it lands where the driver looks. Both are easy to get wrong
# and silent when wrong: the broken fallback blob is a plausible 6 MB file
# with the same name.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE/.."
PASS=0; FAIL=0
ok()  { echo "  ok   $1"; PASS=$((PASS+1)); }
bad() { echo "  no   $1"; FAIL=$((FAIL+1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (got '$2' want '$3')"; fi; }

M=magisk
BLOB="$M/system/vendor/firmware/kiwi/amss20.bin"

# The blob the driver needs, and the one it must NOT be. Both hashes are
# recorded in the README; the second is the modem-partition fallback that
# breaks 6 GHz SAE, which is the actual bug this module exists to fix.
GOOD=a4ea7e02c6d5273183776323aec917665cf10205f41bb94aa15e5713341da4ff
BROKEN=429fc931cc13bef5468a2b48f3de89f997d01bcb81db001f638ea0e6e2587547

# --- the payload ------------------------------------------------------------
if [ -f "$BLOB" ]; then ok "firmware blob is present at the overlay path"
else bad "firmware blob is present at the overlay path"; fi

if [ -f "$BLOB" ]; then
  GOT=$(sha256sum "$BLOB" | cut -d' ' -f1)
  check "blob is the known-working firmware" "$GOT" "$GOOD"
  [ "$GOT" != "$BROKEN" ] && ok "blob is NOT the broken modem fallback" \
                          || bad "blob is NOT the broken modem fallback"
  check "blob size matches the documented 6299712" "$(stat -c%s "$BLOB")" "6299712"
fi

# --- the path the driver actually reads -------------------------------------
# /vendor/firmware has load priority over /vendor/firmware_mnt, so the overlay
# only works at this exact path. A typo here is a module that installs cleanly
# and changes nothing.
[ -d "$M/system/vendor/firmware/kiwi" ] \
  && ok "overlay targets /vendor/firmware/kiwi" \
  || bad "overlay targets /vendor/firmware/kiwi"

# --- module metadata --------------------------------------------------------
check "module.prop id"          "$(awk -F= '/^id=/{print $2}'      "$M/module.prop")" "thor-wifi-kiwi-fw"
check "module.prop has version" "$(awk -F= '/^version=/{print $2}' "$M/module.prop" | grep -c .)" "1"
[ -n "$(awk -F= '/^versionCode=/{print $2}' "$M/module.prop")" ] \
  && ok "module.prop has versionCode" || bad "module.prop has versionCode"

# --- nothing else should be in the module ----------------------------------
# A file-overlay module with a script is a different kind of thing and would
# need different tests; assert it stays an overlay.
N=$(find "$M" -name '*.sh' | wc -l)
check "module ships no scripts (pure overlay)" "$N" "0"

# The blob must not also be tracked at the component root: two copies drift,
# and the one outside magisk/ would never be the one installed.
[ ! -f amss20.bin ] && ok "no stray duplicate blob at the component root" \
                    || bad "no stray duplicate blob at the component root"

echo; echo "  module: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
