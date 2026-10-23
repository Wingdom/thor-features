#!/system/bin/sh
# Runs when the module is removed. A forced density is a framework setting and
# outlives us, so clear it rather than leaving the user's display changed by a
# module that no longer exists. Main display only.
STATE=${THOR_DD_STATE:-/data/adb/thor-display-density}
if [ -f "$STATE/preset" ]; then
    wm density reset -d 0 >/dev/null 2>&1
fi
rm -rf "$STATE"
