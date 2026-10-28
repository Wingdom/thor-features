#!/system/bin/sh
# Install-time validation.
#
# This module overlays a vendor display config. If the stock file is not the
# one it was built against, the overlay could silently change more than the
# one element it intends to - so check before installing rather than after.
SKIPUNZIP=0

MODEL=$(getprop ro.product.model)
ui_print "- model: $MODEL"
case "$MODEL" in
  Thor|"AYN Thor"|"AYN Thor Lite") ;;
  *) abort "! This module is for the AYN Thor only (got '$MODEL')." ;;
esac

STOCK=/vendor/etc/displayconfig/display_layout_configuration.xml
OURS=$MODPATH/system/vendor/etc/displayconfig/display_layout_configuration.xml

[ -f "$STOCK" ] || abort "! No $STOCK on this build. Aborting."

# The overlay must differ from stock by exactly the lead-display line. Compare
# the two with that line stripped: anything else that differs means the ROM's
# file has changed under us and this overlay is stale.
if ! diff <(grep -v '<leadDisplayAddress>' "$STOCK") "$OURS" >/dev/null 2>&1; then
  ui_print "! The ROM's display layout config differs from the one this"
  ui_print "! module was built against. Installing would overwrite changes"
  ui_print "! it does not know about."
  abort "! Rebuild the overlay from the current $STOCK. Aborting."
fi

# And it must actually remove something, or the module is a no-op.
grep -q '<leadDisplayAddress>' "$STOCK" \
  || abort "! Stock file has no <leadDisplayAddress> - nothing to decouple. Aborting."

ui_print "- stock config matches the one this overlay was built from"
ui_print "- removing the bottom panel's leadDisplayAddress"

set_perm_recursive "$MODPATH" 0 0 0755 0644
ui_print "- installed. Reboot for it to take effect."
