#!/system/bin/sh
# Runs when the module is removed. The XML overlay needs nothing -- it vanishes
# with the mount. The /data override does: see below.
STATE=${THOR_DM_STATE:-/data/adb/thor-display-match}
# The framework persisted our vendor-file value into /data on the first boot
# after install; it outlives the module, so removing it is part of uninstalling.
rm -f "${THOR_DM_DATA_OVERRIDE:-/data/system/display_settings.xml}"

rm -rf "$STATE"
