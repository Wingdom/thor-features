#!/system/bin/sh
# Runs when the module is removed. Trips are already stock at this point (the
# kernel re-read the device tree this boot and our service.sh is gone), so this
# is mostly cleanup -- but restore anyway in case it is invoked live.
FANCTL=/system/bin/fanctl
[ -x "$FANCTL" ] && "$FANCTL" default >/dev/null 2>&1
rm -rf /data/adb/thor-fanctl
