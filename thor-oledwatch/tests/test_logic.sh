#!/usr/bin/env bash
# Tests for the daemon's pure logic. No device, no root.
# Run: ./tests/test_logic.sh
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

# Source the daemon for its pure functions. The LIB guard stops it before the
# runtime half, so nothing is started and nothing is written.
OLEDWATCH_LIB=1 . ./oledwatch

F=tests/fixtures
echo "oledwatch logic tests"

# --- reading the device's own words ------------------------------------------
echo
echo " power_facts"
check "awake and idle, captured output" "Awake 123637 0 -1" \
      "$(power_facts $F/power-awake-idle.txt)"
check "asleep" "Asleep 123637 0 -1" \
      "$(power_facts $F/power-asleep.txt)"
check "just used" "Awake 4210 0 -1" \
      "$(power_facts $F/power-just-used.txt)"
check "long idle" "Awake 1800500 0 -1" \
      "$(power_facts $F/power-long-idle.txt)"

# A lock that holds the display must be seen...
check "screen wake lock is seen" "Awake 123637 1 -1" \
      "$(power_facts $F/power-screen-wakelock.txt)"
# ...and one that does not must not be. Audio playing with the screen idle is
# exactly when a wash is wanted, not when it should be suppressed.
check "partial wake lock is ignored" "Awake 123637 0 -1" \
      "$(power_facts $F/power-partial-wakelock.txt)"

check "timeout override is read" "Awake 123637 0 600000" \
      "$(power_facts $F/power-timeout-override.txt)"

# Degenerate input must not read as "infinitely idle and ready to go".
: > /tmp/oledwatch-empty.$$
check "empty input yields nothing usable" "unknown -1 0 -1" \
      "$(power_facts /tmp/oledwatch-empty.$$)"
rm -f /tmp/oledwatch-empty.$$

echo
echo " keyguard_showing"
check "unlocked" "no"      "$(keyguard_showing $F/keyguard-unlocked.txt)"
check "locked"   "yes"     "$(keyguard_showing $F/keyguard-locked.txt)"
: > /tmp/oledwatch-nokg.$$
# Absent must NOT read as unlocked. A parse that degrades to "go ahead" is how
# a wash ends up painted over a lock screen.
check "absent reads as unknown, never as unlocked" "unknown" \
      "$(keyguard_showing /tmp/oledwatch-nokg.$$)"
rm -f /tmp/oledwatch-nokg.$$

# --- the decision ------------------------------------------------------------
# decide <enabled> <app> <wake> <idle> <lock> <override> <keyguard> <threshold> <since>
echo
echo " decide"
T=300000      # five minutes
FAR=999999999 # long since the last wash

check "idle, unlocked, nothing holding the screen" "wash" \
      "$(decide 1 1 Awake 400000 0 -1 no $T $FAR)"

# No app means nothing to start. Reported as a verdict so that `status` can
# say why nothing is happening, rather than it being buried in the log.
check "no app installed" "hold app-missing" \
      "$(decide 1 0 Awake 400000 0 -1 no $T $FAR)"
check "switched off beats a missing app" "hold disabled" \
      "$(decide 0 0 Awake 400000 0 -1 no $T $FAR)"
check "a missing app beats everything else" "hold app-missing" \
      "$(decide 1 0 Asleep 1 1 600000 yes $T 0)"
check "turned off" "hold disabled" \
      "$(decide 0 1 Awake 400000 0 -1 no $T $FAR)"
check "screen off" "hold not-awake" \
      "$(decide 1 1 Asleep 400000 0 -1 no $T $FAR)"
check "dozing counts as not awake" "hold not-awake" \
      "$(decide 1 1 Dozing 400000 0 -1 no $T $FAR)"
check "locked" "hold locked" \
      "$(decide 1 1 Awake 400000 0 -1 yes $T $FAR)"
check "unknown keyguard is treated as locked" "hold locked" \
      "$(decide 1 1 Awake 400000 0 -1 unknown $T $FAR)"
check "an app is holding the screen up" "hold screen-held" \
      "$(decide 1 1 Awake 400000 1 -1 no $T $FAR)"
check "an app asked for a longer timeout" "hold timeout-override" \
      "$(decide 1 1 Awake 400000 0 600000 no $T $FAR)"
check "still being used" "hold active" \
      "$(decide 1 1 Awake 4000 0 -1 no $T $FAR)"
check "exactly at the threshold fires" "wash" \
      "$(decide 1 1 Awake 300000 0 -1 no $T $FAR)"
check "one millisecond short does not" "hold active" \
      "$(decide 1 1 Awake 299999 0 -1 no $T $FAR)"

# Without this the daemon would fire on every poll for as long as the device
# was left alone, because idle keeps climbing and a wash does not reset it.
check "just washed, so wait" "hold cooldown" \
      "$(decide 1 1 Awake 400000 0 -1 no $T 1000)"
check "a full threshold later, wash again" "wash" \
      "$(decide 1 1 Awake 700000 0 -1 no $T $T)"

# An unreadable device must refuse, not be treated as idle forever.
check "missing idle refuses" "hold unreadable" \
      "$(decide 1 1 Awake -1 0 -1 no $T $FAR)"
check "garbage idle refuses" "hold unreadable" \
      "$(decide 1 1 Awake abc 0 -1 no $T $FAR)"

# Order of reasons: the most fundamental wins, so the reason reported is the
# one a person would give.
check "off beats everything" "hold disabled" \
      "$(decide 0 1 Asleep 1 1 600000 yes $T 0)"
check "asleep beats locked" "hold not-awake" \
      "$(decide 1 1 Asleep 400000 0 -1 yes $T $FAR)"

# --- polling interval --------------------------------------------------------
echo
echo " clamp_interval"
check "far away still caps at a minute" "60" "$(clamp_interval 900000)"
check "close by floors at ten seconds"  "10" "$(clamp_interval 1000)"
check "zero floors too"                 "10" "$(clamp_interval 0)"
check "mid range is proportional"       "30" "$(clamp_interval 30000)"
check "garbage floors rather than spins" "10" "$(clamp_interval abc)"

# --- settings, read from the app's own preferences ---------------------------
echo
echo " pref_get"
check "reads a boolean as a number" "0"    "$(pref_get $F/prefs-set.txt auto 1)"
check "reads an int"                "10"   "$(pref_get $F/prefs-set.txt idle_minutes 5)"
check "reads a float"               "0.5"  "$(pref_get $F/prefs-set.txt band 0.33)"
check "reads a long"                "4000" "$(pref_get $F/prefs-set.txt cycle_ms 2500)"
check "reads the panel target"      "2"    "$(pref_get $F/prefs-set.txt target 0)"
# The stale key sits ABOVE the real one in the fixture, so a substring match
# would return 99 here and putting it below would let that bug pass.
check "a stale similar key does not win" "10" \
      "$(pref_get $F/prefs-set.txt idle_minutes 5)"
check "missing key defaults"    "2" "$(pref_get $F/prefs-set.txt grain_x 2)"
check "empty prefs defaults"    "5" "$(pref_get $F/prefs-empty.txt idle_minutes 5)"
# A device where the settings screen has never been opened has no file at all,
# and must run on exactly the values the app would have shown.
check "no prefs file at all defaults" "5" "$(pref_get /nonexistent/x idle_minutes 5)"
check "defaults match Params.java" "1 5 0.33 2500 4 2 0" \
      "$(pref_get /nonexistent/x auto 1) $(pref_get /nonexistent/x idle_minutes 5) \
$(pref_get /nonexistent/x band 0.33) $(pref_get /nonexistent/x cycle_ms 2500) \
$(pref_get /nonexistent/x cycles 4) $(pref_get /nonexistent/x grain 2) \
$(pref_get /nonexistent/x target 0)"

# --- when the last wash ran --------------------------------------------------
# REGRESSION. `oledwatch decide` used to pass a hardcoded "ages ago" where the
# timer passes real elapsed time, because the last fire lived only in a shell
# variable the CLI could not read. So the one command whose job is "say what
# would happen right now" reported `wash` while the daemon was correctly
# holding -- observed three times on the device. The record is now a file, and
# both read it.
echo
echo " last_fire_ms"
LF=/tmp/oledwatch-lf.$$
BID=0d4b1f3a-0000-0000-0000-000000000001
OTHER=9999aaaa-0000-0000-0000-000000000002
NONE=-999999999

rm -f "$LF"
check "no record at all"            "$NONE" "$(last_fire_ms "$LF" "$BID" 500000)"

echo "$BID 120000" > "$LF"
check "a record from this boot"     "120000" "$(last_fire_ms "$LF" "$BID" 500000)"

# Uptime restarts at zero every boot, so a value from a previous boot is not
# merely stale, it is in a different coordinate system. A short previous boot
# followed by a long current one would leave a number that looks plausible.
echo "$OTHER 120000" > "$LF"
check "a record from a previous boot" "$NONE" "$(last_fire_ms "$LF" "$BID" 500000)"

echo "$BID 900000" > "$LF"
check "later than now is unusable"  "$NONE" "$(last_fire_ms "$LF" "$BID" 500000)"

echo "$BID banana" > "$LF"
check "garbage is unusable"         "$NONE" "$(last_fire_ms "$LF" "$BID" 500000)"

: > "$LF"
check "an empty file is unusable"   "$NONE" "$(last_fire_ms "$LF" "$BID" 500000)"

# And the whole point: a real record makes the cooldown visible to decide.
echo "$BID 400000" > "$LF"
now=500000
lf=$(last_fire_ms "$LF" "$BID" "$now")
check "a recent wash is held, not re-fired" "hold cooldown" \
      "$(decide 1 1 Awake 400000 0 -1 no $T $(( now - lf )))"
echo "$BID 100000" > "$LF"
lf=$(last_fire_ms "$LF" "$BID" "$now")
check "an old wash does not hold"   "wash" \
      "$(decide 1 1 Awake 400000 0 -1 no $T $(( now - lf )))"
# With no record at all, nothing is held back -- which is the state after a
# reboot, and the reason a fresh boot washes on schedule rather than waiting.
rm -f "$LF"
lf=$(last_fire_ms "$LF" "$BID" "$now")
check "no record does not hold"     "wash" \
      "$(decide 1 1 Awake 400000 0 -1 no $T $(( now - lf )))"
rm -f "$LF"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ] || exit 1
