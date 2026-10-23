#!/system/bin/sh
# Runs late_start. Re-applies the saved preset to the MAIN display only.
#
# This is what makes the choice survive a ROM update: the forced density lives
# in a framework setting, and re-asserting it at boot means the module's saved
# preset is the source of truth rather than whatever the framework happens to
# hold.

STATE=${THOR_DD_STATE:-/data/adb/thor-display-density}
LOG=$STATE/boot.log
DD=/system/bin/display-density

[ -f "$STATE/preset" ] || exit 0

i=0
while [ "$i" -lt 90 ]; do
    [ "$(getprop sys.boot_completed)" = 1 ] && break
    sleep 1
    i=$((i+1))
done
sleep 5

WANT=$(cat "$STATE/preset" 2>/dev/null)
mkdir -p "$STATE"
{
    echo "--- boot $(date 2>/dev/null) (waited ${i}s for boot_completed) ---"
    echo "saved preset: $WANT"

    BEFORE=$(wm density -d 0 2>/dev/null | tr '\n' ' ')
    wm density "$WANT" -d 0 >/dev/null 2>&1
    sleep 1

    # Verify the downstream effect, not the exit code.
    GOT=$(wm density -d 0 2>/dev/null \
        | awk -F': *' '/Override density/{o=$2} /Physical density/{p=$2} END{print (o?o:p)}')
    if [ "$GOT" = "$WANT" ]; then
        echo "applied: display 0 now $GOT  (was: $BEFORE)"
    else
        echo "FAILED: wanted $WANT, display 0 reads $GOT"
    fi
    echo "display 1: $(wm density -d 1 2>/dev/null | tr '\n' ' ') (untouched)"
} >> "$LOG" 2>&1
