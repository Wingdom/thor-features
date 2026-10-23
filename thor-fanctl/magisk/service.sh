#!/system/bin/sh
# Runs late_start. Re-apply the persisted mode after boot.
#
# Why capture --force here: at boot the kernel has just parsed the device tree,
# so every trip is guaranteed to be at its stock value. This is the only moment
# we can record originals with certainty. Doing it every boot makes the stored
# originals self-healing -- they can never drift to a modified value.

FANCTL=/system/bin/fanctl
LOG=/data/adb/thor-fanctl/boot.log

# wait for the thermal subsystem to expose the fan (bounded)
i=0
while [ "$i" -lt 60 ]; do
    for c in /sys/class/thermal/cooling_device*; do
        [ "$(cat "$c/type" 2>/dev/null)" = "pwm-fan" ] && break 2
    done
    sleep 1
    i=$((i+1))
done

mkdir -p /data/adb/thor-fanctl
{
    echo "--- boot $(date 2>/dev/null) (waited ${i}s) ---"
    "$FANCTL" capture --force
    MODE=$("$FANCTL" get)
    echo "persisted mode: $MODE"
    case "$MODE" in
        quiet|performance) "$FANCTL" "$MODE" ;;
        *) echo "nothing to apply" ;;
    esac
} >> "$LOG" 2>&1
