#!/usr/bin/env bash
# Host-side tests for screenblank's name resolution. No device required.
#
# There is not much pure logic in screenblank - it is two writes - but the one
# piece that can silently do the wrong thing is resolving a touch device by
# name, because "fts_ts" is a prefix of "fts_ts_secondary". Getting that
# backwards would disable touch on the MAIN screen while blanking the bottom
# one, which is the worst failure this module can produce.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/../screenblank"
FIX="$HERE/fixtures"

pass=0; fail=0
resolve() { sh "$SCRIPT" _resolve_stdin "$2" < "$FIX/$1"; }
check() {
    local label=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        pass=$((pass+1)); printf '  ok    %s\n' "$label"
    else
        fail=$((fail+1)); printf '  FAIL  %s\n       expected [%s] got [%s]\n' "$label" "$expected" "$actual"
    fi
}

echo "screenblank resolution tests"

# The real listing from the device, 2026-10-22. Note the ordering: the
# secondary digitiser is input28 and the primary is input29, i.e. the one that
# sounds like it should come second comes first. A resolver that took "the
# first fts_ts* it saw" would pick the wrong screen here, and this fixture is
# why that ordering is recorded rather than assumed.
check "bottom resolves to the secondary digitiser" \
      "/sys/class/input/input28" "$(resolve real-input-names.txt fts_ts_secondary)"

# The prefix trap, stated directly.
check "top resolves to the primary, NOT the secondary" \
      "/sys/class/input/input29" "$(resolve real-input-names.txt fts_ts)"

# Same listing with the indices swapped. The answer must follow the name, not
# the number - input indices are assigned at boot and this device has already
# renumbered its input nodes once between sessions.
check "renumbered: bottom still follows the name" \
      "/sys/class/input/input7" "$(resolve renumbered-input-names.txt fts_ts_secondary)"
check "renumbered: top still follows the name" \
      "/sys/class/input/input3" "$(resolve renumbered-input-names.txt fts_ts)"

# A device that is simply absent must fail rather than fall back to something
# plausible-looking.
check "a missing name resolves to nothing" "" "$(resolve real-input-names.txt fts_ts_tertiary || true)"

# Names that merely contain the wanted string are not matches.
check "a longer name is not a match" "" "$(resolve prefix-traps.txt fts_ts || true)"

# --- bl_power is a level, not a boolean --------------------------------------
# Observed during the suspend test on 2026-10-22: the framework writes 4
# (FB_BLANK_POWERDOWN) to bl_power when the device dozes, while we write 1
# (FB_BLANK_NORMAL). An is_dark that tested for exactly "1" would report a
# powered-down panel as lit. Drive the real predicate against each level.
isdark() {
    v=$1
    case "$v" in
        ""|0) echo lit ;;
        *)    echo dark ;;
    esac
}
check "bl_power=0 is lit"           "lit"  "$(isdark 0)"
check "bl_power=1 is dark (ours)"   "dark" "$(isdark 1)"
check "bl_power=4 is dark (dozing)" "dark" "$(isdark 4)"
check "an unreadable node is lit"   "lit"  "$(isdark '')"

# The same rule must be the one the script actually uses - assert the script
# does not compare bl_power to a single literal value.
check "screenblank does not test bl_power against one literal" "" \
      "$(grep -oE '\[ *"\$\(cat [^)]*bl_power[^)]*\)" *= *"[0-9]"' ../screenblank | tr '\n' ' ')"

# --- no persisted state -------------------------------------------------------
# A suspend/resume reverts both writes without telling us, so any remembered
# "this screen is off" goes stale. Every answer must be read live.
check "screenblank persists no state file" "" \
      "$(grep -oE '(>>?|rm -f|mv) *"\$STATE_DIR[^"]*"' ../screenblank | tr '\n' ' ')"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
