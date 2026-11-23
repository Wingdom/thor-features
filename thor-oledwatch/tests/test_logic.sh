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
# decide <enabled> <wake> <idle> <lock> <override> <keyguard> <threshold> <since>
echo
echo " decide"
T=300000      # five minutes
FAR=999999999 # long since the last wash

check "idle, unlocked, nothing holding the screen" "wash" \
      "$(decide 1 Awake 400000 0 -1 no $T $FAR)"

check "turned off" "hold disabled" \
      "$(decide 0 Awake 400000 0 -1 no $T $FAR)"
check "screen off" "hold not-awake" \
      "$(decide 1 Asleep 400000 0 -1 no $T $FAR)"
check "dozing counts as not awake" "hold not-awake" \
      "$(decide 1 Dozing 400000 0 -1 no $T $FAR)"
check "locked" "hold locked" \
      "$(decide 1 Awake 400000 0 -1 yes $T $FAR)"
check "unknown keyguard is treated as locked" "hold locked" \
      "$(decide 1 Awake 400000 0 -1 unknown $T $FAR)"
check "an app is holding the screen up" "hold screen-held" \
      "$(decide 1 Awake 400000 1 -1 no $T $FAR)"
check "an app asked for a longer timeout" "hold timeout-override" \
      "$(decide 1 Awake 400000 0 600000 no $T $FAR)"
check "still being used" "hold active" \
      "$(decide 1 Awake 4000 0 -1 no $T $FAR)"
check "exactly at the threshold fires" "wash" \
      "$(decide 1 Awake 300000 0 -1 no $T $FAR)"
check "one millisecond short does not" "hold active" \
      "$(decide 1 Awake 299999 0 -1 no $T $FAR)"

# Without this the daemon would fire on every poll for as long as the device
# was left alone, because idle keeps climbing and a wash does not reset it.
check "just washed, so wait" "hold cooldown" \
      "$(decide 1 Awake 400000 0 -1 no $T 1000)"
check "a full threshold later, wash again" "wash" \
      "$(decide 1 Awake 700000 0 -1 no $T $T)"

# An unreadable device must refuse, not be treated as idle forever.
check "missing idle refuses" "hold unreadable" \
      "$(decide 1 Awake -1 0 -1 no $T $FAR)"
check "garbage idle refuses" "hold unreadable" \
      "$(decide 1 Awake abc 0 -1 no $T $FAR)"

# Order of reasons: the most fundamental wins, so the reason reported is the
# one a person would give.
check "off beats everything" "hold disabled" \
      "$(decide 0 Asleep 1 1 600000 yes $T 0)"
check "asleep beats locked" "hold not-awake" \
      "$(decide 1 Asleep 400000 0 -1 yes $T $FAR)"

# --- polling interval --------------------------------------------------------
echo
echo " clamp_interval"
check "far away still caps at a minute" "60" "$(clamp_interval 900000)"
check "close by floors at ten seconds"  "10" "$(clamp_interval 1000)"
check "zero floors too"                 "10" "$(clamp_interval 0)"
check "mid range is proportional"       "30" "$(clamp_interval 30000)"
check "garbage floors rather than spins" "10" "$(clamp_interval abc)"

# --- config ------------------------------------------------------------------
echo
echo " config_get"
CFG=/tmp/oledwatch-cfg.$$
printf 'enabled=1\nidle_minutes=10\nband=0.50\nblank=\n' > "$CFG"
check "reads a value"          "10"   "$(config_get "$CFG" idle_minutes 5)"
check "reads a decimal"        "0.50" "$(config_get "$CFG" band 0.33)"
check "missing key defaults"   "2"    "$(config_get "$CFG" grain 2)"
check "blank value defaults"   "7"    "$(config_get "$CFG" blank 7)"
check "missing file defaults"  "5"    "$(config_get /nonexistent/x idle_minutes 5)"
# A key that is a prefix of another must not match it.
printf 'idle_minutes_old=99\nidle_minutes=3\n' > "$CFG"
check "prefix key does not match" "3" "$(config_get "$CFG" idle_minutes 5)"
rm -f "$CFG"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ] || exit 1
