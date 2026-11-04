#!/usr/bin/env bash
# Host tests for aynkeyd's persistent log: the line format, the size bound, and
# the signal traps. No device.
#
# Why this file exists at all:
#   The daemon's one unexplained death (2026-10-29) was investigated with
#   nothing to go on. It has always logged to logcat - that part of the earlier
#   brief was wrong - but the main buffer on this device holds under six
#   minutes (measured 2026-11-03: `logcat -S -b main` -> Logspan 5:52), so the
#   daemon's own lines were long gone. The file is the fix, and the thing most
#   worth testing about a diagnostic is that it actually records the event.
#
# These drive the REAL logit, the REAL rotation and the REAL traps against a
# temp directory, via AYNKEYD_LOG / AYNKEYD_LOG_MAX / AYNKEYD_STATE_DIR. That
# is deliberate: this project has twice had tests pass on broken code by
# grepping for a literal instead of running it.
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
LOG="$TMP/aynkeyd.log"

# The shebang is /system/bin/sh, which does not exist on the host, so invoke
# through sh - same approach as test_parse.sh.
emit() { AYNKEYD_LOG="$LOG" AYNKEYD_LOG_MAX="${2:-131072}" sh "$SCRIPT" _log "$1" "${3:-a message}"; }

echo "aynkeyd log tests"

# --- the line format ---------------------------------------------------------
# Fixed and column-stable, because it is what a human reads at the point the
# button has stopped working: date, time, daemon pid, stage, message.
: > "$LOG"
emit main 131072 "started (threshold 2000ms)" >/dev/null
check "one call writes exactly one line" "1" "$(wc -l < "$LOG" | tr -d ' ')"
check "the line matches the documented format" "yes" \
      "$(grep -qE '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} [0-9]+ main: started \(threshold 2000ms\)$' "$LOG" \
         && echo yes || echo no)"

# The stage field is the whole point of the exercise: the daemon is a pipeline
# of three independently-mortal processes and nothing used to record which one
# spoke. Assert an arbitrary stage label survives into the line.
: > "$LOG"
emit "getevent.7" 131072 "exited rc=143" >/dev/null
check "the stage label is recorded verbatim" "getevent.7: exited rc=143" \
      "$(sed -E 's/^[^ ]+ [^ ]+ [0-9]+ //;q' "$LOG" | sed -n '1p')$(printf '')"

# --- logit must never write to stdout ----------------------------------------
# Three of its four callers are inside the getevent | parser | reader pipeline,
# where one stray byte on stdout is read back as fake event data. This is the
# guard for that, and it is why logit redirects `log` as well as its stderr.
: > "$LOG"
out=$(emit main 131072 "quiet please" 2>/dev/null)
check "logit writes nothing to stdout" "" "$out"

# --- the size bound ----------------------------------------------------------
# The unbounded case is not the press rate - it is a permanently missing input
# device, which logs a reconnect every 2s forever. With a 1 KiB cap, 200 lines
# must not produce a 200-line file.
: > "$LOG"; rm -f "$LOG.1"
for i in $(seq 1 200); do emit main 1024 "line $i padding padding padding" >/dev/null; done
live=$(wc -c < "$LOG" | tr -d ' ')
old=$(wc -c < "$LOG.1" 2>/dev/null | tr -d ' ' || echo 0)
check "the live file stays under the cap after rotation" "yes" \
      "$([ "$live" -le 1024 ] && echo yes || echo no)"
check "exactly one old generation is kept" "1" \
      "$(ls -1 "$TMP" | grep -c '^aynkeyd\.log\.')"
check "the pair is bounded at 2x the cap" "yes" \
      "$([ $((live + old)) -le 2048 ] && echo yes || echo no)"

# And it must NOT rotate below the cap, or the log would never retain anything.
: > "$LOG"; rm -f "$LOG.1"
for i in 1 2 3; do emit main 131072 "short line $i" >/dev/null; done
check "no rotation below the cap" "no" \
      "$([ -f "$LOG.1" ] && echo yes || echo no)"
check "all lines retained below the cap" "3" "$(wc -l < "$LOG" | tr -d ' ')"

# --- `aynkeyd log` reads both generations, oldest first ----------------------
# A death that straddles a rotation must still read as one ordered stream.
printf 'OLD-FIRST\n' > "$LOG.1"
printf 'NEW-SECOND\n' > "$LOG"
check "log prints the rotated generation first" "OLD-FIRST
NEW-SECOND" "$(AYNKEYD_LOG="$LOG" sh "$SCRIPT" log 10)"
check "log honours a line count" "NEW-SECOND" \
      "$(AYNKEYD_LOG="$LOG" sh "$SCRIPT" log 1)"
check "log rejects a non-numeric line count" "2" \
      "$(AYNKEYD_LOG="$LOG" sh "$SCRIPT" log abc >/dev/null 2>&1; echo $?)"
# A missing log must not be an error - it is the normal state before first run.
check "log on a missing file is quiet and succeeds" "0" \
      "$(AYNKEYD_LOG="$TMP/nope.log" sh "$SCRIPT" log 10 >/dev/null 2>&1; echo $?)"

# --- the signal traps --------------------------------------------------------
# The point of the whole change. Run the real daemon in the foreground, send it
# a real signal, and require that the log says which signal.
#
# On the host there is no getevent, so find_device returns empty and run()
# parks in its "no device; retrying in 10s" branch - which is a perfectly good
# place to be signalled.
run_and_signal() {
    local sig=$1 dir
    dir=$(mktemp -d "$TMP/d.XXXXXX")
    AYNKEYD_STATE_DIR="$dir" AYNKEYD_LOG="$dir/aynkeyd.log" \
        sh "$SCRIPT" start --foreground >/dev/null 2>&1 &
    local p=$!
    # Wait for the daemon to have logged its start line, rather than sleeping a
    # guessed interval.
    local i=0
    while [ $i -lt 100 ]; do
        grep -q 'started' "$dir/aynkeyd.log" 2>/dev/null && break
        sleep 0.05; i=$((i+1))
    done
    kill -"$sig" "$p" 2>/dev/null
    wait "$p" 2>/dev/null
    cat "$dir/aynkeyd.log" 2>/dev/null
}

term_log=$(run_and_signal TERM)
check "the daemon logs its own start" "yes" \
      "$(printf '%s' "$term_log" | grep -q 'main: started (threshold 2000ms' && echo yes || echo no)"
check "SIGTERM is recorded by name" "yes" \
      "$(printf '%s' "$term_log" | grep -q 'main: exiting on SIGTERM' && echo yes || echo no)"
# The EXIT trap must also fire, and after the signal line - that ordering is
# what distinguishes "signalled" from "the shell exited on its own".
check "the exit line follows the signal line" "yes" \
      "$(printf '%s' "$term_log" | awk '/exiting on SIGTERM/{s=NR} /shell exiting/{e=NR} END{print (s && e && s < e) ? "yes" : "no"}')"

hup_log=$(run_and_signal HUP)
check "SIGHUP is recorded by name" "yes" \
      "$(printf '%s' "$hup_log" | grep -q 'main: exiting on SIGHUP' && echo yes || echo no)"
check "SIGHUP is not mislabelled as TERM" "" \
      "$(printf '%s' "$hup_log" | grep -o 'SIGTERM' | tr '\n' ' ')"

# SIGKILL is the one that cannot be trapped, and its absence is the evidence:
# a daemon that is gone with no "exiting on SIG..." line was killed or OOMed.
# Assert that absence, so the inference documented in the daemon stays true.
kill_log=$(run_and_signal KILL)
check "SIGKILL leaves no exit line at all" "" \
      "$(printf '%s' "$kill_log" | grep -o 'exiting on SIG[A-Z]*' | tr '\n' ' ')"
check "but the start line is still there to prove it ran" "yes" \
      "$(printf '%s' "$kill_log" | grep -q 'main: started' && echo yes || echo no)"

# --- exactly one line per stop ----------------------------------------------
# cmd_stop signals the whole process group, and every subshell inherits the
# traps. Without enter_stage clearing them in the timer and the pipeline
# stages, a stop would report several exits and bury the real one.
check "a signal produces exactly one 'exiting on' line" "1" \
      "$(printf '%s' "$term_log" | grep -c 'exiting on SIG')"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
