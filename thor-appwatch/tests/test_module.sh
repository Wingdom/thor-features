#!/usr/bin/env bash
# Tests for the Magisk module's packaging, install gate and revert path.
# No device, no root. Run: ./tests/test_module.sh
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
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

echo "appwatch module tests"

# --- packaging ---------------------------------------------------------------
check "shipped binary matches source" "same" \
      "$(cmp -s appwatch magisk/system/bin/appwatch && echo same || echo different)"
check "shipped binary is executable" "yes" \
      "$([ -x magisk/system/bin/appwatch ] && echo yes || echo no)"
for f in magisk/module.prop magisk/customize.sh magisk/uninstall.sh magisk/service.sh; do
    check "$f exists" "yes" "$([ -f "$f" ] && echo yes || echo no)"
done
for f in appwatch magisk/customize.sh magisk/uninstall.sh magisk/service.sh; do
    check "$f is valid shell" "ok" "$(sh -n "$f" 2>/dev/null && echo ok || echo broken)"
done
for k in id name version versionCode author description; do
    check "module.prop has $k" "yes" \
          "$(grep -q "^$k=" magisk/module.prop && echo yes || echo no)"
done

# --- no command substitution in install messages -----------------------------
# REGRESSION GUARD. The first draft of customize.sh contained
#   ui_print "... (the Fan tile, or `fanctl quiet`)."
# and backticks inside double quotes are command substitution, so installing
# the module would have APPLIED a fan tier as a side effect of printing a
# help string. Assert no backticks survive outside comments.
check "no backticks outside comments in install scripts" "" \
      "$(sed 's/#.*//' magisk/customize.sh magisk/service.sh magisk/uninstall.sh \
         | grep -o '`' | tr '\n' ' ')"

# --- the self-kill trap ------------------------------------------------------
# A `pkill -f` pattern appears in the killer's own command line and matches
# itself. This has already cost this project three round trips elsewhere.
check "uninstall.sh uses no pkill/pgrep -f" "" \
      "$(sed 's/#.*//' magisk/uninstall.sh \
         | grep -oE '\b(pkill|pgrep)\b[^|]*' | tr '\n' ' ')"
check "appwatch uses no pkill/pgrep -f" "" \
      "$(sed 's/#.*//' appwatch | grep -oE '\b(pkill|pgrep)\b[^|]*' | tr '\n' ' ')"

# --- this module touches no hardware -----------------------------------------
# Everything it changes, it changes by asking fanctl. Strip comments first:
# the file discusses thermal nodes in prose, and matching prose is how an
# assertion like this produces a false positive on correct code.
check "appwatch writes nothing under /sys" "" \
      "$(sed -e 's/#.*//' -e "s/'[^']*'//g" -e 's/"[^"]*"//g' appwatch \
         | grep -oE '>[[:space:]]*/sys[^[:space:];]*' | tr '\n' ' ')"
check "appwatch references no thermal_zone path" "" \
      "$(sed 's/#.*//' appwatch | grep -o 'thermal_zone[0-9]*' | tr '\n' ' ')"
check "appwatch references no cooling_device index" "" \
      "$(sed 's/#.*//' appwatch | grep -oE 'cooling_device[0-9]+' | tr '\n' ' ')"

# --- `fanctl default` must never be reachable --------------------------------
# It restores stock originals and undoes the usb-therm fix fanctl exists for.
# Comments are stripped because the source documents the rule in prose.
check "appwatch never calls fanctl default" "" \
      "$(sed -e 's/#.*//' -e "s/'[^']*'//g" appwatch \
         | grep -oE '\$FANCTL"?[[:space:]]+default|fanctl[[:space:]]+default' | tr '\n' ' ')"
check "uninstall.sh never calls fanctl default" "" \
      "$(sed 's/#.*//' magisk/uninstall.sh \
         | grep -oE '\$FANCTL"?[[:space:]]+default|fanctl[[:space:]]+default' | tr '\n' ' ')"

# --- state stays inside our own directory ------------------------------------
check "appwatch writes only under its own state dir" "" \
      "$(sed -e 's/#.*//' appwatch \
         | grep -oE '>[[:space:]]*"?/data/adb/[^"[:space:]]*' \
         | grep -v 'thor-appwatch' | tr '\n' ' ')"

# --- service.sh waits rather than assuming -----------------------------------
check "service.sh waits for boot_completed" "yes" \
      "$(grep -q 'sys.boot_completed' magisk/service.sh && echo yes || echo no)"
# fanctl's own service.sh re-captures and re-applies at boot. Starting before
# that settles would record its transient state as our baseline.
check "service.sh waits for a real fanctl tier" "yes" \
      "$(grep -q 'quiet|balanced|performance' magisk/service.sh && echo yes || echo no)"
check "that wait is bounded" "yes" \
      "$(grep -qE '\[ "\$i" -lt [0-9]+ \]' magisk/service.sh && echo yes || echo no)"

# --- the daemon must resolve at startup, not only on events ------------------
# Structural, because the behaviour only exists on a device. Measured
# 2026-10-28: rebooting mid-override left the fan pinned at the game's tier
# because fanctl re-applies its persisted mode at boot and a purely
# event-driven daemon waits for an app switch to notice. The fix is one
# resolve_and_apply before the logcat pipe, so assert it is there AND that it
# precedes the pipeline rather than sitting inside the loop.
awk '/^daemon_loop\(\)/,/^}/' appwatch > /tmp/aw_loop.$$ 2>/dev/null
check "daemon_loop resolves before the logcat pipe" "yes" \
      "$(awk '/resolve_once/ && !seen_pipe {print "yes"; exit}
              /logcat -b events/ {seen_pipe=1}' /tmp/aw_loop.$$)"
check "daemon_loop still resolves inside the loop too" "yes" \
      "$(awk '/logcat -b events/ {seen_pipe=1}
              /resolve_once/ && seen_pipe {print "yes"; exit}' /tmp/aw_loop.$$)"
# The immediate pass must come BEFORE the bounded wait, or every boot pays the
# full timeout with the fan stuck on the previous override's tier.
check "the immediate pass precedes the bounded wait" "yes" \
      "$(awk '/resolve_once/ && !seen_wait {print "yes"; exit}
              /_n. -lt 20|_n" -lt 20/ {seen_wait=1}' /tmp/aw_loop.$$)"
check "the startup foreground wait is bounded" "yes" \
      "$(grep -qE '\[ "\$_n" -lt [0-9]+ \]' /tmp/aw_loop.$$ && echo yes || echo no)"
rm -f /tmp/aw_loop.$$

# --- customize.sh: the install gate, executed against stubs ------------------
stub_env() {
    local model=$1 mode=$2 fanctl_exec=$3
    rm -rf "$TMP/stub" "$TMP/state" "$TMP/modpath"
    mkdir -p "$TMP/stub" "$TMP/state" "$TMP/modpath/system/bin"
    : > "$TMP/modpath/system/bin/appwatch"
    : > "$TMP/modpath/service.sh"
    printf '#!/bin/sh\necho "%s"\n' "$model" > "$TMP/stub/getprop"
    chmod +x "$TMP/stub/getprop"
    if [ "$fanctl_exec" = yes ]; then
        printf '#!/bin/sh\n[ "$1" = get ] && echo "%s"\nexit 0\n' "$mode" > "$TMP/stub/fanctl"
        chmod +x "$TMP/stub/fanctl"
    fi
}
run_customize() {
    ( set +u
      cd "$TMP"
      export PATH="$TMP/stub:$PATH" STATE="$TMP/state" FANCTL="$TMP/stub/fanctl" MODPATH="$TMP/modpath"
      ui_print() { echo "$*"; }
      abort()    { echo "ABORT $*"; exit 1; }
      set_perm() { :; }; set_perm_recursive() { :; }
      . "$HERE/../magisk/customize.sh" ) 2>&1
}

stub_env Thor quiet yes
out=$(run_customize)
check "installs on a Thor with fanctl present" "0" "$?"
check "  reports the baseline" "yes" \
      "$(echo "$out" | grep -q 'baseline: quiet' && echo yes || echo no)"
check "  creates the enabled flag" "1" "$(cat "$TMP/state/enabled" 2>/dev/null)"

stub_env "Pixel 8" quiet yes
out=$(run_customize); rc=$?
check "aborts on non-Thor hardware" "1" "$rc"
check "  and says why" "yes" "$(echo "$out" | grep -q 'AYN Thor only' && echo yes || echo no)"

stub_env Thor quiet no
out=$(run_customize); rc=$?
check "aborts when fanctl is missing" "1" "$rc"
check "  and names the required module" "yes" \
      "$(echo "$out" | grep -q 'thor-fanctl is required' && echo yes || echo no)"
check "  and warns about Magisk staging" "yes" \
      "$(echo "$out" | grep -q 'NEXT boot' && echo yes || echo no)"

# A baseline of `default` is a warning, not an abort: a tier can be chosen later.
stub_env Thor default yes
out=$(run_customize); rc=$?
check "installs with a 'default' baseline" "0" "$rc"
check "  but warns it will not engage" "yes" \
      "$(echo "$out" | grep -q 'not engage' && echo yes || echo no)"

# --- uninstall.sh: the revert path, executed against stubs -------------------
# This is the important one. The applied tier is persisted by FANCTL and
# re-applied at every boot, so an uninstall that does not restore leaves the
# fan pinned with nothing installed to explain it.
run_uninstall() {
    ( set +u
      export STATE="$TMP/state" FANCTL="$TMP/stub/fanctl" APPWATCH="$TMP/stub/appwatch"
      sh "$HERE/../magisk/uninstall.sh" ) 2>&1
}
setup_state() {
    rm -rf "$TMP/stub" "$TMP/state"; mkdir -p "$TMP/stub" "$TMP/state"
    : > "$TMP/stub/applied"
    cat > "$TMP/stub/fanctl" <<'FEOF'
#!/bin/sh
D=$(dirname "$0")
if [ "$1" = get ]; then cat "$D/mode"; else echo "$1" > "$D/mode"; echo "$1" >> "$D/applied"; fi
FEOF
    chmod +x "$TMP/stub/fanctl"
    echo "$1" > "$TMP/stub/mode"        # what fanctl currently reports
    [ -n "${2:-}" ] && printf 'fan\t%s\n' "$2" > "$TMP/state/baseline"
}

# An override was active: fanctl is at performance, baseline recorded as quiet.
setup_state performance quiet
run_uninstall >/dev/null
check "uninstall restores the recorded baseline" "quiet" "$(cat "$TMP/stub/mode")"
check "  it does not call fanctl default" "" \
      "$(grep -x default "$TMP/stub/applied" 2>/dev/null | tr '\n' ' ')"
check "  and removes the state dir" "gone" \
      "$([ -d "$TMP/state" ] && echo present || echo gone)"

# No override was active: nothing recorded, so nothing to put back.
setup_state balanced ""
run_uninstall >/dev/null
check "uninstall with no baseline leaves fanctl alone" "balanced" "$(cat "$TMP/stub/mode")"
check "  and applied nothing at all" "" "$(cat "$TMP/stub/applied" | tr '\n' ' ')"

# The binary may already be gone by the time uninstall runs.
setup_state performance quiet
rm -f "$TMP/stub/appwatch"
run_uninstall >/dev/null
check "uninstall works without the appwatch binary" "quiet" "$(cat "$TMP/stub/mode")"

# A junk baseline must not be passed through to fanctl.
setup_state performance wibble
run_uninstall >/dev/null
check "a non-tier baseline is not applied" "performance" "$(cat "$TMP/stub/mode")"

# --- the BOOT path: fanctl is not mounted yet --------------------------------
# Magisk runs uninstall.sh before any module's /system is mounted, confirmed in
# its own log ("thor-appwatch: remove" precedes "thor-fanctl: loading module
# files"). So the direct fanctl call is NOT the path that runs on a real
# uninstall. Getting this wrong left the device pinned at the override's tier
# AND deleted the baseline record that would have explained it.
run_uninstall_noexec() {
    ( set +u
      export STATE="$TMP/state" FANCTL="$TMP/stub/absent-fanctl" \
             APPWATCH="$TMP/stub/absent-appwatch" FANCTL_MODE="$TMP/stub/fanctl_mode"
      sh "$HERE/../magisk/uninstall.sh" ) 2>&1
}
setup_state performance quiet
rm -f "$TMP/stub/fanctl"                 # fanctl binary not mounted yet
echo performance > "$TMP/stub/fanctl_mode"
run_uninstall_noexec >/dev/null
check "with fanctl unmounted, its persisted mode is set to the baseline" "quiet" \
      "$(cat "$TMP/stub/fanctl_mode" 2>/dev/null)"
check "  and the state dir is still removed" "gone" \
      "$([ -d "$TMP/state" ] && echo present || echo gone)"

# No baseline recorded means no override was active - nothing to put back.
setup_state performance ""
rm -f "$TMP/stub/fanctl"
echo performance > "$TMP/stub/fanctl_mode"
run_uninstall_noexec >/dev/null
check "no baseline leaves fanctl's persisted mode alone" "performance" \
      "$(cat "$TMP/stub/fanctl_mode" 2>/dev/null)"

# A junk baseline must not reach the mode file either.
setup_state performance wibble
rm -f "$TMP/stub/fanctl"
echo performance > "$TMP/stub/fanctl_mode"
run_uninstall_noexec >/dev/null
check "a non-tier baseline never reaches the mode file" "performance" \
      "$(cat "$TMP/stub/fanctl_mode" 2>/dev/null)"

# Neither available: must not crash, and must still clean up.
setup_state performance quiet
rm -f "$TMP/stub/fanctl" "$TMP/stub/fanctl_mode"
run_uninstall_noexec >/dev/null
check "with neither available it still exits cleanly" "0" "$?"
check "  and still removes the state dir" "gone" \
      "$([ -d "$TMP/state" ] && echo present || echo gone)"

echo
printf 'appwatch module: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
