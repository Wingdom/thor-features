#!/usr/bin/env bash
# Tests for the Magisk module's packaging and its revert path. No device.
#
# Written against behaviour rather than text wherever possible: two earlier
# module tests failed on correct code because they grepped for literal strings
# that the script spelled differently.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); cd "$HERE/.."

pass=0; fail=0
check() {
    local label=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        pass=$((pass+1)); printf '  ok    %s\n' "$label"
    else
        fail=$((fail+1)); printf '  FAIL  %s\n       expected [%s] got [%s]\n' "$label" "$expected" "$actual"
    fi
}

echo "screenblank module tests"

# The shipped copy must be the source. The build syncs before testing, so a
# mismatch here means the sync was skipped or the source changed mid-build.
check "shipped script matches source" "same" \
      "$(cmp -s screenblank magisk/system/bin/screenblank && echo same || echo different)"

check "shipped script is executable" "yes" \
      "$([ -x magisk/system/bin/screenblank ] && echo yes || echo no)"

for f in magisk/module.prop magisk/customize.sh magisk/uninstall.sh; do
    check "$f exists" "yes" "$([ -f "$f" ] && echo yes || echo no)"
done

# Every shell file must parse. A syntax error in uninstall.sh would only
# surface at uninstall time, which is the worst moment to find one.
for f in screenblank magisk/customize.sh magisk/uninstall.sh; do
    check "$f is valid shell" "ok" "$(sh -n "$f" 2>/dev/null && echo ok || echo broken)"
done

# --- the revert path, simulated against a fake sysfs -------------------------
# uninstall.sh is the one script that must work when everything else is gone.
# Run it for real against a fake tree and assert the nodes came back.
tmp=$(mktemp -d)
mkdir -p "$tmp/sys/class/backlight/panel0-backlight" \
         "$tmp/sys/class/backlight/panel1-backlight" \
         "$tmp/sys/class/input/input28/device" \
         "$tmp/sys/class/input/input29/device"
echo 1 > "$tmp/sys/class/backlight/panel0-backlight/bl_power"
echo 1 > "$tmp/sys/class/backlight/panel1-backlight/bl_power"
printf 'fts_ts_secondary\n' > "$tmp/sys/class/input/input28/name"
printf 'fts_ts\n'           > "$tmp/sys/class/input/input29/name"
echo 0 > "$tmp/sys/class/input/input28/device/fts_irq"
echo 0 > "$tmp/sys/class/input/input29/device/fts_irq"

# Rewrite the absolute paths to point at the fake tree, changing nothing else.
sed "s#/sys/#$tmp/sys/#g; s#/data/adb/#$tmp/data/adb/#g" magisk/uninstall.sh > "$tmp/uninstall.sh"
sh "$tmp/uninstall.sh" >/dev/null 2>&1

check "uninstall relights panel0" "0" "$(cat "$tmp/sys/class/backlight/panel0-backlight/bl_power")"
check "uninstall relights panel1" "0" "$(cat "$tmp/sys/class/backlight/panel1-backlight/bl_power")"
check "uninstall re-enables primary touch"   "1" "$(cat "$tmp/sys/class/input/input29/device/fts_irq")"
check "uninstall re-enables secondary touch" "1" "$(cat "$tmp/sys/class/input/input28/device/fts_irq")"

# And it must be safe to run twice, and safe when the nodes are already fine.
sh "$tmp/uninstall.sh" >/dev/null 2>&1
check "uninstall is idempotent" "0" "$(cat "$tmp/sys/class/backlight/panel1-backlight/bl_power")"

# It must not fail when the hardware is absent - an uninstall on a device
# where the nodes have gone must still complete.
rm -rf "$tmp/sys"
sh "$tmp/uninstall.sh" >/dev/null 2>&1
check "uninstall succeeds with no sysfs at all" "0" "$?"
rm -rf "$tmp"

# --- the install-time guard --------------------------------------------------
# customize.sh must resolve the touch devices by name, not by index. Assert
# behaviourally: no bare input<number> path may appear outside a glob.
check "customize.sh hardcodes no input index" "" \
      "$(grep -oE '/sys/class/input/input[0-9]+' magisk/customize.sh | sort -u | tr '\n' ' ')"
check "screenblank hardcodes no input index" "" \
      "$(grep -oE '/sys/class/input/input[0-9]+' screenblank | sort -u | tr '\n' ' ')"

# The module must never write a thermal or battery node. This is the one-rule
# guard of this project, asserted mechanically.
check "no thermal or battery node is written" "" \
      "$(grep -hoE '> *"?/sys/(class/(thermal|power_supply|qcom-battery)|devices/virtual/thermal)[^ "]*' \
          screenblank magisk/customize.sh magisk/uninstall.sh | sort -u | tr '\n' ' ')"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
