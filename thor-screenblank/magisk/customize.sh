#!/system/bin/sh
# Install-time validation.
#
# screenblank is harmless on the wrong device - it only writes two nodes, and
# refuses if it cannot find them - but a command that cannot turn a screen off
# is just confusing, so refuse rather than install it.
SKIPUNZIP=0

MODEL=$(getprop ro.product.model)
ui_print "- model: $MODEL"
case "$MODEL" in
  Thor|"AYN Thor"|"AYN Thor Lite") ;;
  *) abort "! This module is for the AYN Thor only (got '$MODEL')." ;;
esac

# Both panels must have a backlight with a bl_power node. This is the half
# that makes the screen genuinely dark; without it the module can do nothing
# an overlay could not.
for p in panel0-backlight panel1-backlight; do
  [ -e "/sys/class/backlight/$p/bl_power" ] \
    || abort "! No /sys/class/backlight/$p/bl_power. Aborting."
  ui_print "- found $p/bl_power"
done

# And both digitisers must expose fts_irq. This is the half that kills touch.
# Resolved by name, never by input index - those are assigned at boot and have
# already moved once on this device.
for want in fts_ts fts_ts_secondary; do
  found=""
  for d in /sys/class/input/input*; do
    [ -r "$d/name" ] || continue
    [ "$(cat "$d/name" 2>/dev/null)" = "$want" ] || continue
    [ -e "$d/device/fts_irq" ] || continue
    found=$d
    break
  done
  [ -n "$found" ] || abort "! No input device named '$want' with fts_irq. Aborting."
  ui_print "- found $want at $found"
done

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/screenblank" 0 0 0755
ui_print "- installed."
ui_print "- use: su -c 'screenblank status'  /  screenblank toggle bottom"
