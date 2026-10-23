#!/usr/bin/env bash
# Structural tests for the Magisk module. Host-side, no device.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
M="$HERE/../magisk"
pass=0; fail=0
ok()  { pass=$((pass+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s\n     %s\n' "$1" "${2:-}"; }

echo "magisk module structure"

for f in module.prop customize.sh service.sh uninstall.sh system/bin/fanctl; do
  [ -f "$M/$f" ] && ok "present: $f" || bad "present: $f" "missing"
done

for k in id name version versionCode author description; do
  grep -q "^$k=" "$M/module.prop" && ok "module.prop has $k" || bad "module.prop has $k"
done

[ "$(awk -F= '/^id=/{print $2}' "$M/module.prop")" = thor-fanctl ] \
  && ok "module id is thor-fanctl" || bad "module id is thor-fanctl"

for f in customize.sh service.sh uninstall.sh system/bin/fanctl; do
  sh -n "$M/$f" 2>/dev/null && ok "shell syntax: $f" || bad "shell syntax: $f"
done

# the shipped fanctl must be identical to the source of truth
if cmp -s "$HERE/../fanctl" "$M/system/bin/fanctl"; then
  ok "shipped fanctl matches source"
else
  bad "shipped fanctl matches source" "run ./build.sh to refresh"
fi

# SAFETY: customize.sh must refuse non-Thor hardware and missing fan
grep -q 'abort' "$M/customize.sh" && ok "customize.sh can abort" || bad "customize.sh can abort"
grep -q 'ro.product.model' "$M/customize.sh" && ok "customize.sh checks the model" || bad "customize.sh checks the model"
grep -q 'pwm-fan' "$M/customize.sh" && ok "customize.sh checks for the pwm-fan device" || bad "customize.sh checks for pwm-fan"

# SAFETY: uninstall must restore and clean up
grep -qE '(\$FANCTL|fanctl)"? +default' "$M/uninstall.sh" && ok "uninstall.sh restores stock trips" || bad "uninstall.sh restores stock trips"
grep -q 'rm -rf /data/adb/thor-fanctl' "$M/uninstall.sh" && ok "uninstall.sh removes state" || bad "uninstall.sh removes state"

# SAFETY: service.sh must force-capture before applying, or originals can drift
if grep -q 'capture --force' "$M/service.sh"; then ok "service.sh force-captures originals at boot"
else bad "service.sh force-captures originals at boot"; fi
if awk '/capture --force/{c=NR} /fanctl" "\$MODE"|FANCTL" "\$MODE/{a=NR} END{exit (c&&a&&c<a)?0:1}' "$M/service.sh"; then
  ok "service.sh captures BEFORE applying"
else bad "service.sh captures BEFORE applying"; fi

# the module must never write pwm1 or cur_state directly
if grep -rnE '>\s*/sys/class/hwmon/hwmon0/pwm1|>\s*.*cooling_device.*cur_state' "$M" >/dev/null 2>&1; then
  bad "module never writes pwm1 or cur_state" "$(grep -rnE '>\s*/sys/class/hwmon/hwmon0/pwm1|cur_state' "$M" | head -2)"
else
  ok "module never writes pwm1 or cur_state directly"
fi

# nor switch a governor
# strip comments first: the invariant is documented in prose, which must not
# count as a violation. Only an actual write of user_space matters.
if find "$M" -type f -exec sed 's/#.*//' {} + | grep -qE 'user_space'; then
  bad "module never sets the user_space governor" "$(find "$M" -type f -exec sed 's/#.*//' {} + | grep -n user_space | head -2)"
else ok "module never sets the user_space governor"; fi

# REGRESSION GUARD: selftest once called discover() twice per trip per mode
# (~192 full scans, >14 minutes, and the churn spun the fan up by itself).
# Discovery must be invoked at most once inside selftest.
nd=$(awk '/^selftest\(\)/{f=1} f&&/^}$/{exit} f' "$HERE/../fanctl" \
     | sed 's/#.*//' | grep -cE '(^|[^a-z_])discover($|[^a-z_])')
[ "${nd:-9}" -le 1 ] && ok "selftest calls discover at most once (got $nd)" \
                     || bad "selftest calls discover at most once" "got $nd - it must snapshot, not rescan"

# and discovery must never appear as a command substitution (which is how the
# per-line rescan crept in originally)
if awk '/^selftest\(\)/{f=1} f&&/^}$/{exit} f' "$HERE/../fanctl" \
   | sed 's/#.*//' | grep -q '\$(discover'; then
  bad "selftest never uses \$(discover ...) substitution"
else
  ok "selftest never uses \$(discover ...) substitution"
fi

# REGRESSION GUARD: applying a mode must nudge the affected zones afterwards.
# These zones do not poll (polling-delay = 0), so a trip change alone can leave
# a previously latched fan state in place -- seen live with the fan stuck at
# state 6 while every zone sat at ~40 C.
grep -q '^settle()' "$HERE/../fanctl" && ok "fanctl defines settle()" || bad "fanctl defines settle()"
if awk '/^apply\(\)/{f=1} f&&/^}$/{exit} f' "$HERE/../fanctl" | grep -q '^ *settle$'; then
  ok "apply() calls settle() after writing trips"
else
  bad "apply() calls settle() after writing trips"
fi
if awk '/^apply\(\)/{f=1} f&&/^}$/{exit} f' "$HERE/../fanctl" \
   | awk '/\[ "\$dry" = 1 \] && return 0/{d=NR} /^ *settle$/{s=NR} END{exit (d&&s&&d<s)?0:1}'; then
  ok "settle() is skipped on --dry-run"
else
  bad "settle() is skipped on --dry-run"
fi

# REGRESSION GUARD: no script may hardcode a thermal node index.
#
# cooling_device* numbering is assigned in driver probe-registration order and
# is NOT stable across boots. Caught live: the pwm-fan was cooling_device34 on
# 2026-10-20 and cooling_device35 on 2026-10-21 -- same build, after a reboot --
# and cooling_device34 had become thermal-cluster-1-2 (max_state 20, a CPU
# cluster throttle step). fanlog.sh hardcoded 34, so it reported fan_state=0
# while the fan was actually at state 3: a plausible-looking CSV that would have
# inverted the conclusion of the usb-therm measurement it exists to make.
#
# thermal_zone* indices come from the device tree and have been stable, but they
# are resolved by type too -- same cost, no guesswork, and no second class of
# silently-wrong node.
#
# Comments are stripped first: the history above documents the bad indices in
# prose, which must not count as a violation. Only real code matters.
#
# Scope is the scripts that touch a live device: fanctl, fanlog.sh, build.sh and
# everything under magisk/. Host-side unit tests under tests/ are deliberately
# excluded -- test_plan.sh feeds fixed node paths as FIXTURE strings into pure
# planning functions that never open them, so a literal index there is data, not
# a node lookup. dist/ is build output.
P="$(cd "$HERE/.." && pwd)"   # normalise: "$HERE/.." contains /tests/ and
                               # would be eaten by the exclusion below
scripts=$(find "$P" -type f \( -name '*.sh' -o -name fanctl \) \
            -not -path '*/dist/*' -not -path '*/tests/*')
hits=$(for f in $scripts; do sed 's/#.*//' "$f" \
        | grep -onE '(cooling_device|thermal_zone)[0-9]+' \
        | sed "s|^|$(basename "$f"):|"; done)
if [ -n "$hits" ]; then
  bad "no script hardcodes a thermal node index" "$(printf '%s' "$hits" | head -3) - resolve by type instead"
else
  ok "no script hardcodes a thermal node index"
fi

# and fanlog.sh must refuse to log rather than guess, if resolution fails
if sed 's/#.*//' "$HERE/../fanlog.sh" | grep -qE 'exit 1'; then
  ok "fanlog.sh aborts when a node cannot be resolved"
else
  bad "fanlog.sh aborts when a node cannot be resolved" "it must not log with a missing node"
fi
if sed 's/#.*//' "$HERE/../fanlog.sh" | grep -q 'pwm-fan'; then
  ok "fanlog.sh resolves the fan by type"
else
  bad "fanlog.sh resolves the fan by type"
fi

echo
printf '  %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
