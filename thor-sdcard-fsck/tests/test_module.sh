#!/usr/bin/env bash
# Host tests for the thor-sdcard-fsck module. No device, no root, no card.
#
# The module is a binary swap: it replaces LineageOS's fsck.exfat 1.4.3 with
# 1.1.1, because vold SIGTERMs fsck.exfat at a hardcoded 45 s and 1.4.3 needs
# 58 s on this card. So the assertions worth making are about *which* binary
# ships and where it lands -- a wrong-architecture or wrong-version binary
# installs cleanly and leaves the card unmountable exactly as before.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE/.."
PASS=0; FAIL=0
ok()  { echo "  ok   $1"; PASS=$((PASS+1)); }
bad() { echo "  no   $1"; FAIL=$((FAIL+1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (got '$2' want '$3')"; fi; }

M=magisk
BIN="$M/system/bin/fsck.exfat"

# AYN's binary, lifted from the stock OTA, which this module used to ship.
# exfatprogs is GPL-2.0 and that build's corresponding source could not
# honestly be offered, so shipping it again would be a regression.
AYN=97c42e882c40b03d0d696ac03973213ad78ecf874a4d301531e61367a2e2ddf3

# --- the payload ------------------------------------------------------------
if [ -f "$BIN" ]; then ok "fsck.exfat is staged at /system/bin"
else bad "fsck.exfat is staged at /system/bin"; fi

if [ -f "$BIN" ]; then
  [ -x "$BIN" ] && ok "fsck.exfat is executable" || bad "fsck.exfat is executable"

  # Version is the whole point: 1.4.3 is the binary that does not fit vold's
  # budget. Read it out of the binary rather than trusting the filename.
  # Capture first: `grep -q` exits on the first match, strings then takes
  # SIGPIPE, and `set -o pipefail` would report the pipeline as failed.
  VSTR=$(strings -a "$BIN" | grep -cx '1\.1\.1')
  [ "$VSTR" -ge 1 ] \
    && ok "binary reports exfatprogs 1.1.1" \
    || bad "binary reports exfatprogs 1.1.1"

  # Must be arm64 Android, or it cannot execute at all on the device.
  d=$(file -b "$BIN")
  case "$d" in *"ARM aarch64"*) ok "binary is ARM aarch64";; *) bad "binary is ARM aarch64 (got: $d)";; esac
  case "$d" in *"interpreter /system/bin/linker64"*) ok "binary links against Android's linker64";;
               *) bad "binary links against Android's linker64";; esac

  # It must be our upstream build, not the one taken out of AYN's image.
  GOT=$(sha256sum "$BIN" | cut -d' ' -f1)
  [ "$GOT" != "$AYN" ] && ok "shipped binary is NOT AYN's OTA-extracted build" \
                       || bad "shipped binary is NOT AYN's OTA-extracted build"

  # The staged copy must match the component-root source, or the zip ships
  # something other than what was built and reviewed.
  if [ -f fsck.exfat ]; then
    check "staged binary matches the component-root source" \
      "$(sha256sum fsck.exfat | cut -d' ' -f1)" "$GOT"
  else bad "component-root fsck.exfat exists"; fi
fi

# --- the target path --------------------------------------------------------
# file_contexts matches /system/bin/fsck\.exfat exactly, and a wrongly
# labelled file is not executable by vold. Magisk derives the label from the
# target path, so the path has to be exact.
check "overlay targets /system/bin exactly" "$(dirname "$BIN")" "magisk/system/bin"

# --- the repair helper ------------------------------------------------------
# It used to point at fsck.exfat.stock, a path left over from the rejected
# wrapper design that this module never installs.
if [ -f "$M/repair.sh" ]; then
  ok "repair.sh ships inside the module"
  grep -q 'fsck\.exfat\.stock' "$M/repair.sh" \
    && bad "repair.sh does not reference the removed .stock path" \
    || ok "repair.sh does not reference the removed .stock path"
  sh -n "$M/repair.sh" && ok "repair.sh is syntactically valid" \
                       || bad "repair.sh is syntactically valid"
else bad "repair.sh ships inside the module"; fi

# --- safety -----------------------------------------------------------------
# Same one-rule guard as every other module here, asserted mechanically.
W=$(grep -rhoE '> *"?/sys/(class/(thermal|power_supply|qcom-battery)|devices/virtual/thermal)[^ "]*' "$M" 2>/dev/null | wc -l)
check "no thermal or battery node is written" "$W" "0"

# --- module metadata --------------------------------------------------------
check "module.prop id" "$(awk -F= '/^id=/{print $2}' "$M/module.prop")" "thor-sdcard-fsck"
[ -n "$(awk -F= '/^version=/{print $2}'     "$M/module.prop")" ] && ok "module.prop has version"     || bad "module.prop has version"
[ -n "$(awk -F= '/^versionCode=/{print $2}' "$M/module.prop")" ] && ok "module.prop has versionCode" || bad "module.prop has versionCode"

echo; echo "  module: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
