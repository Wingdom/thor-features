#!/system/bin/sh
# Installs the command and the boot hook. Applies nothing on its own.

ui_print "- thor-display-density"

DEVICE=$(getprop ro.product.device)
ui_print "  device: $DEVICE ($(getprop ro.product.model))"
case "$DEVICE" in
  odin2thor) ;;
  *) abort "! Not an odin2thor (got '$DEVICE'). The presets are sized for this panel. Aborting." ;;
esac

DD="$MODPATH/system/bin/display-density"
[ -f "$DD" ] || abort "! module payload incomplete: $DD missing. Aborting."

# The presets assume a 1920x1080 main display at ~320dpi. On anything else the
# dp figures in the descriptions would be wrong.
PHYS=$(wm density -d 0 2>/dev/null | awk -F': *' '/Physical density/{print $2; exit}')
ui_print "  main display physical density: ${PHYS:-unknown}"
if [ -n "$PHYS" ] && [ "$PHYS" != 320 ]; then
  ui_print "! Expected a 320dpi main display, found $PHYS."
  ui_print "  The preset descriptions would be misleading. Aborting."
  abort    "! Unexpected panel."
fi

ui_print "- installed. Nothing changes until you pick one:"
ui_print "    display-density 288      tablet layouts (sw600dp)"
ui_print "    display-density 369      stock AYN look"
ui_print "    display-density 320      panel density, forced"
ui_print "    display-density native   panel default"
ui_print "  Main screen only; the second display is left alone."

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  set_perm "$MODPATH/system/bin/display-density" 0 0 0755
  ui_print "  permissions set"
}
