#!/system/bin/sh
# Install-time validation. Abort rather than install onto hardware we don't
# recognise -- a wrong trip write is a thermal problem, not a software one.
SKIPUNZIP=0

MODEL=$(getprop ro.product.model)
ui_print "- model: $MODEL"
case "$MODEL" in
  Thor|"AYN Thor"|"AYN Thor Lite") ;;
  *) abort "! This module is for the AYN Thor only (got '$MODEL')." ;;
esac

# the fan must be a pwm-fan cooling device
FOUND=0
for c in /sys/class/thermal/cooling_device*; do
  [ "$(cat "$c/type" 2>/dev/null)" = "pwm-fan" ] && FOUND=1 && break
done
[ "$FOUND" = 1 ] || abort "! No pwm-fan cooling device found. Aborting."
ui_print "- found pwm-fan cooling device"

# and at least one writable fan-bound trip must exist
N=0
for z in /sys/class/thermal/thermal_zone*; do
  for c in "$z"/cdev*; do
    case "$c" in *_trip_point|*_weight) continue ;; esac
    [ -e "$c/type" ] || continue
    [ "$(cat "$c/type" 2>/dev/null)" = "pwm-fan" ] || continue
    tp=$(cat "${c}_trip_point" 2>/dev/null)
    [ -n "$tp" ] && [ -w "$z/trip_point_${tp}_temp" ] && N=$((N+1))
  done
done
[ "$N" -gt 0 ] || abort "! No writable fan-bound trip points. Aborting."
ui_print "- $N writable fan-bound trip points"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/fanctl" 0 0 0755
ui_print "- installed. Default mode is 'default' (stock behaviour)."
ui_print "- use: su -c 'fanctl quiet'   /   fanctl status   /   fanctl selftest"
