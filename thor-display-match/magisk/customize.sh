#!/system/bin/sh
# Generate the overlaid display_settings.xml from the ROM's own copy.
#
# We do not ship a static XML. The ROM's file already configures the secondary
# display, and a future nightly may change it; starting from the live file
# preserves whatever it says and adds only our entry.

ui_print "- thor-display-match"

DEVICE=$(getprop ro.product.device)
ui_print "  device: $DEVICE ($(getprop ro.product.model))"
case "$DEVICE" in
  odin2thor) ;;
  *) abort "! Not an odin2thor (got '$DEVICE'). Display ids are device-specific. Aborting." ;;
esac

# Resolving the ROM's own file is not as simple as reading /vendor/etc:
# if a previous version of this module is already mounted, that path IS our
# overlay and already contains the entry. So the first install saves a pristine
# copy, and every later install resolves against that.
STATE=${THOR_DM_STATE:-/data/adb/thor-display-match}
VENDOR=${THOR_DM_STOCK:-/vendor/etc/display_settings.xml}
SAVED="$STATE/stock.xml"

if [ -f "$SAVED" ]; then
  # A saved copy only stays valid while the ROM does. If the ROM moved, we
  # cannot see its new file from under our own mount -- say so plainly rather
  # than silently reapplying a stale overlay.
  PREV=$(awk -F= '/^rom_build=/{print $2}' "$STATE/install.info" 2>/dev/null)
  NOW=$(getprop ro.build.date.utc)
  if [ -n "$PREV" ] && [ "$PREV" != "$NOW" ]; then
    ui_print "! The ROM has changed since this module was installed."
    ui_print "  Its display_settings.xml cannot be read from under our own mount."
    ui_print "  Uninstall this module, reboot, then install again."
    abort    "! Refusing to reapply a stale overlay."
  fi
  STOCK="$SAVED"
  ui_print "  using the ROM copy saved at first install"
else
  STOCK="$VENDOR"
fi

[ -f "$STOCK" ] || abort "! $STOCK not found. This ROM does not use the file this module patches. Aborting."

DM="$MODPATH/system/bin/display-match"
[ -f "$DM" ] || abort "! module payload incomplete: $DM missing. Aborting."

ID=$(sh "$DM" primary-id)
[ -n "$ID" ] || abort "! Could not determine the primary display id from dumpsys. Aborting."
ui_print "  primary display: $ID"

case "$ID" in
  local:*) ;;
  *) abort "! Primary display id '$ID' is not a local: id. Aborting." ;;
esac

if grep -q "name=\"$ID\"" "$STOCK"; then
  if [ -d "${THOR_DM_MODDIR:-/data/adb/modules/thor-display-match}" ] && [ ! -f "$SAVED" ]; then
    # Our own overlay is mounted over /vendor and we have no pristine copy, so
    # the ROM's real file is unreachable. Do not guess at it.
    ui_print "! A previous version of this module is mounted, and no pristine"
    ui_print "  copy of the ROM's file was saved by it."
    ui_print "  Uninstall this module, reboot, then install again."
    abort    "! Cannot read the ROM's file from under our own mount."
  fi
  ui_print "! The ROM already has an entry for the primary display."
  ui_print "  Installing anyway would change nothing. Aborting."
  abort    "! Nothing to do."
fi

mkdir -p "$STATE"
if [ ! -f "$SAVED" ]; then
  cp "$STOCK" "$SAVED" || abort "! Could not save a copy of the ROM's file. Aborting."
  ui_print "  saved the ROM's file for future upgrades"
fi

mkdir -p "$MODPATH/system/vendor/etc"
sh "$DM" make-xml "$STOCK" "$ID" > "$MODPATH/system/vendor/etc/display_settings.xml" \
  || abort "! Failed to generate the XML. Aborting."

grep -q "ignoreOrientationRequest" "$MODPATH/system/vendor/etc/display_settings.xml" \
  || abort "! Generated XML lacks ignoreOrientationRequest. Aborting."
grep -q "name=\"$ID\"" "$MODPATH/system/vendor/etc/display_settings.xml" \
  || abort "! Generated XML lacks the primary display entry. Aborting."
ui_print "  generated overlay, $(wc -c < "$MODPATH/system/vendor/etc/display_settings.xml") bytes"

# Record what we built against, so service.sh can warn when a ROM update moves
# underneath us.
mkdir -p "$STATE"
{
  echo "built_at=$(date 2>/dev/null)"
  echo "display_id=$ID"
  echo "rom_build=$(getprop ro.build.date.utc)"
  echo "rom_version=$(getprop ro.lineage.build.version)"
} > "$STATE/install.info"

# The per-user override in /data wins over the vendor file. If one exists it
# will mask this module -- for better or worse -- so say so rather than letting
# a test silently measure the wrong thing.
if [ -f "${THOR_DM_DATA_OVERRIDE:-/data/system/display_settings.xml}" ]; then
  ui_print "! /data/system/display_settings.xml exists and OVERRIDES this module."
  ui_print "  If it was created by 'wm set-ignore-orientation-request', delete it"
  ui_print "  to let this module's vendor-level setting take effect:"
  ui_print "    su -c 'rm /data/system/display_settings.xml' && reboot"
fi

ui_print "- installed. Reboot to apply."

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  set_perm "$MODPATH/system/bin/display-match" 0 0 0755
  set_perm "$MODPATH/system/vendor/etc/display_settings.xml" 0 0 0644
  ui_print "  permissions set"
}
