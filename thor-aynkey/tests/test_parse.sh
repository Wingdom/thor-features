#!/usr/bin/env bash
# Host-side tests for aynkeyd's press classifier. No device required.
#
# The classifier is the only non-trivial logic in this module - everything else
# is device discovery and process control. It is also logic we had to *invent*:
# stock's receiver handled key-DOWN only with no timer, so the short/long
# distinction was in AYN's framework and is not recoverable from the APKs.
# That makes it exactly the kind of thing this project requires be host-tested.
#
# Two fixtures are real captures from the device (2026-10-22); the rest are
# synthetic sequences for cases a real capture does not conveniently contain.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/../aynkeyd"
FIX="$HERE/fixtures"

pass=0; fail=0

# The script's shebang is /system/bin/sh, which does not exist on the host, so
# invoke it through sh explicitly - same approach as screenmove's tests.
classify() { sh "$SCRIPT" _simulate_stdin < "$FIX/$1"; }

check() {
    local label=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        pass=$((pass+1)); printf '  ok    %s\n' "$label"
    else
        fail=$((fail+1)); printf '  FAIL  %s\n       expected [%s] got [%s]\n' "$label" "$expected" "$actual"
    fi
}

echo "aynkeyd classifier tests"

# --- the real captures --------------------------------------------------------
# Taken on this unit: a 276 ms tap, then a 1601 ms hold. Both are SHORT under
# the 2000 ms threshold - and that is the point of keeping this fixture. The
# first build used 500 ms and called the second press long; the user then
# asked for 2-3 s, so the same recorded input now has to classify differently.
check "real capture: both presses are short at a 2 s threshold" \
      "short@275
short@1601" "$(classify real-short-then-long.txt)"

# Last session's capture, recorded across all devices so every line carries a
# "/dev/input/eventN:" prefix. The classifier must not care: a parser that
# matched on field position rather than content would see nothing here.
check "real capture: prefixed lines, two taps (302 ms, 279 ms)" \
      "short@302
short@279" "$(classify real-prefixed-two-taps.txt)"

# --- the one that would be a real bug ----------------------------------------
# KEY_VOLUMEUP is on the *same* gpio-keys node as KEY_F24. A classifier that
# keyed off DOWN/UP without checking which key would fire on volume presses -
# and a 3.5 s volume-up hold would read as a long press and toggle the screen.
check "volume up is ignored, F24 between them still works" \
      "short@200" "$(classify volume-up-interleaved.txt)"

# --- malformed input ----------------------------------------------------------
# An UP with no DOWN happens every time the daemon attaches while the button is
# held. A DOWN followed by a DOWN means an UP was lost. Neither is a press;
# the second DOWN restarts the timer, so only the final clean 100 ms tap emits.
check "unpaired UP and doubled DOWN emit only the clean tap" \
      "short@100" "$(classify unpaired-events.txt)"

# A duration cannot be negative. If it is, the timestamp source changed under
# us - a re-added device, or two captures interleaved - which is not a press.
# The following clean 100 ms tap must still be found, i.e. the bad pair must
# not desynchronise the state machine.
check "backwards clock is dropped, next tap still parsed" \
      "short@100" "$(classify clock-went-backwards.txt)"

# getevent prints device enumeration and driver warnings into the same stream,
# including mid-stream when a controller connects.
check "getevent noise is ignored" \
      "short@150" "$(classify getevent-noise.txt)"

# --- the threshold ------------------------------------------------------------
# 1999 / 2000 / 2001 ms. The boundary is inclusive: >= 2000 ms is long.
# Asserted explicitly because an off-by-one here is invisible in use - it just
# makes the button feel unreliable near the boundary.
#
# Note what the long rows report: long@2000, not long@2001. The long action
# fires AT the threshold, mid-hold, so its timing does not depend on when the
# key was released. That is the whole behaviour the user asked for, and this
# is where it is pinned down.
check "boundary: 1999 short, 2000 long, 2001 long - and long fires at 2000" \
      "short@1999
long@2000
long@2000" "$(classify threshold-boundary.txt)"

# --- empty and whitespace input ----------------------------------------------
check "empty input emits nothing" "" "$(printf '' | sh "$SCRIPT" _simulate_stdin)"
check "blank lines emit nothing"  "" "$(printf '\n\n\n' | sh "$SCRIPT" _simulate_stdin)"

# --- the threshold constant is the documented one ----------------------------
# Behaviour, not text: derive the threshold from what the classifier *does*
# rather than grepping the assignment. Comments mentioning 500 ms would pass a
# grep even if the constant were wrong - a trap this project hit once in fanctl.
derive_threshold() {
    # Binary search the boundary in ms over a synthetic press.
    local lo=1 hi=9000 mid
    while [ $((hi - lo)) -gt 1 ]; do
        mid=$(( (lo + hi) / 2 ))
        local sec
        sec=$(awk -v m="$mid" 'BEGIN{printf "%.6f", 1000 + m/1000}')
        local r
        r=$(printf '[    1000.000000] EV_KEY       KEY_F24              DOWN\n[ %s] EV_KEY       KEY_F24              UP\n' "$sec" \
            | sh "$SCRIPT" _simulate_stdin)
        case "$r" in long@*) hi=$mid ;; *) lo=$mid ;; esac
    done
    echo "$hi"
}
check "measured threshold is 2000 ms" "2000" "$(derive_threshold)"

# --- only KEY_F24 is ever acted on -------------------------------------------
# Generalises the volume-up case: no other key on any device may emit. Drives
# a handful of plausible neighbours through the classifier as long holds.
other_keys=""
for k in KEY_VOLUMEUP KEY_VOLUMEDOWN KEY_POWER KEY_F23 KEY_F2 BTN_SOUTH; do
    r=$(printf '[    1.000000] EV_KEY       %s              DOWN\n[    5.000000] EV_KEY       %s              UP\n' "$k" "$k" \
        | sh "$SCRIPT" _simulate_stdin)
    [ -n "$r" ] && other_keys="$other_keys $k:$r"
done
check "no other key emits an action" "" "$other_keys"

# KEY_F24 must not be matched as a substring of a longer name. There is no
# KEY_F240, but the guard is cheap and the failure mode would be silent.
check "a longer key name is not mistaken for F24" "" \
      "$(printf '[    1.000000] EV_KEY       KEY_F24X              DOWN\n[    1.100000] EV_KEY       KEY_F24X              UP\n' \
         | sh "$SCRIPT" _simulate_stdin)"

# --- the behaviour the user asked for ----------------------------------------
# "If I hold for 10 seconds, then lift, it turns off when I lift off the
# button. Can you have it turn off/on after 2 or 3 seconds exactly, even if I
# keep holding the button?"
#
# A 10 second hold must therefore report a long press at 2000 ms, not at
# 10000 ms, and must report exactly one action - releasing after a long press
# does nothing.
check "a 10 s hold fires once, at the threshold, not on release" \
      "long@2000" "$(classify long-hold-10s.txt)"

# And the key must still be held when it fires: a press that never ends in the
# recorded stream still produces the long action, because the live timer does
# not wait for an UP.
check "a press with no release still fires the long action" \
      "long@2000" "$(printf '[   10.000000] EV_KEY       KEY_F24              DOWN\n' \
                     | sh "$SCRIPT" _simulate_stdin)"

# The race the mkdir arbiter exists for: a release landing exactly on the
# threshold must produce one action, never both.
check "release exactly at the threshold yields exactly one action" \
      "long@2000" "$(printf '[   10.000000] EV_KEY       KEY_F24              DOWN\n[   12.000000] EV_KEY       KEY_F24              UP\n' \
                     | sh "$SCRIPT" _simulate_stdin)"

# No input sequence may ever produce two actions for one press. Swept across
# the boundary at 1 ms resolution, which is where a both-fire bug would live.
dup=""
for ms in 1995 1996 1997 1998 1999 2000 2001 2002 2003 2004 2005; do
    sec=$(awk -v m="$ms" 'BEGIN{printf "%.6f", 10 + m/1000}')
    n=$(printf '[   10.000000] EV_KEY       KEY_F24              DOWN\n[ %s] EV_KEY       KEY_F24              UP\n' "$sec" \
        | sh "$SCRIPT" _simulate_stdin | grep -c .)
    [ "$n" = "1" ] || dup="$dup ${ms}ms:${n}actions"
done
check "exactly one action per press across the boundary" "" "$dup"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
