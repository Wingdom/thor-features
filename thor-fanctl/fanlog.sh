#!/usr/bin/env bash
# Log fan + thermal behaviour over adb. Read-only; changes nothing.
#
# Purpose: find out whether usb-therm actually crosses its 43 C stock trip
# during real charging, and how high it gets -- the one number behind the
# choice of 60 C for quiet mode.
#
# Node indices are RESOLVED BY TYPE at startup, never hardcoded.
#
#   cooling_device* numbering is assigned in driver probe-registration order
#   and is NOT stable across boots. Observed on this device: the pwm-fan was
#   cooling_device34 on 2026-10-20 and cooling_device35 on 2026-10-21, same
#   build (24.0-20261019-NIGHTLY), after a reboot -- cooling_device34 had
#   become thermal-cluster-1-2. This script previously hardcoded 34, so it
#   would have silently logged a CPU cluster's throttle state in the
#   fan_state column, with no error.
#
#   thermal_zone* numbering comes from the device tree and has been stable
#   (usb-therm 53, cpu-1-9 14, gpuss-0 33, verified 2026-10-21), but it is
#   resolved by type here too, for the same cost and no guesswork.
#
# Discovery runs ONCE, not per sample: re-discovering per item is what made an
# earlier fanctl selftest take 14 minutes and spin the fan up by itself,
# polluting the very thing it measured.
#
# Usage: ./fanlog.sh [interval_seconds] [outfile]
set -uo pipefail
INT=${1:-5}
OUT=${2:-fanlog-$(date +%Y%m%d_%H%M%S).csv}

# ---- resolve nodes by type, once -------------------------------------------
echo "resolving thermal nodes by type..."
disc=$(adb shell "su -c '
    for d in /sys/class/thermal/cooling_device*; do
        if [ \"\$(cat \$d/type 2>/dev/null)\" = pwm-fan ]; then
            echo FAN=\$d
            break
        fi
    done
    for d in /sys/class/thermal/thermal_zone*; do
        case \"\$(cat \$d/type 2>/dev/null)\" in
            usb-therm) echo USB=\$d ;;
            cpu-1-9)   echo CPU=\$d ;;
            gpuss-0)   echo GPU=\$d ;;
        esac
    done
'" </dev/null 2>/dev/null | tr -d '\r')

FAN=$(printf '%s\n' "$disc" | sed -n 's/^FAN=//p' | head -1)
USB=$(printf '%s\n' "$disc" | sed -n 's/^USB=//p' | head -1)
CPU=$(printf '%s\n' "$disc" | sed -n 's/^CPU=//p' | head -1)
GPU=$(printf '%s\n' "$disc" | sed -n 's/^GPU=//p' | head -1)

miss=
[ -n "$FAN" ] || miss="$miss pwm-fan(cooling_device)"
[ -n "$USB" ] || miss="$miss usb-therm"
[ -n "$CPU" ] || miss="$miss cpu-1-9"
[ -n "$GPU" ] || miss="$miss gpuss-0"
if [ -n "$miss" ]; then
    echo "! could not resolve:$miss" >&2
    echo "! device unreachable, not rooted, or this is not the Thor. Refusing" >&2
    echo "! to log -- a wrong node silently produces a plausible-looking CSV." >&2
    exit 1
fi

printf '  fan   %s\n  usb   %s\n  cpu   %s\n  gpu   %s\n' \
    "$FAN" "$USB" "$CPU" "$GPU"

# tag the resolved indices into the header so a stored CSV is self-describing
fan_n=${FAN##*/}; usb_n=${USB##*/}; cpu_n=${CPU##*/}; gpu_n=${GPU##*/}

# ---- log --------------------------------------------------------------------
echo "uptime_s,batt_pct,batt_status,usb_therm_mC($usb_n),cpu19_mC($cpu_n),gpu0_mC($gpu_n),fan_state($fan_n),pwm1,mode,usb_trip2" > "$OUT"
echo "logging every ${INT}s to $OUT  (ctrl-c to stop)"
printf '  %-8s %-6s %-5s %-9s %-8s %-8s %-6s %-5s %s\n' UPTIME BATT STAT USB CPU GPU STATE PWM MODE

while true; do
    row=$(adb shell "su -c '
        b=\$(dumpsys battery)
        printf \"%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\" \
          \"\$(cut -d. -f1 /proc/uptime)\" \
          \"\$(printf %s \"\$b\" | sed -n \"s/.*level: //p\" | head -1)\" \
          \"\$(printf %s \"\$b\" | sed -n \"s/.*  status: //p\" | head -1)\" \
          \"\$(cat $USB/temp)\" \
          \"\$(cat $CPU/temp)\" \
          \"\$(cat $GPU/temp)\" \
          \"\$(cat $FAN/cur_state)\" \
          \"\$(cat /sys/class/hwmon/hwmon0/pwm1)\" \
          \"\$(fanctl get 2>/dev/null || echo ?)\" \
          \"\$(cat $USB/trip_point_2_temp)\"
    '" </dev/null 2>/dev/null | tr -d '\r')
    [ -z "$row" ] && { echo "  (device unreachable, retrying)"; sleep "$INT"; continue; }
    echo "$row" >> "$OUT"
    IFS=, read -r up batt chg usb cpu gpu st pwm mode trip <<< "$row"
    printf '  %-8s %-6s %-5s %-9s %-8s %-8s %-6s %-5s %s\n' \
        "$up" "$batt%" "$chg" "$usb" "$cpu" "$gpu" "$st" "$pwm" "$mode"
    sleep "$INT"
done
