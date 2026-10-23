#!/usr/bin/env bash
# Host-side unit tests for fanctl's pure planning logic.
# No device, no root, no network. Run: ./tests/test_plan.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FANCTL_LIB=1 . "$HERE/../fanctl"

pass=0; fail=0
ok()   { pass=$((pass+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s\n     %s\n' "$1" "$2"; }

# Fixture mirrors the real discovered table on the Thor (2026-10-20):
# usb-therm trips 2,3 and the CPU/GPU ladder.
fixture() {
cat <<'EOF'
usb-therm /sys/class/thermal/thermal_zone53/trip_point_2_temp 43000
usb-therm /sys/class/thermal/thermal_zone53/trip_point_3_temp 45000
gpuss-0 /sys/class/thermal/thermal_zone33/trip_point_4_temp 50000
gpuss-0 /sys/class/thermal/thermal_zone33/trip_point_5_temp 55000
cpu-1-9 /sys/class/thermal/thermal_zone14/trip_point_5_temp 55000
cpu-1-9 /sys/class/thermal/thermal_zone14/trip_point_11_temp 85000
EOF
}
plan() { ( unset _usb_seen _usb_first_orig; fixture | plan_for_mode "$1" ); }
val()  { plan "$1" | awk -v p="$2" '$1==p{print $2}'; }

USB2=/sys/class/thermal/thermal_zone53/trip_point_2_temp
USB3=/sys/class/thermal/thermal_zone53/trip_point_3_temp
GPU4=/sys/class/thermal/thermal_zone33/trip_point_4_temp
CPU11=/sys/class/thermal/thermal_zone14/trip_point_11_temp

echo "fanctl plan_for_mode"

# --- default is an exact identity on the recorded originals -------------------
if diff <(fixture | awk '{print $2, $3}') <(plan default) >/dev/null; then
  ok "default restores every trip to its original"
else
  bad "default restores every trip to its original" "$(diff <(fixture | awk '{print $2,$3}') <(plan default) | head -4)"
fi

# --- quiet --------------------------------------------------------------------
v=$(val quiet "$USB2"); [ "$v" = 60000 ] && ok "quiet sets usb-therm first trip to 60000" \
  || bad "quiet sets usb-therm first trip to 60000" "got $v"

v=$(val quiet "$USB3"); [ "$v" = 62000 ] && ok "quiet preserves the 2C gap on the second usb trip" \
  || bad "quiet preserves the 2C gap on the second usb trip" "got $v"

v=$(val quiet "$GPU4"); [ "$v" = 50000 ] && ok "quiet leaves the GPU ladder untouched" \
  || bad "quiet leaves the GPU ladder untouched" "got $v"

v=$(val quiet "$CPU11"); [ "$v" = 85000 ] && ok "quiet leaves the CPU ladder untouched" \
  || bad "quiet leaves the CPU ladder untouched" "got $v"

# --- balanced -----------------------------------------------------------------
v=$(val balanced "$GPU4"); [ "$v" = 45000 ] && ok "balanced lowers GPU trip by 5C" \
  || bad "balanced lowers GPU trip by 5C" "got $v"

v=$(val balanced "$CPU11"); [ "$v" = 80000 ] && ok "balanced lowers CPU trip by 5C" \
  || bad "balanced lowers CPU trip by 5C" "got $v"

v=$(val balanced "$USB2"); [ "$v" = 60000 ] && ok "balanced also fixes usb-therm" \
  || bad "balanced also fixes usb-therm" "got $v"

# the tiers must be strictly ordered: quiet <= balanced <= performance in how
# early the fan engages, i.e. descending trip temperatures
q=$(val quiet "$GPU4"); b=$(val balanced "$GPU4"); p=$(val performance "$GPU4")
[ "$q" -ge "$b" ] && [ "$b" -ge "$p" ] && ok "tiers are ordered quiet >= balanced >= performance ($q/$b/$p)" \
  || bad "tiers are ordered" "quiet=$q balanced=$b performance=$p"

# --- performance --------------------------------------------------------------
v=$(val performance "$GPU4"); [ "$v" = 40000 ] && ok "performance lowers GPU trip by the offset" \
  || bad "performance lowers GPU trip by the offset" "got $v"

v=$(val performance "$CPU11"); [ "$v" = 75000 ] && ok "performance lowers CPU trip by the offset" \
  || bad "performance lowers CPU trip by the offset" "got $v"

v=$(val performance "$USB2"); [ "$v" = 60000 ] && ok "performance also fixes usb-therm" \
  || bad "performance also fixes usb-therm" "got $v"

# --- SAFETY INVARIANTS --------------------------------------------------------
# never raise a CPU/GPU trip above its original, in any mode
bad_raise=""
for m in quiet balanced default performance; do
  while IFS=' ' read -r zt path orig; do
    case "$zt" in usb-therm) continue ;; esac
    nv=$(val "$m" "$path")
    [ -n "$nv" ] && [ "$nv" -gt "$orig" ] && bad_raise="$bad_raise $m:$path($orig->$nv)"
  done < <(fixture)
done
[ -z "$bad_raise" ] && ok "INVARIANT: no mode ever raises a CPU/GPU fan trip" \
  || bad "INVARIANT: no mode ever raises a CPU/GPU fan trip" "$bad_raise"

# usb-therm is clamped to the ceiling
out=$(USB_QUIET_TEMP=999000 bash -c '
  FANCTL_LIB=1 . "'"$HERE"'/../fanctl"
  echo "usb-therm /p 43000" | plan_for_mode quiet' | awk '{print $2}')
[ "$out" = "$USB_CEILING" ] && ok "INVARIANT: usb-therm clamped to ceiling ($USB_CEILING)" \
  || bad "INVARIANT: usb-therm clamped to ceiling" "got $out"

# usb-therm is clamped to the floor
out=$(USB_QUIET_TEMP=1000 bash -c '
  FANCTL_LIB=1 . "'"$HERE"'/../fanctl"
  echo "usb-therm /p 43000" | plan_for_mode quiet' | awk '{print $2}')
[ "$out" = "$USB_FLOOR" ] && ok "INVARIANT: usb-therm clamped to floor ($USB_FLOOR)" \
  || bad "INVARIANT: usb-therm clamped to floor" "got $out"

# performance never pushes a trip below PERF_MIN_TRIP
out=$(PERF_OFFSET=-90000 bash -c '
  FANCTL_LIB=1 . "'"$HERE"'/../fanctl"
  echo "cpu-1-9 /p 50000" | plan_for_mode performance' | awk '{print $2}')
[ "$out" = "$PERF_MIN_TRIP" ] && ok "INVARIANT: performance floors at $PERF_MIN_TRIP" \
  || bad "INVARIANT: performance floors at PERF_MIN_TRIP" "got $out"

# a positive offset must not be able to raise trips (invariant beats tunable)
out=$(PERF_OFFSET=20000 bash -c '
  FANCTL_LIB=1 . "'"$HERE"'/../fanctl"
  echo "cpu-1-9 /p 50000" | plan_for_mode performance' | awk '{print $2}')
[ "$out" = 50000 ] && ok "INVARIANT: a positive PERF_OFFSET cannot raise a trip" \
  || bad "INVARIANT: a positive PERF_OFFSET cannot raise a trip" "got $out"

out=$(BALANCED_OFFSET=-90000 bash -c '
  FANCTL_LIB=1 . "'"$HERE"'/../fanctl"
  echo "cpu-1-9 /p 50000" | plan_for_mode balanced' | awk '{print $2}')
[ "$out" = "$PERF_MIN_TRIP" ] && ok "INVARIANT: balanced floors at $PERF_MIN_TRIP" \
  || bad "INVARIANT: balanced floors at PERF_MIN_TRIP" "got $out"

out=$(BALANCED_OFFSET=20000 bash -c '
  FANCTL_LIB=1 . "'"$HERE"'/../fanctl"
  echo "cpu-1-9 /p 50000" | plan_for_mode balanced' | awk '{print $2}')
[ "$out" = 50000 ] && ok "INVARIANT: a positive BALANCED_OFFSET cannot raise a trip" \
  || bad "INVARIANT: a positive BALANCED_OFFSET cannot raise a trip" "got $out"

# "stock" must NOT be reachable as a tier -- it is the module being disabled.
# default still exists for the CLI/uninstall restore path.
if diff <(fixture | awk '{print $2, $3}') <(plan default) >/dev/null; then
  ok "default remains an exact restore (CLI revert path)"
else
  bad "default remains an exact restore" "differs"
fi

# --- idempotence / no drift ----------------------------------------------------
if diff <(plan quiet) <(plan quiet) >/dev/null; then
  ok "planning is deterministic"
else
  bad "planning is deterministic" "two runs differed"
fi

# every input line produces exactly one output line
i=$(fixture | wc -l); o=$(plan performance | wc -l)
[ "$i" = "$o" ] && ok "every discovered trip yields exactly one planned write ($i)" \
  || bad "every discovered trip yields exactly one planned write" "in=$i out=$o"

echo
printf '  %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
