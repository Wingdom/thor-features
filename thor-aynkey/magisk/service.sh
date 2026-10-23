#!/system/bin/sh
# Start the key daemon once boot has completed.
#
# late_start service, not post-fs-data: the daemon needs /data mounted for its
# action script and its run directory, and there is no reason to run before
# the user can press anything.
#
# The wait is for the input device, not for a timer. Input devices enumerate
# asynchronously and gpio-keys is not guaranteed present the moment this runs;
# aynkeyd itself retries, but starting it after the device exists keeps the
# log readable and makes a genuine absence visible rather than silent.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done

i=0
while [ "$i" -lt 30 ]; do
  if [ -n "$(/system/bin/aynkeyd device)" ]; then
    break
  fi
  i=$((i + 1))
  sleep 1
done

/system/bin/aynkeyd start
