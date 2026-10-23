#!/system/bin/sh
# Runs late_start. Applies nothing -- the overlay is already mounted by Magisk
# before this point. This exists to verify and to warn, per the project's rule
# that a write is not trusted until its downstream effect is observed.

STATE=${THOR_DM_STATE:-/data/adb/thor-display-match}
LOG=$STATE/boot.log
DM=/system/bin/display-match

i=0
while [ "$i" -lt 90 ]; do
    [ "$(getprop sys.boot_completed)" = 1 ] && break
    sleep 1
    i=$((i+1))
done
sleep 5

mkdir -p "$STATE"
{
    echo "--- boot $(date 2>/dev/null) (waited ${i}s for boot_completed) ---"

    # Did the overlay actually take?
    if grep -q "ignoreOrientationRequest" /vendor/etc/display_settings.xml 2>/dev/null; then
        echo "overlay: present in /vendor/etc/display_settings.xml"
    else
        echo "overlay: MISSING -- Magisk mount did not take"
    fi

    # Did the framework act on it? This is the effect that matters.
    echo "effective: $(wm get-ignore-orientation-request -d 0 2>&1)"

    if [ -f /data/system/display_settings.xml ]; then
        echo "note: /data/system/display_settings.xml exists and overrides the vendor file"
    fi

    # ROM update drift: the overlay was generated from the ROM's file at install
    # time. A newer ROM may ship a different one, which we would be masking.
    if [ -f "$STATE/install.info" ]; then
        . "$STATE/install.info" 2>/dev/null
        NOW=$(getprop ro.build.date.utc)
        if [ -n "${rom_build:-}" ] && [ "$rom_build" != "$NOW" ]; then
            echo "warn: ROM changed since install (built against $rom_build, now $NOW)."
            echo "      Reinstall the module so the overlay is regenerated from the new file."
        fi
    fi
} >> "$LOG" 2>&1
