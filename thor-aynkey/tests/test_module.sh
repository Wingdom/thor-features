#!/usr/bin/env bash
# Tests for the Magisk module's packaging and its boot/stop paths. No device.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); cd "$HERE/.."

pass=0; fail=0
check() {
    local label=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        pass=$((pass+1)); printf '  ok    %s\n' "$label"
    else
        fail=$((fail+1)); printf '  FAIL  %s\n       expected [%s] got [%s]\n' "$label" "$expected" "$actual"
    fi
}

echo "aynkey module tests"

check "shipped daemon matches source" "same" \
      "$(cmp -s aynkeyd magisk/system/bin/aynkeyd && echo same || echo different)"
check "shipped daemon is executable" "yes" \
      "$([ -x magisk/system/bin/aynkeyd ] && echo yes || echo no)"

for f in magisk/module.prop magisk/customize.sh magisk/uninstall.sh magisk/service.sh; do
    check "$f exists" "yes" "$([ -f "$f" ] && echo yes || echo no)"
done
for f in aynkeyd magisk/customize.sh magisk/uninstall.sh magisk/service.sh; do
    check "$f is valid shell" "ok" "$(sh -n "$f" 2>/dev/null && echo ok || echo broken)"
done

# --- the self-kill trap ------------------------------------------------------
# This has cost this project three round trips: a `pkill -f` / `pgrep -f`
# whose pattern appears in the killer's own command line matches itself. It
# happened AGAIN this session, inside an adb one-liner. uninstall.sh kills the
# daemon, so assert mechanically that it does not use a pattern-matching kill.
check "uninstall.sh uses no pkill/pgrep -f" "" \
      "$(grep -oE '\b(pkill|pgrep)\b[^|]*' magisk/uninstall.sh | tr '\n' ' ')"
check "aynkeyd uses no pkill/pgrep -f" "" \
      "$(grep -oE '\b(pkill|pgrep)\b[^|]*' aynkeyd | tr '\n' ' ')"

# --- the module is inert without an action -----------------------------------
# The daemon must not have a built-in action. Its entire effect is running
# /data/adb/thor-aynkey/action.sh, so installing it alone must change nothing
# the user can see. Assert no am/pm/settings/reboot call hides in the daemon.
# Strip comments and quoted strings first: "input" and "am" occur in log
# messages and prose, and matching those is how this assertion produced a
# false positive on correct code the first time it ran.
check "daemon invokes no action of its own" "" \
      "$(sed -e 's/#.*//' -e "s/'[^']*'//g" -e 's/"[^"]*"//g' aynkeyd \
         | grep -oE '(^|[;&|]|\$\()[[:space:]]*(am|pm|settings|reboot|svc|cmd)[[:space:]]' \
         | tr -d ' ' | tr '\n' ' ')"

# --- service.sh waits rather than assuming -----------------------------------
# Input devices enumerate asynchronously, so a service that starts the daemon
# immediately can start it before the key exists.
check "service.sh waits for boot_completed" "yes" \
      "$(grep -q 'sys.boot_completed' magisk/service.sh && echo yes || echo no)"
check "service.sh waits for the device to appear" "yes" \
      "$(grep -q 'aynkeyd device' magisk/service.sh && echo yes || echo no)"

# That wait must be bounded - an unbounded `until` on missing hardware is a
# boot-time spin that nothing ever reports.
check "the device wait is bounded" "yes" \
      "$(grep -qE '\-lt[[:space:]]+[0-9]+' magisk/service.sh && echo yes || echo no)"

# --- the threshold is one constant -------------------------------------------
# The module description and the usage text both quote it; neither may be a
# separately-maintained literal, or they will drift apart from the behaviour.
check "threshold appears once as an assignment" "1" \
      "$(grep -cE '^LONG_PRESS_MS=' aynkeyd)"

# --- no hardware writes ------------------------------------------------------
# This module reads an input node and nothing else.
check "daemon writes no sysfs node" "" \
      "$(grep -oE '> *"?/sys/[^ "]*' aynkeyd | sort -u | tr '\n' ' ')"

# --- the key layout ----------------------------------------------------------
# A vendor key layout REPLACES Generic.kl for this device rather than merging
# with it. gpio-keys carries KEY_VOLUMEUP (115) and KEY_F24 (194); dropping
# either would silently break that button, and volume up is the one a user
# would notice and not connect to this module.
KL=magisk/system/usr/keylayout/Vendor_0001_Product_0001.kl
check "key layout exists" "yes" "$([ -f "$KL" ] && echo yes || echo no)"
check "key layout keeps VOLUME_UP" "yes" \
      "$(grep -qE '^key 115 +VOLUME_UP' "$KL" && echo yes || echo no)"
check "key layout maps F24 with WAKE" "yes" \
      "$(grep -qE '^key 194 +F24 +WAKE' "$KL" && echo yes || echo no)"

# Exactly one behaviour changes: volume up must NOT silently gain WAKE too.
check "VOLUME_UP does not gain WAKE" "" \
      "$(grep -E '^key 115 .*WAKE' "$KL" | tr '\n' ' ')"

# Every key the device carries must be mapped. Both codes, no more, no fewer.
check "the layout maps exactly the device's two keys" "115 194" \
      "$(grep -oE '^key +[0-9]+' "$KL" | awk '{print $2}' | sort -n | tr '\n' ' ' | sed 's/ $//')"

# customize.sh must refuse a layout missing volume up, rather than install it.
check "customize.sh guards VOLUME_UP" "yes" \
      "$(grep -q 'key 115' magisk/customize.sh && echo yes || echo no)"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
