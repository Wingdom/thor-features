#!/system/bin/sh
# Refuse anything that is not an AYN Thor with the expected LED hardware.
# A wrong device would mean writing triggers to unrelated LEDs.

ui_print "- thor-stickfx"

MODEL=$(getprop ro.product.model)
DEVICE=$(getprop ro.product.device)
ui_print "  device: $DEVICE ($MODEL)"

case "$DEVICE" in
  odin2thor) ;;
  *) ui_print "! Not an odin2thor (got '$DEVICE')."
     abort  "! The stick LED layout is device-specific. Aborting." ;;
esac

# the 8 multicolor stick LEDs must exist, with a trigger file each
N=0
for d in /sys/class/leds/*:stick:*; do
  [ -e "$d/trigger" ] || continue
  [ -e "$d/multi_intensity" ] || continue
  N=$((N+1))
done
ui_print "  stick LEDs found: $N"
[ "$N" -gt 0 ] || abort "! No multicolor stick LEDs found. Aborting."

# and they must advertise the charge trigger we rely on
FIRST=$(for d in /sys/class/leds/*:stick:*; do [ -e "$d/trigger" ] && echo "$d" && break; done)
# Gate on the trigger we actually bind by default: usb-online. The battery-*
# variants are still selectable with --trigger, so check but only warn.
if ! tr -d '[]' < "$FIRST/trigger" | tr '\n' ' ' | grep -q ' usb-online '; then
  abort "! Kernel does not advertise usb-online. Aborting."
fi
ui_print "  usb-online: available"
if ! tr -d '[]' < "$FIRST/trigger" | tr '\n' ' ' | grep -q ' battery-charging-blink-full-solid '; then
  ui_print "  (note: blink variant unavailable; --trigger blink will not work)"
fi

ui_print "- installed. Nothing changes until you pick a mode:"
ui_print "    stickfx charge          (uses AynParts' colour)"
ui_print "    stickfx charge --color 2BE0D8   (stock AYN teal)"
ui_print "    stickfx off             (restore)"

# Magisk calls this AFTER extracting the module files. The top-level code above
# runs BEFORE extraction, which is why the permission calls cannot live there:
# in v0.1.0 they ran against a $MODPATH/system that did not exist yet. Harmless
# then only because the zip already carried the executable bit, but wrong.
set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  set_perm "$MODPATH/system/bin/stickfx" 0 0 0755
  ui_print "  permissions set"
}
