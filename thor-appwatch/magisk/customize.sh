#!/system/bin/sh
# Install-time validation.
SKIPUNZIP=0

MODEL=$(getprop ro.product.model)
ui_print "- model: $MODEL"
case "$MODEL" in
  Thor|"AYN Thor"|"AYN Thor Lite") ;;
  *) abort "! This module is for the AYN Thor only (got '$MODEL')." ;;
esac

# thor-fanctl is a hard requirement, not a soft one. Without it there is
# nothing to apply: the table would record tiers that nothing could ever act
# on. Refuse rather than install something inert and confusing.
FANCTL=${FANCTL:-/system/bin/fanctl}
[ -x "$FANCTL" ] || abort "! thor-fanctl is required but /system/bin/fanctl is missing.
!   Install the thor-fanctl module first, reboot, then install this one.
!   Note Magisk stages modules and swaps them at the NEXT boot, so a fanctl
!   installed in this same session will not be visible until you reboot."
ui_print "- fanctl: present"

# The baseline must be a real tier. `default` means the user ran `fanctl
# default` and has opted out of fan management, and this module must never
# call `fanctl default` to "restore" anything - that would undo the usb-therm
# fix fanctl exists for. Warn rather than abort: they can pick a tier later.
MODE=$("$FANCTL" get 2>/dev/null | tr -d '\r\n')
case "$MODE" in
  quiet|balanced|performance) ui_print "- baseline: $MODE" ;;
  *) ui_print "! fanctl baseline is '$MODE', which is not a tier."
     ui_print "  Per-app profiles will not engage until you pick quiet,"
     ui_print "  balanced or performance (the Fan tile, or 'fanctl quiet')." ;;
esac

STATE=${STATE:-/data/adb/thor-appwatch}
mkdir -p "$STATE"
# Enabled by default, which is safe because it is also inert: with no profiles
# in the table there is no override, so the daemon resolves to the baseline
# and applies nothing.
[ -f "$STATE/enabled" ] || echo 1 > "$STATE/enabled"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/appwatch" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

ui_print "- installed."
if [ -s "$STATE/profiles" ]; then
  ui_print "- $(awk '$2=="fan"' "$STATE/profiles" | wc -l) fan profile(s) already configured."
else
  ui_print "! No profiles yet - install the thor-appprofiles app to add some."
  ui_print "  Until then this module changes nothing."
fi
