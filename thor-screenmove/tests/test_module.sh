#!/usr/bin/env bash
# Host-side checks on the packaged module. No device required.
#
# These test *intent*, not text. An earlier module in this project failed two
# tests on correct code because they grepped for literal strings that happened
# to be spelled differently (or appeared only inside a comment saying the
# opposite). So: strip comments before asserting on behaviour.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT="$HERE/.."
pass=0; fail=0

ok()   { pass=$((pass+1)); printf '  ok    %s\n' "$1"; }
bad()  { fail=$((fail+1)); printf '  FAIL  %s\n' "$1"; }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected [$2] got [$3])"; fi; }

# Code only: drop full-line and trailing comments, so a rule described in prose
# is never mistaken for a rule implemented in code.
code_of() { sed -e 's/[[:space:]]#.*$//' -e '/^[[:space:]]*#/d' "$1"; }

echo "screenmove module tests"

# --- the shipped copy must match source --------------------------------------
# build.sh syncs before testing; if this fails the package would ship stale code.
if diff -q "$ROOT/screenmove" "$ROOT/magisk/system/bin/screenmove" >/dev/null 2>&1; then
    ok "shipped screenmove matches source"
else
    bad "shipped screenmove differs from source (run build.sh)"
fi

# --- module.prop --------------------------------------------------------------
prop="$ROOT/magisk/module.prop"
check "module id"      "thor-screenmove" "$(sed -n 's/^id=//p' "$prop")"
[ -n "$(sed -n 's/^version=//p' "$prop")" ] && ok "has version" || bad "no version"
[ -n "$(sed -n 's/^versionCode=//p' "$prop")" ] && ok "has versionCode" || bad "no versionCode"

# --- the module must have no boot-time behaviour ------------------------------
# This is the module's core safety claim: nothing runs at boot, so `disable`
# plus a reboot is a complete revert. Assert the absence of the Magisk boot
# hooks rather than trusting the description.
for hook in service.sh post-fs-data.sh boot-completed.sh; do
    if [ -e "$ROOT/magisk/$hook" ]; then
        bad "module ships $hook - it must have no boot-time behaviour"
    else
        ok "no $hook (nothing runs at boot)"
    fi
done

# --- customize.sh must gate on the device and the capability ------------------
cust=$(code_of "$ROOT/magisk/customize.sh")
grep -q "ro.product.model" <<<"$cust" && ok "customize checks model" || bad "customize does not check model"
grep -q "abort"            <<<"$cust" && ok "customize can abort"    || bad "customize never aborts"
grep -q "move-stack"       <<<"$cust" && ok "customize checks for move-stack support" \
                                      || bad "customize does not check move-stack support"
grep -q "displayId"        <<<"$cust" && ok "customize checks for a second display" \
                                      || bad "customize does not check for a second display"

# --- the script must verify its own writes ------------------------------------
# An `am` call can report success for an operation the window manager declines,
# so the script re-reads state and compares. Assert that the move path reads
# back *after* calling am, not just that the words appear somewhere.
sm=$(code_of "$ROOT/screenmove")
am_line=$(grep -n "am display move-stack" <<<"$sm" | head -1 | cut -d: -f1)
after_line=$(grep -n "after=" <<<"$sm" | head -1 | cut -d: -f1)
if [ -n "$am_line" ] && [ -n "$after_line" ] && [ "$after_line" -gt "$am_line" ]; then
    ok "move verifies by re-reading after the am call"
else
    bad "move does not re-read state after the am call"
fi

# --- the script must not write hardware or persist state ----------------------
# The safety claim is "no node, no persistent state". Guard it: no writes into
# /sys, /proc, /data/adb state dirs, no setprop, no `settings put`.
for pat in '>[[:space:]]*/sys/' '>[[:space:]]*/proc/' 'setprop' 'settings put'; do
    if grep -qE "$pat" <<<"$sm"; then
        bad "script contains a persistent/hardware write matching: $pat"
    else
        ok "no writes matching: $pat"
    fi
done

# --- the script must resolve the second display, not hardcode it --------------
# Display ids are assigned at boot. A hardcoded 1 would silently move things to
# a display that may not be the bottom panel.
grep -q "bottom_display()" <<<"$sm" && ok "resolves the bottom display dynamically" \
                                    || bad "does not resolve the bottom display"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
