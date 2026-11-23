#!/system/bin/sh
# Start the watcher once boot has completed.
#
# late_start service, not post-fs-data: the daemon reads `dumpsys power` and
# starts an Activity, neither of which exists before the framework does.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done

# Wait for the package manager to have the app, not merely for boot. On a cold
# boot `pm path` can fail for a while after sys.boot_completed while the
# package manager is still settling, and a daemon that started before that
# would log "app-missing" and then be right about it for the rest of the boot
# only because it asked too early. The daemon re-checks on every fire, so this
# is politeness rather than correctness - but a clean log is worth 30 seconds.
i=0
while [ "$i" -lt 30 ]; do
  pm path com.thorfeatures.oledwash >/dev/null 2>&1 && break
  i=$((i + 1))
  sleep 1
done

# Nothing is applied at boot and nothing is restored at boot, because this
# module changes nothing that survives it. Contrast thor-fanctl, which must
# re-capture originals here because the device tree has just been parsed.
/system/bin/oledwatch start
