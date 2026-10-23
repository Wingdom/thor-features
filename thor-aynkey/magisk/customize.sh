#!/system/bin/sh
# Install-time validation.
SKIPUNZIP=0

MODEL=$(getprop ro.product.model)
ui_print "- model: $MODEL"
case "$MODEL" in
  Thor|"AYN Thor"|"AYN Thor Lite") ;;
  *) abort "! This module is for the AYN Thor only (got '$MODEL')." ;;
esac

# The whole module is "watch this key on this device". If the key is not
# there, nothing it does can work.
DEV=$(getevent -pl 2>/dev/null | awk '
    /^add device/      { node = $NF; name = ""; haskey = 0; next }
    /^[ \t]+name:/     { name = $2; gsub(/"/, "", name); next }
    index($0, "KEY_F24") > 0 { haskey = 1 }
    name == "gpio-keys" && haskey { print node; exit }')
[ -n "$DEV" ] || abort "! No gpio-keys device carrying KEY_F24 found. Aborting."
ui_print "- AYN button (KEY_F24) on $DEV"

mkdir -p /data/adb/thor-aynkey

# The key layout replaces Generic.kl for this device, so it must carry every
# key the device has. Refuse rather than ship a build that kills volume up.
KL="$MODPATH/system/usr/keylayout/Vendor_0001_Product_0001.kl"
if [ -f "$KL" ]; then
  grep -q "^key 115 " "$KL" || abort "! Key layout is missing VOLUME_UP. Aborting."
  grep -q "^key 194 .*WAKE" "$KL" || abort "! Key layout is missing the F24 WAKE flag. Aborting."
  ui_print "- key layout: F24 gains WAKE, VOLUME_UP preserved"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/aynkeyd" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

ui_print "- installed."
if [ -x /data/adb/thor-aynkey/action.sh ]; then
  ui_print "- action script present; the button is live after a reboot."
else
  ui_print "! No /data/adb/thor-aynkey/action.sh yet - presses will only be"
  ui_print "  logged (logcat -s aynkeyd) until one is installed."
fi
