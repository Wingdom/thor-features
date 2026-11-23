#!/system/bin/sh
# Install-time validation.
#
# This module is only a timer. It writes no hardware node and holds no state
# the device needs back, so it is safe almost anywhere - but a timer whose
# alarm has nothing to ring is just a confusing thing to have installed, so
# refuse rather than install it.
SKIPUNZIP=0

PKG=com.thorfeatures.oledwash

MODEL=$(getprop ro.product.model)
ui_print "- model: $MODEL"

# Deliberately NOT gated on the Thor. The wash is written against whatever
# panels the device reports, and the idle signal is plain framework state, so
# this works on any AYN OLED handheld running a recent Android with Magisk.
# Nothing here is Thor-specific.

# The app is the thing that draws. Without it this module has nothing to start.
if ! pm path "$PKG" >/dev/null 2>&1; then
  ui_print " "
  ui_print "! The Thor OLED Wash app is not installed."
  ui_print "!"
  ui_print "! This module only decides WHEN to wash. The app decides what to"
  ui_print "! draw, and it is where the wash actually lives."
  ui_print "!"
  ui_print "! Install thor-oledwash.apk first, then flash this again."
  abort "! Aborting."
fi
ui_print "- found $PKG"

# dumpsys is the idle signal and am is how the wash is started. Both are part
# of the platform, so their absence means something is very wrong.
for t in dumpsys am pm; do
  command -v "$t" >/dev/null 2>&1 || abort "! No '$t' on PATH. Aborting."
done
ui_print "- dumpsys, am, pm present"

# Verify the field the whole thing hangs on actually exists on this build.
# Android does not promise dumpsys output, and a silent parse failure here
# would mean a module that installs cleanly and then never fires.
if dumpsys power 2>/dev/null | grep -q '^lastUserActivityTime='; then
  ui_print "- idle signal readable"
else
  ui_print " "
  ui_print "! dumpsys power has no 'lastUserActivityTime=' line on this build."
  ui_print "! That is the idle signal, so this module could not fire."
  abort "! Aborting."
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/oledwatch" 0 0 0755

ui_print " "
ui_print "- Installed. Reboot, then open Thor OLED Wash to set the idle time."
ui_print "- Defaults to a wash every 5 minutes of inactivity."
ui_print "- To stop it without uninstalling: oledwatch stop, or disable this"
ui_print "  module and reboot."
