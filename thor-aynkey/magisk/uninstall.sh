#!/system/bin/sh
# Stop the daemon and remove its runtime state.
#
# Nothing hardware is restored because nothing hardware was changed: aynkeyd
# only reads an evdev node. The action script is left in place deliberately -
# it is installed by whatever configures the button, not by this module, and
# removing someone else's file on uninstall would be surprising.
/system/bin/aynkeyd stop 2>/dev/null

# Belt and braces: if the pidfile was stale, kill by argv0 rather than by a
# pattern that this script's own command line would match.
for p in $(ps -eo pid,args 2>/dev/null | awk '$2 ~ /aynkeyd$/ {print $1}'); do
  kill "$p" 2>/dev/null
done

rm -rf /data/adb/thor-aynkey/run /data/adb/thor-aynkey/aynkeyd.pid
exit 0
