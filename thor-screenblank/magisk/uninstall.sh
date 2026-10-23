#!/system/bin/sh
# Put both panels back before the command that could do it goes away.
#
# This matters more here than it did for screenmove. screenmove's effects are
# task placements that a reboot undoes; screenblank's are two live hardware
# writes, and uninstalling while a screen is blanked would remove the only
# tool that turns it back on. So restore unconditionally, by hand rather than
# by calling the binary, since by this point it may already be gone.
for p in panel0-backlight panel1-backlight; do
  [ -e "/sys/class/backlight/$p/bl_power" ] && echo 0 > "/sys/class/backlight/$p/bl_power" 2>/dev/null
done

for want in fts_ts fts_ts_secondary; do
  for d in /sys/class/input/input*; do
    [ -r "$d/name" ] || continue
    [ "$(cat "$d/name" 2>/dev/null)" = "$want" ] || continue
    [ -e "$d/device/fts_irq" ] && echo 1 > "$d/device/fts_irq" 2>/dev/null
    break
  done
done

rm -rf /data/adb/thor-screenblank
exit 0
