#!/system/bin/sh
# Stop the daemon and remove our state.
#
# There is deliberately nothing to restore, and that is worth saying plainly
# because the sibling modules in this repository are not like this. thor-fanctl
# holds thermal trip points, thor-appwatch leaves a fan tier persisted in
# fanctl's own state, thor-display-density writes a Settings.Secure value - all
# of them must put something back, and thor-appwatch's uninstall ran before its
# dependency was even mounted, which took a boot to find.
#
# This module writes no hardware node, no system property and no framework
# setting. The only thing it ever did was start an Activity, and an Activity
# that is not running has left nothing behind. So: stop, delete, exit.
STATE=${STATE:-/data/adb/thor-oledwatch}
OLEDWATCH=${OLEDWATCH:-/system/bin/oledwatch}

[ -x "$OLEDWATCH" ] && "$OLEDWATCH" stop 2>/dev/null

# Belt and braces, by argv0 and never by a pattern this script's own command
# line would match. A `pkill -f oledwatch` here would match itself and kill the
# uninstall mid-way, leaving the state directory behind.
for p in $(ps -eo pid,args 2>/dev/null | awk '$2 ~ /oledwatch$/ {print $1}'); do
  kill "$p" 2>/dev/null
done

rm -rf "$STATE"
exit 0
