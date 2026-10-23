#!/system/bin/sh
# Install-time validation. This module is harmless on the wrong device - it
# writes nothing - but a `screenmove` that cannot find a second screen is just
# a confusing command, so refuse rather than install it.
SKIPUNZIP=0

MODEL=$(getprop ro.product.model)
ui_print "- model: $MODEL"
case "$MODEL" in
  Thor|"AYN Thor"|"AYN Thor Lite") ;;
  *) abort "! This module is for the AYN Thor only (got '$MODEL')." ;;
esac

# There must be a second internal display to move things to. Resolved the same
# way the script does, so a pass here means the script will work.
SECOND=$(dumpsys display 2>/dev/null | tr ',' '\n' | awk '
    /DisplayViewport\{type=INTERNAL/ { internal=1 }
    internal && /displayId=/ {
        if (match($0, /displayId=[0-9]+/)) {
            id = substr($0, RSTART+10, RLENGTH-10)
            if (id != 0) { print id; exit }
        }
        internal=0
    }')
[ -n "$SECOND" ] || abort "! No second internal display found. Aborting."
ui_print "- second internal display: $SECOND"

# `am display move-stack` is the whole mechanism; refuse if this build lacks it.
if ! am 2>&1 | grep -q "move-stack"; then
  abort "! This build's activity manager has no 'display move-stack'. Aborting."
fi
ui_print "- activity manager supports 'display move-stack'"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/screenmove" 0 0 0755
ui_print "- installed."
ui_print "- use: su -c 'screenmove status'  /  screenmove toggle"
