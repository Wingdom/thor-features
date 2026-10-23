#!/system/bin/sh
# Runs when the module is removed. Put the LEDs back to what was recorded at
# boot, then drop our state. A reboot alone would also restore them, because
# AynParts rewrites the LEDs at every boot and our service.sh is gone -- but
# restore anyway in case this runs on a live system.
STICKFX=/system/bin/stickfx
[ -x "$STICKFX" ] && "$STICKFX" off >/dev/null 2>&1
rm -rf /data/adb/thor-stickfx
