#!/system/bin/sh
# Restore the baseline, then remove everything.
#
# REVERT IS NOT OPTIONAL, and this is the trap that makes it so: the tier we
# applied is persisted by *fanctl*, in fanctl's own state, and fanctl's
# service.sh re-applies it at every boot. So removing this module without
# restoring would leave the user's fan pinned at whatever a game last asked
# for, with nothing left installed to explain why. Same shape as
# thor-display-density and thor-display-match.
#
# This deliberately does NOT call `appwatch`: by the time uninstall runs the
# binary may already be gone. It reads the recorded baseline and drives fanctl
# directly, exactly as thor-screenblank's uninstall relights the panels by
# hand rather than calling its own CLI.
STATE=${STATE:-/data/adb/thor-appwatch}
FANCTL=${FANCTL:-/system/bin/fanctl}
APPWATCH=${APPWATCH:-/system/bin/appwatch}
# fanctl's own persisted mode, on /data. See the fallback below for why this
# matters.
FANCTL_MODE=${FANCTL_MODE:-/data/adb/thor-fanctl/mode}

[ -x "$APPWATCH" ] && "$APPWATCH" stop 2>/dev/null

# Belt and braces: kill by argv0, never by a pattern that this script's own
# command line would match - a `pkill -f appwatch` here would match itself.
for p in $(ps -eo pid,args 2>/dev/null | awk '$2 ~ /appwatch$/ {print $1}'); do
  kill "$p" 2>/dev/null
done

BASE=$(awk '$1=="fan" {print $2; exit}' "$STATE/baseline" 2>/dev/null)
case "$BASE" in
  quiet|balanced|performance)
    if [ -x "$FANCTL" ]; then
      # Direct path: available when uninstall.sh is run by hand from a shell.
      "$FANCTL" "$BASE" >/dev/null 2>&1
      got=$("$FANCTL" get 2>/dev/null | tr -d '\r\n')
      # Verified by read-back, not by exit code.
      if [ "$got" = "$BASE" ]; then
        /system/bin/log -p i -t appwatch "uninstall: restored baseline $BASE" 2>/dev/null
      else
        /system/bin/log -p w -t appwatch "uninstall: restore reported '$got', wanted '$BASE'" 2>/dev/null
      fi
    elif [ -f "$FANCTL_MODE" ]; then
      # BOOT PATH, and the one that actually runs. Magisk executes uninstall.sh
      # BEFORE any module's /system is mounted - confirmed in Magisk's own log,
      # where "thor-appwatch: remove" precedes "thor-fanctl: loading module
      # files". So /system/bin/fanctl does not exist yet and cannot be called.
      #
      # Measured consequence of getting this wrong: the restore was skipped
      # silently, `rm -rf "$STATE"` then destroyed the recorded baseline, and
      # the device was left pinned at the last override's tier with nothing
      # installed to explain it - precisely the failure the module warns about.
      #
      # What IS mounted at post-fs-data is /data, where fanctl persists its own
      # mode and re-reads it from its service.sh later in the SAME boot
      # (late_start, well after this). Writing the recorded baseline there hands
      # the restore to fanctl itself, which is also exactly what a user would do
      # by hand. This is the only sequence available at this point in boot.
      echo "$BASE" > "$FANCTL_MODE"
      /system/bin/log -p i -t appwatch \
        "uninstall: fanctl not mounted yet; set its persisted mode to $BASE for this boot" 2>/dev/null
    else
      /system/bin/log -p w -t appwatch \
        "uninstall: could not restore baseline $BASE - no fanctl and no $FANCTL_MODE" 2>/dev/null
    fi
    ;;
  *) : ;;   # no override was active, so there is nothing to put back
esac

rm -rf "$STATE"
exit 0
