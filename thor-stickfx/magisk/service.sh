#!/system/bin/sh
# Runs late_start. Record the LEDs' stock state, then re-apply the saved mode.
#
# Why we wait for boot to settle before capturing:
#   LineageOS ships org.lineageos.settings.ayn (AynParts), whose own
#   BootCompletedReceiver writes these LEDs at boot -- it either applies the
#   user's saved colours or calls turnOffAll(). That write is what "stock"
#   means for us, so the originals must be recorded AFTER it lands. Capturing
#   too early would record a pre-AynParts state, and `stickfx off` would then
#   restore something the user never chose.

STICKFX=/system/bin/stickfx
LOG=/data/adb/thor-stickfx/boot.log

# wait for boot_completed (bounded), then let AynParts' receiver run
i=0
while [ "$i" -lt 90 ]; do
    [ "$(getprop sys.boot_completed)" = 1 ] && break
    sleep 1
    i=$((i+1))
done
sleep 8

mkdir -p /data/adb/thor-stickfx
{
    echo "--- boot $(date 2>/dev/null) (waited ${i}s for boot_completed) ---"
    "$STICKFX" capture --force
    MODE=$("$STICKFX" get)
    COLOR=$("$STICKFX" getcolor)
    echo "persisted mode: $MODE   colour: ${COLOR:-<none, AynParts owns it>}"
    case "$MODE" in
        charge|breathe)
            if [ -n "$COLOR" ]; then
                "$STICKFX" "$MODE" --color "$COLOR"
            else
                "$STICKFX" "$MODE"
            fi ;;
        *) echo "nothing to apply" ;;
    esac
} >> "$LOG" 2>&1
