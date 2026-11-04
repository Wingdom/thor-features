#!/usr/bin/env bash
# Host tests for aynkeyd's LIVE press path - the timer, the mkdir arbiter, the
# three-stage pipeline and the dispatch - with no device.
#
# The daemon used to say this part could not be host-tested, and that was true
# while it insisted on a real evdev node. It does not: everything it needs from
# the device arrives through `getevent`, so a fake getevent on PATH plus
# AYNKEYD_STATE_DIR is enough to run the real loop end to end.
#
# What that buys, which test_parse.sh cannot:
#   - the timer and the arbiter actually race, rather than being simulated
#   - the historical regression is testable: an earlier build killed the losing
#     timer with `kill "$timer_pid"`, which took the daemon's own shell down
#     with it and made the button work exactly once per boot. A test that
#     presses three times catches that; a simulation never could.
#   - per-stage exit logging is observed rather than grepped for
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/../aynkeyd"

pass=0; fail=0
check() {
    local label=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        pass=$((pass+1)); printf '  ok    %s\n' "$label"
    else
        fail=$((fail+1)); printf '  FAIL  %s\n       expected [%s] got [%s]\n' "$label" "$expected" "$actual"
    fi
}

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
BIN="$TMP/bin"; mkdir -p "$BIN"

# --- the fake device ---------------------------------------------------------
# `-pl` answers find_device's probe; `-lt` replays a press script in real time.
# The live reader ignores the timestamps (it uses the wall clock and the
# arbiter), but they must still parse, or emit_events drops the line.
cat > "$BIN/getevent" <<'FAKE'
#!/usr/bin/env bash
if [ "${1:-}" = "-pl" ]; then
    printf 'add device 1: /dev/input/event9\n'
    printf '  name:     "gpio-keys"\n'
    printf '    KEY_F24               194\n'
    printf '    KEY_VOLUMEUP          115\n'
    exit 0
fi
t=100
while read -r delay kind; do
    [ -n "${delay:-}" ] || continue
    sleep "$delay"
    t=$(awk -v t="$t" -v d="$delay" 'BEGIN{printf "%.6f", t + d}')
    # HOLD keeps the stream open without emitting - the daemon then stays in
    # its live pipeline, which is the only state in which the stages and the
    # timer can be observed receiving a signal.
    [ "$kind" = HOLD ] && continue
    printf '[ %12s] EV_KEY       KEY_F24              %s\n' "$t" "$kind"
done < "$AYNKEY_TEST_PRESSES"
exit "${AYNKEY_TEST_GETEVENT_RC:-0}"
FAKE
# A no-op `log`, so the host's own logger (if it has one) is never touched and
# the only sink under test is the file.
printf '#!/bin/sh\nexit 0\n' > "$BIN/log"
chmod +x "$BIN/getevent" "$BIN/log"

# --- the harness -------------------------------------------------------------
# Run the real daemon against a press script and return its state directory.
# Waits on observable progress rather than on guessed sleeps.
run_presses() {
    local script=$1 rc=${2:-0} dir
    dir=$(mktemp -d "$TMP/d.XXXXXX")
    printf '%s' "$script" > "$dir/presses"
    # A fake action script that records every dispatch, so the test asserts on
    # what the daemon actually ran.
    cat > "$dir/action.sh" <<'ACT'
#!/bin/sh
printf '%s\n' "$1" >> "$(dirname "$0")/dispatched"
exit 0
ACT
    chmod +x "$dir/action.sh"
    : > "$dir/dispatched"

    PATH="$BIN:$PATH" \
      AYNKEY_TEST_PRESSES="$dir/presses" AYNKEY_TEST_GETEVENT_RC="$rc" \
      AYNKEYD_STATE_DIR="$dir" AYNKEYD_LOG="$dir/aynkeyd.log" \
      sh "$SCRIPT" start --foreground >/dev/null 2>&1 &
    local p=$!
    # The stream ends when the script is exhausted; the daemon then logs
    # "input stream ... ended" and sleeps 2s before reconnecting. Wait for that
    # line rather than for a fixed interval.
    local i=0
    while [ $i -lt 400 ]; do
        grep -q 'input stream' "$dir/aynkeyd.log" 2>/dev/null && break
        sleep 0.05; i=$((i+1))
    done
    kill -TERM "-$p" 2>/dev/null || kill -TERM "$p" 2>/dev/null
    wait "$p" 2>/dev/null
    printf '%s' "$dir"
}

echo "aynkeyd live-path tests"

# --- a short press -----------------------------------------------------------
d=$(run_presses '0.1 DOWN
0.2 UP
')
check "a 200 ms press dispatches exactly one short action" "short" \
      "$(tr '\n' ' ' < "$d/dispatched" | sed 's/ $//')"
check "the press is in the log" "yes" \
      "$(grep -q 'short press ->' "$d/aynkeyd.log" && echo yes || echo no)"
check "the action's exit status is logged" "yes" \
      "$(grep -q 'short action exited rc=0' "$d/aynkeyd.log" && echo yes || echo no)"

# --- a long press fires mid-hold --------------------------------------------
# Held for 2.6 s against a 2000 ms threshold. The long action must fire, and
# the release afterwards must add nothing.
d=$(run_presses '0.1 DOWN
2.6 UP
')
check "a 2.6 s hold dispatches exactly one long action" "long" \
      "$(tr '\n' ' ' < "$d/dispatched" | sed 's/ $//')"
check "the timer stage names itself in the log" "yes" \
      "$(grep -qE 'timer\.[0-9]+: long press ->' "$d/aynkeyd.log" && echo yes || echo no)"

# --- several presses in a row -------------------------------------------------
# An earlier build cancelled the losing timer with `kill "$timer_pid"`. The
# shell took a SIGTERM immediately after and the whole pipeline went down, so
# the second press of any boot did nothing.
#
# Checked 2026-11-03: reintroducing that kill does NOT fail this test, because
# it does not reproduce under the host's bash - it was specific to Android's
# mksh. So this is not the guard for that bug; the mechanical "no kill in the
# press path" check at the bottom of this file is. What this does cover is that
# a mixed sequence of presses all land, which is still the symptom any future
# shell-level breakage of the press path would produce.
d=$(run_presses '0.1 DOWN
0.2 UP
0.3 DOWN
0.2 UP
0.3 DOWN
2.3 UP
0.3 DOWN
0.2 UP
')
check "four presses in a row all dispatch" "short short long short" \
      "$(tr '\n' ' ' < "$d/dispatched" | sed 's/ $//')"
check "the daemon is still alive at the end of them" "yes" \
      "$(grep -q 'input stream' "$d/aynkeyd.log" && echo yes || echo no)"

# --- volume up on the same node must do nothing -----------------------------
# KEY_VOLUMEUP shares gpio-keys. test_parse.sh covers the classifier; this
# covers the live path, where a dispatch would mean a 3 s volume-up hold
# blanking the screen.
dv=$(mktemp -d "$TMP/v.XXXXXX")
cat > "$BIN/getevent-vol" <<'FAKE2'
#!/usr/bin/env bash
if [ "${1:-}" = "-pl" ]; then
    printf 'add device 1: /dev/input/event9\n  name:     "gpio-keys"\n    KEY_F24               194\n'
    exit 0
fi
printf '[   100.000000] EV_KEY       KEY_VOLUMEUP         DOWN\n'
sleep 2.5
printf '[   102.500000] EV_KEY       KEY_VOLUMEUP         UP\n'
exit 0
FAKE2
chmod +x "$BIN/getevent-vol"
cp "$BIN/getevent" "$BIN/getevent.real"; cp "$BIN/getevent-vol" "$BIN/getevent"
cat > "$dv/action.sh" <<'ACT2'
#!/bin/sh
printf '%s\n' "$1" >> "$(dirname "$0")/dispatched"
ACT2
chmod +x "$dv/action.sh"; : > "$dv/dispatched"
PATH="$BIN:$PATH" AYNKEYD_STATE_DIR="$dv" AYNKEYD_LOG="$dv/aynkeyd.log" \
    sh "$SCRIPT" start --foreground >/dev/null 2>&1 &
vp=$!
i=0; while [ $i -lt 200 ]; do grep -q 'input stream' "$dv/aynkeyd.log" 2>/dev/null && break; sleep 0.05; i=$((i+1)); done
kill -TERM "-$vp" 2>/dev/null || kill -TERM "$vp" 2>/dev/null; wait "$vp" 2>/dev/null
cp "$BIN/getevent.real" "$BIN/getevent"
check "a 2.5 s volume-up hold dispatches nothing" "" "$(tr '\n' ' ' < "$dv/dispatched")"

# --- which stage died -------------------------------------------------------
# The whole reason for this work: the daemon is three independently-mortal
# processes and the 2026-10-29 death could not be attributed to any of them.
# Each must name itself and report its own status.
d=$(run_presses '0.1 DOWN
0.2 UP
' 3)
check "getevent reports its own non-zero exit" "yes" \
      "$(grep -qE 'getevent\.[0-9]+: getevent exited rc=3' "$d/aynkeyd.log" && echo yes || echo no)"
check "the parser reports its exit" "yes" \
      "$(grep -qE 'parser\.[0-9]+: parser exited rc=' "$d/aynkeyd.log" && echo yes || echo no)"
check "the reader reports its exit" "yes" \
      "$(grep -qE 'reader\.[0-9]+: reader exited rc=' "$d/aynkeyd.log" && echo yes || echo no)"
check "all three stages are distinguishable" "3" \
      "$(grep -oE '(getevent|parser|reader)\.[0-9]+:' "$d/aynkeyd.log" | sed 's/\..*//' | sort -u | wc -l | tr -d ' ')"

# --- exactly one exit line for the whole process group ----------------------
# cmd_stop signals the whole process group, which at this moment is seven
# processes. Exactly one of them must report the exit, or the line that says
# what killed the daemon is buried in four that say nothing.
#
# This is a guarantee the shell provides, not one the daemon implements:
# measured 2026-11-03, a shell resets trapped signals to their default in a
# subshell, so only the main shell runs the handler. enter_stage used to
# `trap -` as well, on the opposite assumption, and that was removed as dead
# code. The property is pinned HERE because it is what the daemon depends on -
# if a future shell or a restructured pipeline broke it, this is what should
# fail.
#
# Noted because the first version of this test was worthless. It signalled
# after waiting for "input stream ... ended", by which point the stages had
# already exited and there was nothing left to mis-report - removing the trap
# reset did not fail it. The second version tried to signal mid-stream but
# backgrounded the daemon directly, and a background job in a non-interactive
# shell is NOT its own process-group leader, so `kill -TERM -$!` had no group
# to hit and the test hung for 30 s on a HOLD.
#
# So this one puts the daemon in its own session with `setsid`, exactly as
# cmd_start does, and then stops it through the real `aynkeyd stop`.
#
# It cannot go through `aynkeyd start` itself: that re-execs the script as
# `setsid "$0" start --foreground`, which makes the kernel honour the
# `#!/system/bin/sh` shebang - and /system/bin/sh does not exist on the host.
# Every other test here invokes the script as `sh "$SCRIPT"`, which bypasses
# the shebang, for the same reason. `aynkeyd stop` re-execs nothing, so the
# stop path under test is the production one.
d=$(mktemp -d "$TMP/t.XXXXXX")
printf '0.1 DOWN\n30 HOLD\n' > "$d/presses"
cat > "$d/action.sh" <<'ACT3'
#!/bin/sh
printf '%s\n' "$1" >> "$(dirname "$0")/dispatched"
ACT3
chmod +x "$d/action.sh"; : > "$d/dispatched"

ayn() {
    PATH="$BIN:$PATH" AYNKEY_TEST_PRESSES="$d/presses" \
      AYNKEYD_STATE_DIR="$d" AYNKEYD_LOG="$d/aynkeyd.log" \
      sh "$SCRIPT" "$@"
}
setsid env PATH="$BIN:$PATH" AYNKEY_TEST_PRESSES="$d/presses" \
    AYNKEYD_STATE_DIR="$d" AYNKEYD_LOG="$d/aynkeyd.log" \
    sh "$SCRIPT" start --foreground >/dev/null 2>&1 &
i=0; while [ $i -lt 200 ]; do
    grep -q 'reading /dev/input/event9' "$d/aynkeyd.log" 2>/dev/null && break
    sleep 0.05; i=$((i+1))
done
# The daemon writes its own pid, and setsid made it the group leader - so this
# is the pid `aynkeyd stop` will signal the group through.
dpid=$(cat "$d/aynkeyd.pid" 2>/dev/null || echo 0)
sleep 0.6                      # inside the 2 s hold: the timer is still pending
live_stages=$(pgrep -g "$dpid" 2>/dev/null | wc -l | tr -d ' ')
ayn stop >/dev/null 2>&1
# Bounded wait for the group to actually go, so a broken stop fails the test
# instead of hanging it.
i=0; while [ $i -lt 100 ]; do
    kill -0 "$dpid" 2>/dev/null || break
    sleep 0.05; i=$((i+1))
done

check "the daemon was reachable by pid" "yes" \
      "$([ "${dpid:-0}" -gt 0 ] && echo yes || echo no)"
check "the pipeline really was live when signalled" "yes" \
      "$([ "${live_stages:-0}" -ge 3 ] && echo yes || echo no)"
check "the daemon is gone after stop" "yes" \
      "$(kill -0 "$dpid" 2>/dev/null && echo no || echo yes)"
check "a TERM to the live group logs exactly one 'exiting on' line" "1" \
      "$(grep -c 'exiting on SIG' "$d/aynkeyd.log")"
check "and it is the main shell that reports it" "yes" \
      "$(grep -q 'main: exiting on SIGTERM' "$d/aynkeyd.log" && echo yes || echo no)"
# A deliberate stop must be distinguishable from a death. This is the line that
# the 2026-10-29 investigation had no way to look for.
check "the stop is recorded as requested, not as a death" "yes" \
      "$(grep -q 'stop requested' "$d/aynkeyd.log" && echo yes || echo no)"

# --- no kill in the press path ----------------------------------------------
# Behavioural cover is above; this is the mechanical backstop, because the bug
# was a `kill` whose target was right and whose blast radius was not. Strip
# comments first - the daemon discusses the kill at length.
check "the press path contains no kill" "" \
      "$(sed 's/#.*//' "$SCRIPT" \
         | awk '/^start_press\(\)|^end_press\(\)|^cancel_press\(\)|^dispatch\(\)/,/^}/' \
         | grep -oE '\bkill\b' | tr '\n' ' ')"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
