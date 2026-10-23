#!/system/bin/sh
# stickfxlog - record LED + charge state ON THE DEVICE, so the charger can be
# unplugged without losing the log.
#
# Why this exists: on the Thor, USB *is* the charger. Any test that says
# "unplug the charger" cannot also use adb over USB -- the connection dies with
# the cable. So this runs on-device under nohup, survives the unplug, and is
# read back afterwards.
#
# LEDs are discovered by name, never hardcoded: cooling_device34 became 35
# across a reboot once already, and AynParts references :strip / :stick group
# nodes that do not exist on the Thor. See README.md.
#
# Usage, from an adb shell while still plugged in:
#     nohup /data/local/tmp/stickfxlog.sh > /dev/null 2>&1 &
#     echo $! > /data/local/tmp/stickfxlog.pid
# then unplug, run the test, plug back in, and:
#     kill $(cat /data/local/tmp/stickfxlog.pid)
#     cat /data/local/tmp/stickfxlog.csv
set -u
OUT=${1:-/data/local/tmp/stickfxlog.csv}
INT=${2:-2}
B=/sys/class/power_supply/battery
LOCK=${OUT}.lock

# Singleton guard. Two instances appending to the same CSV produced duplicate
# rows (708 total, 662 unique) and silently halved the apparent sample interval,
# because the start command was handed out once in a test plan and run again
# here. mkdir is atomic on every Android filesystem we care about.
if ! mkdir "$LOCK" 2>/dev/null; then
    if [ -f "$LOCK/pid" ] && kill -0 "$(cat "$LOCK/pid")" 2>/dev/null; then
        echo "already running as pid $(cat "$LOCK/pid"); refusing to double-log" >&2
        exit 1
    fi
    echo "removing a stale lock from pid $(cat "$LOCK/pid" 2>/dev/null)" >&2
    rm -rf "$LOCK"; mkdir "$LOCK" 2>/dev/null || { echo "cannot lock $LOCK" >&2; exit 1; }
fi
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT INT TERM

LEDS=""
for d in /sys/class/leds/*:stick:*; do
    [ -e "$d/brightness" ] || continue
    [ -e "$d/multi_intensity" ] || continue
    LEDS="$LEDS $d"
done
[ -n "$LEDS" ] || { echo "no stick LEDs found" >&2; exit 1; }
FIRST=$(echo $LEDS | awk '{print $1}')

# active trigger of the first LED. Parsed by string manipulation, never
# `for x in $list`: the active entry is bracketed and
# "[battery-charging-blink-full-solid]" is a valid glob character class, which
# with cwd "/" expands to the single-char entry "d" and eats the bracket.
act() {
    _l=$(tr '\n' ' ' < "$FIRST/trigger")
    case "$_l" in
        *'['*']'*) _l=${_l#*\[}; echo "${_l%%\]*}" ;;
        *) echo none ;;
    esac
}

# one brightness column per discovered LED, in discovery order
hdr="uptime_s,batt_pct,batt_status,current_now_uA,trigger"
for d in $LEDS; do hdr="$hdr,${d##*/}"; done
echo "$hdr" > "$OUT"

while true; do
    row="$(cut -d. -f1 /proc/uptime),$(cat $B/capacity 2>/dev/null),$(cat $B/status 2>/dev/null),$(cat $B/current_now 2>/dev/null),$(act)"
    for d in $LEDS; do
        row="$row,$(cat "$d/brightness" 2>/dev/null)"
    done
    echo "$row" >> "$OUT"
    sleep "$INT"
done
