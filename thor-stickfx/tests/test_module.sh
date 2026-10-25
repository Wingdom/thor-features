#!/usr/bin/env bash
# Structural tests for the thor-stickfx Magisk module. Host-side, no device.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
P="$(cd "$HERE/.." && pwd)"
M="$P/magisk"
pass=0; fail=0
ok()  { pass=$((pass+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s\n     %s\n' "$1" "${2:-}"; }
# the script with comments stripped: invariants documented in prose must never
# count as violations (the lesson from thor-fanctl's test suite)
code() { sed 's/#.*//' "$P/stickfx"; }

echo "magisk module structure"
for f in module.prop customize.sh service.sh uninstall.sh system/bin/stickfx; do
  [ -f "$M/$f" ] && ok "present: $f" || bad "present: $f" "missing"
done
for k in id name version versionCode author description; do
  grep -q "^$k=" "$M/module.prop" && ok "module.prop has $k" || bad "module.prop has $k"
done
[ "$(awk -F= '/^id=/{print $2}' "$M/module.prop")" = thor-stickfx ] \
  && ok "module id is thor-stickfx" || bad "module id is thor-stickfx"
for f in customize.sh service.sh uninstall.sh system/bin/stickfx; do
  sh -n "$M/$f" 2>/dev/null && ok "shell syntax: $f" || bad "shell syntax: $f"
done
if cmp -s "$P/stickfx" "$M/system/bin/stickfx"; then
  ok "shipped stickfx matches source"
else
  bad "shipped stickfx matches source" "run ./build.sh to refresh"
fi

echo
echo "safety: install-time gating"
grep -q 'abort' "$M/customize.sh" && ok "customize.sh can abort" || bad "customize.sh can abort"
grep -q 'odin2thor' "$M/customize.sh" && ok "customize.sh checks the device" || bad "customize.sh checks the device"
# must gate on the trigger actually bound by default, not some other one
grep -q 'usb-online' "$M/customize.sh" \
  && ok "customize.sh checks the default trigger (usb-online) exists" \
  || bad "customize.sh checks the default trigger (usb-online) exists"
# The default must not be a battery-STATUS trigger: measured 2026-10-23, every
# one of them goes dark while charging control holds the battery at its limit,
# because the kernel reports "Not charging" there.
if grep -qE "^CHARGE_TRIGGER=.*echo usb-online\)\}" "$P/stickfx"; then
  ok "stickfx defaults to usb-online"
else
  bad "stickfx defaults to usb-online" "a battery-* default goes dark at the charging-control hold"
fi
if grep -qE "^CHARGE_TRIGGER=.*echo battery-" "$P/stickfx"; then
  bad "default is not a battery-status trigger" "goes dark at the hold point"
else
  ok "default is not a battery-status trigger"
fi
# and the gate must abort on the default, not merely warn
if awk '/usb-online/,/fi/' "$M/customize.sh" | grep -q abort; then
  ok "install aborts if the default trigger is missing"
else bad "install aborts if the default trigger is missing"; fi
# online-based triggers must seed from usb/online, not battery status
if code | awk '/^ *usb\)/,/;;/' | grep -q 'seed_state online'; then
  ok "usb mode seeds from usb/online"
else bad "usb mode seeds from usb/online" "edge-driven: it would come up dark"; fi
if code | awk '/^ *charge\)/,/;;/' | grep -q 'seed_state online'; then
  ok "charge picks the online seed for *-online triggers"
else bad "charge picks the online seed for *-online triggers"; fi
grep -qE 'stick' "$M/customize.sh" && ok "customize.sh checks for stick LEDs" || bad "customize.sh checks for stick LEDs"

# Magisk sources customize.sh BEFORE extraction, so set_perm on system/bin/*
# there targets a path that does not exist yet (the v0.1.0 bug). It belongs in
# set_permissions(), which Magisk calls after extracting.
if awk '/^set_permissions\(\)/{f=1} f&&/^}/{f=0} f' "$M/customize.sh" | grep -q 'system/bin/stickfx'; then
  ok "set_perm on the binary is inside set_permissions()"
else
  bad "set_perm on the binary is inside set_permissions()" "it would run before extraction"
fi
if sed '/^set_permissions()/,/^}/d' "$M/customize.sh" | grep -q 'set_perm.*system/bin'; then
  bad "no set_perm on system/bin at top level" "runs before extraction"
else
  ok "no set_perm on system/bin at top level"
fi

echo
echo "safety: uninstall restores"
grep -qE '(\$STICKFX|stickfx)"? +off' "$M/uninstall.sh" \
  && ok "uninstall.sh restores the LEDs" || bad "uninstall.sh restores the LEDs"
grep -q 'rm -rf /data/adb/thor-stickfx' "$M/uninstall.sh" \
  && ok "uninstall.sh removes state" || bad "uninstall.sh removes state"

echo
echo "safety: boot ordering"
grep -q 'capture --force' "$M/service.sh" && ok "service.sh force-captures at boot" || bad "service.sh force-captures at boot"
# AynParts writes these LEDs at boot. Capturing before that would record a
# pre-AynParts state and `off` would restore something the user never chose.
grep -q 'sys.boot_completed' "$M/service.sh" \
  && ok "service.sh waits for boot_completed" || bad "service.sh waits for boot_completed"
if awk '/sys.boot_completed/{b=NR} /capture --force/{c=NR} END{exit (b&&c&&b<c)?0:1}' "$M/service.sh"; then
  ok "service.sh waits BEFORE capturing"
else bad "service.sh waits BEFORE capturing" "capture must follow AynParts' boot write"; fi
# a fresh install must be inert
if awk '/^ *case "\$MODE"/,/esac/' "$M/service.sh" | grep -q '\*) echo "nothing to apply"'; then
  ok "service.sh does nothing when no mode is set"
else bad "service.sh does nothing when no mode is set"; fi

echo
echo "REGRESSION: never hardcode a sysfs index or LED name"
# cooling_device34 -> 35 across a reboot taught this once already (see the
# top-level README). LED names are discovered; AynParts also references
# left:strip / left:stick group nodes that do not exist on the Thor.
hits=$(for f in "$P/stickfx" "$P/stickfxlog.sh" "$M"/*.sh "$M"/system/bin/stickfx; do
         [ -f "$f" ] || continue
         sed 's/#.*//' "$f" | grep -onE '(cooling_device|thermal_zone)[0-9]+|(left|right):stick:[0-9]' \
           | sed "s|^|$(basename "$f"):|"
       done)
[ -z "$hits" ] && ok "no hardcoded node index or LED name" \
               || bad "no hardcoded node index or LED name" "$(printf '%s' "$hits" | head -3)"

[ -f "$P/stickfxlog.sh" ] && ok "stickfxlog.sh present" || bad "stickfxlog.sh present"
sh -n "$P/stickfxlog.sh" 2>/dev/null && ok "shell syntax: stickfxlog.sh" || bad "shell syntax: stickfxlog.sh"

echo
echo "REGRESSION: trigger lists must not be word-split"
# The active entry is bracketed, and "[battery-charging-blink-full-solid]" is a
# valid glob character class. `for x in $list` also does PATHNAME EXPANSION, so
# with cwd "/" it expanded to the single-char entry "d" and the bracket vanished.
# On hardware the trigger was correctly bound while the script reported all 8
# writes as failed.
if code | awk '/^(active_trigger|trigger_supported)\(\)/{f=1} f&&/^}/{f=0} f' | grep -qE 'for [A-Za-z_]+ in \$[0-9]'; then
  bad "parsers do not word-split the trigger list" "found 'for x in \$1' in a parser"
else
  ok "parsers do not word-split the trigger list"
fi
# ${x#*[} is an invalid pattern (unterminated bracket expression)
if code | grep -qE '\$\{[A-Za-z_0-9]+#\*\[\}'; then
  bad "bracket patterns are escaped" "found an unescaped \${x#*[}"
else
  ok "bracket patterns are escaped"
fi

echo
echo "REGRESSION: BIND the trigger first, then seed -- and never seed zero"
# Two kernel behaviours box this in, pulling in opposite directions:
#   led_trigger_set()  tears down the old trigger with LED_OFF, so BINDING
#                      resets brightness to 0 and wipes any earlier seed.
#   brightness_store() calls led_trigger_remove() on LED_OFF, so writing a ZERO
#                      brightness after binding UNBINDS the trigger.
# Both were found on hardware: seeding after binding with zero reverted every
# trigger to `none`; seeding before binding left battery-charging-or-full bound
# but dark. The only correct shape is bind -> write non-zero seed.
if code | awk '/^ *charge\)/,/;;/' | awk '/set_trigger/{t=NR} /seed_state/{s=NR} END{exit (s&&t&&t<s)?0:1}'; then
  ok "charge binds before seeding"
else
  bad "charge binds before seeding" "binding would wipe an earlier seed"
fi
if code | awk '/^ *breathe\)/,/;;/' | awk '/set_trigger/{t=NR} /brightness/{s=NR} END{exit (s&&t&&t<s)?0:1}'; then
  ok "breathe binds before seeding"
else
  bad "breathe binds before seeding"
fi
# seed_state must bail out rather than write a zero
if code | awk '/^seed_state\(\)/{f=1} f&&/^}/{f=0} f' \
   | awk '/= 0 \]/{g=NR} /> *"\$d\/brightness"/{w=NR} END{exit (g&&w&&g<w)?0:1}'; then
  ok "seed_state returns early when the seed is zero"
else
  bad "seed_state returns early when the seed is zero" "a zero write would unbind the trigger"
fi

echo
echo "REGRESSION: the colour channel belongs to AynParts"
# multi_intensity may only be written from set_color, which only runs when
# --color was given explicitly.
# Writes go through wr(), not a shell redirect, so match the real call shape.
# The only legitimate writers are set_color() (an explicit --color takeover) and
# restore() (putting AynParts' recorded values back). Anything else would mean
# we are holding the colour channel without being asked to.
writers=$(code | awk '
  /^[a-z_]+\(\) \{/ { fn=$1; sub(/\(\)/,"",fn) }
  /multi_intensity/ && (/wr /||/> *"?\$/) { print fn }' | sort -u | tr '\n' ' ')
writers=$(echo $writers)
if [ "$writers" = "restore set_color" ] || [ "$writers" = "set_color restore" ]; then
  ok "multi_intensity written only by set_color and restore"
else
  bad "multi_intensity written only by set_color and restore" "writers: '$writers'"
fi
# and the test above must not be vacuous: there must BE write sites
nw=$(code | grep -cE 'wr "\$[a-z_]+/multi_intensity"')
[ "$nw" -ge 2 ] && ok "found $nw multi_intensity write sites (test is not vacuous)" \
                || bad "multi_intensity write sites found" "got $nw -- the matcher is broken"
# `off` must hand the colour channel back
if code | awk '/^ *off\)/,/;;/' | grep -q 'rm -f "\$COLOR_FILE"'; then
  ok "off drops the persisted colour takeover"
else bad "off drops the persisted colour takeover" "a later boot would re-take it"; fi

echo
printf '  %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
