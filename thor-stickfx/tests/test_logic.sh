#!/usr/bin/env bash
# Host-side unit tests for stickfx's pure logic. No device, no root, no network.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STICKFX_LIB=1 . "$HERE/../stickfx"

pass=0; fail=0
ok()  { pass=$((pass+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s\n     %s\n' "$1" "${2:-}"; }
eq()  { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "want '$3', got '$2'"; fi; }

echo "parse_color"
eq "stock teal 2BE0D8"        "$(parse_color 2BE0D8)"   "43 224 216"
eq "accepts leading #"        "$(parse_color '#2BE0D8')" "43 224 216"
eq "lowercase hex"            "$(parse_color 2be0d8)"   "43 224 216"
eq "pure red"                 "$(parse_color FF0000)"   "255 0 0"
eq "pure blue"                "$(parse_color 0000FF)"   "0 0 255"
eq "black"                    "$(parse_color 000000)"   "0 0 0"
eq "white"                    "$(parse_color FFFFFF)"   "255 255 255"
eq "AynParts default teal"    "$(parse_color 00A5B4)"   "0 165 180"
for junk in zzz 2BE0D 2BE0D8A '' '#' 'GGGGGG' '0x2BE0D8'; do
  if parse_color "$junk" >/dev/null 2>&1; then
    bad "rejects '$junk'" "it was accepted"
  else
    ok "rejects '$junk'"
  fi
done

echo
echo "breathe_delays"
eq "1200ms -> even split"     "$(breathe_delays 1200)"  "600 600"
eq "2000ms"                   "$(breathe_delays 2000)"  "1000 1000"
eq "odd period floors"        "$(breathe_delays 1001)"  "500 500"
eq "below min clamps up"      "$(breathe_delays 50)"    "100 100"
eq "at min"                   "$(breathe_delays 200)"   "100 100"
eq "above max clamps down"    "$(breathe_delays 99999)" "5000 5000"
eq "default when empty"       "$(breathe_delays)"       "600 600"
for junk in abc 12x -5 1.5; do
  if breathe_delays "$junk" >/dev/null 2>&1; then
    bad "rejects period '$junk'" "it was accepted"
  else
    ok "rejects period '$junk'"
  fi
done
# a clamped period must never produce a zero delay: that would mean "solid",
# silently turning a breathe request into a static LED
for ms in 200 201 999 10000; do
  p=$(breathe_delays "$ms"); on=${p% *}; off=${p#* }
  if [ "$on" -gt 0 ] && [ "$off" -gt 0 ]; then ok "non-zero delays at ${ms}ms"
  else bad "non-zero delays at ${ms}ms" "got '$p'"; fi
done

echo
echo "is_black  (an all-zero colour means a trigger drives an invisible LED)"
for c in "0 0 0" "0 0" "0" "0 0 0 0"; do
  if is_black "$c"; then ok "black: '$c'"; else bad "black: '$c'" "reported non-black"; fi
done
for c in "1 0 0" "0 0 255" "43 224 216" "255 255 255"; do
  if is_black "$c"; then bad "not black: '$c'" "reported black"; else ok "not black: '$c'"; fi
done

echo
echo "trigger_supported / active_trigger"
LIST="[none] rc-feedback timer transient usb-online battery-charging battery-full battery-charging-blink-full-solid"
for t in none timer usb-online battery-charging battery-charging-blink-full-solid; do
  if trigger_supported "$LIST" "$t"; then ok "advertises $t"; else bad "advertises $t"; fi
done
for t in nope battery charging- ""; do
  if trigger_supported "$LIST" "$t"; then bad "rejects '$t'" "accepted"; else ok "rejects '$t'"; fi
done
eq "active is the bracketed one"  "$(active_trigger "$LIST")" "none"
eq "active mid-list"              "$(active_trigger "none timer [battery-charging] usb-online")" "battery-charging"
eq "active last"                  "$(active_trigger "none timer [usb-online]")" "usb-online"
eq "no bracket -> unknown"        "$(active_trigger "none timer")" "unknown"

# a trigger name that is a prefix/suffix of another must not match loosely --
# battery-charging vs battery-charging-blink-full-solid are different triggers
echo
echo "no loose matching between similar trigger names"
if trigger_supported "[none] battery-charging-blink-full-solid" battery-charging; then
  bad "battery-charging must not match the blink-full-solid variant" "matched loosely"
else
  ok "battery-charging does not match the blink-full-solid variant"
fi

echo
echo "REGRESSION: the real trigger list, parsed from a glob-hostile directory"
# The LED trigger file marks the active entry in brackets, and
# "[battery-charging-blink-full-solid]" is a VALID glob character class. The
# first implementation used `for t in $list`, whose unquoted expansion also does
# pathname expansion -- so with cwd "/" the word expanded to the single-char
# entry "d" (in that character set) and the bracket vanished before parsing.
# On hardware the trigger was correctly active while the script reported all 8
# writes as failed. Reproduce the conditions exactly: real string, cwd holding
# single-character files.
REAL='none rc-feedback rfkill-any rfkill-none timer transient mmc1 rfkill0 usb-online wireless-online battery-charging-or-full battery-charging battery-full [battery-charging-blink-full-solid] ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi1-online rfkill1'
GT=$(mktemp -d)
# every single character appearing inside the bracketed trigger name
for ch in b a t e r y c h g i n k f u l s o d -; do : > "$GT/$ch"; done
(
  cd "$GT" || exit 1
  eq "active trigger survives globbing" \
     "$(active_trigger "$REAL")" "battery-charging-blink-full-solid"
  if trigger_supported "$REAL" battery-charging-blink-full-solid; then
    ok "blink variant found despite globbing"
  else bad "blink variant found despite globbing"; fi
  if trigger_supported "$REAL" battery-charging; then
    ok "plain battery-charging still found"
  else bad "plain battery-charging still found"; fi
  if trigger_supported "$REAL" ucsi-source-psy-soc:qcom,pmic_glink:qcom,ucsi1-online; then
    ok "name containing commas and colons is found"
  else bad "name containing commas and colons is found"; fi
  # the bracketed name must not be reported as merely "advertised but inactive"
  eq "bracket stripped from membership test" \
     "$(trigger_supported "$REAL" battery-charging-blink-full-solid && echo in)" "in"
) || bad "glob-hostile cwd block" "could not enter $GT"
# NOTE: the subshell above means its pass/fail counters are lost; re-assert the
# two critical results in this shell so they count.
eq "active trigger (outer shell)" "$(active_trigger "$REAL")" "battery-charging-blink-full-solid"
if trigger_supported "$REAL" battery-charging-blink-full-solid; then
  ok "blink variant found (outer shell)"
else bad "blink variant found (outer shell)"; fi
rm -rf "$GT"

echo
echo "seed_for_status  (power-supply LED triggers are edge-driven, not level-driven)"
eq "Charging seeds on"        "$(seed_for_status Charging)"      "$SEED_BRIGHTNESS"
eq "Full seeds on"            "$(seed_for_status Full)"          "$SEED_BRIGHTNESS"
eq "Discharging seeds off"    "$(seed_for_status Discharging)"   "0"
eq "Not charging seeds off"   "$(seed_for_status 'Not charging')" "0"
eq "Unknown seeds off"        "$(seed_for_status Unknown)"       "0"
eq "empty seeds off"          "$(seed_for_status '')"            "0"
# a status we have never seen must fail safe to OFF, not to on
eq "unseen status fails off"  "$(seed_for_status Weird)"         "0"
st=sentinel; seed_for_status Full >/dev/null 2>&1
eq "seed_for_status keeps caller \$st" "$st" "sentinel"

echo
echo "REGRESSION: helpers must not clobber the caller's variables"
# POSIX sh has no guaranteed `local`. The first version of these helpers used
# bare `for t in ...` / `for v in ...`, so calling one from inside a loop
# overwrote that loop's variable. It made this very test file print four
# identical labels. Guard each helper against its historical variable name.
t=sentinel; trigger_supported "[none] timer" timer >/dev/null 2>&1
eq "trigger_supported keeps caller \$t" "$t" "sentinel"
t=sentinel; active_trigger "none [timer]" >/dev/null 2>&1
eq "active_trigger keeps caller \$t"    "$t" "sentinel"
v=sentinel; is_black "0 0 0" >/dev/null 2>&1
eq "is_black keeps caller \$v"          "$v" "sentinel"
c=sentinel; parse_color 2BE0D8 >/dev/null 2>&1
eq "parse_color keeps caller \$c"       "$c" "sentinel"
ms=sentinel; breathe_delays 1200 >/dev/null 2>&1
eq "breathe_delays keeps caller \$ms"   "$ms" "sentinel"
for n in r g b half want; do
  eval "$n=sentinel"
  parse_color 2BE0D8 >/dev/null 2>&1; breathe_delays 1200 >/dev/null 2>&1
  trigger_supported "[none] timer" timer >/dev/null 2>&1
  eval "got=\$$n"
  eq "helpers keep caller \$$n" "$got" "sentinel"
done

echo
printf '  %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
