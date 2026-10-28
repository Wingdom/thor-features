#!/system/bin/sh
# Start the watcher once boot has completed.
#
# late_start service, not post-fs-data: the daemon needs /data mounted for its
# table and its state, and it reads the framework's event log, which does not
# exist before the framework does.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done

# Wait for fanctl rather than for a timer. fanctl's own service.sh re-captures
# originals and re-applies the persisted mode at boot; starting before that
# finishes would record ITS transient state as our baseline. Poll for the
# binary and for a mode that is actually a tier.
i=0
while [ "$i" -lt 30 ]; do
  if [ -x /system/bin/fanctl ]; then
    m=$(/system/bin/fanctl get 2>/dev/null | tr -d '\r\n')
    case "$m" in quiet|balanced|performance) break ;; esac
  fi
  i=$((i + 1))
  sleep 1
done

/system/bin/appwatch start
