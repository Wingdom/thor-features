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

echo "oledwatch module tests"

SHIPPED=(magisk/customize.sh magisk/service.sh magisk/uninstall.sh)

# --- packaging ---------------------------------------------------------------
echo
echo " packaging"
check "shipped binary matches source" "same" \
      "$(cmp -s oledwatch magisk/system/bin/oledwatch && echo same || echo different)"
check "shipped binary is executable" "yes" \
      "$([ -x magisk/system/bin/oledwatch ] && echo yes || echo no)"
for f in magisk/module.prop "${SHIPPED[@]}"; do
    check "$f exists" "yes" "$([ -f "$f" ] && echo yes || echo no)"
done
for k in id name version versionCode author description; do
    check "module.prop has $k" "yes" \
          "$(grep -q "^$k=" magisk/module.prop && echo yes || echo no)"
done
check "module id matches the directory" "thor-oledwatch" \
      "$(awk -F= '/^id=/{print $2}' magisk/module.prop)"

# versionCode must track version, or Magisk will not offer an update.
# v0.1.0 -> 100, by the same derivation the apps use.
VER=$(awk -F= '/^version=/{print $2}' magisk/module.prop | tr -d 'v')
VC=$(awk -F= '/^versionCode=/{print $2}' magisk/module.prop)
check "versionCode is derived from version" \
      "$(echo "$VER" | awk -F. '{printf "%d", $1*10000 + $2*100 + $3}')" "$VC"

# --- every shipped script must survive dash AND ash --------------------------
# /bin/sh here is bash, on the CI runners it is dash, and Magisk sources
# customize.sh under busybox ash. A bashism passes locally and fails in both of
# the places that matter.
echo
echo " shell portability"
for f in oledwatch "${SHIPPED[@]}"; do
    check "$f parses as POSIX sh" "ok" "$(sh -n "$f" 2>/dev/null && echo ok || echo broken)"
    if command -v dash >/dev/null 2>&1; then
        check "$f parses as dash" "ok" "$(dash -n "$f" 2>/dev/null && echo ok || echo broken)"
    fi
done
check "no bash arrays in shipped scripts" "" \
      "$(grep -lE '^\s*[A-Za-z_]+=\(' oledwatch "${SHIPPED[@]}" 2>/dev/null | tr '\n' ' ')"
check "no [[ in shipped scripts" "" \
      "$(sed 's/#.*//' oledwatch "${SHIPPED[@]}" | grep -o '\[\[' | tr '\n' ' ')"

# --- command substitution in install messages --------------------------------
# A sibling module shipped `ui_print "... (try \`fanctl quiet\`)"` once, and
# backticks inside double quotes are command substitution, so installing the
# module would have APPLIED a fan tier as a side effect of printing help.
echo
echo " regression guards"
check "no backticks outside comments in install scripts" "" \
      "$(sed 's/#.*//' "${SHIPPED[@]}" | grep -o '`' | tr '\n' ' ')"

# A `pkill -f` pattern appears in the killer's own command line and matches
# itself. This has already cost this project three round trips elsewhere.
# The CLI used to pass a hardcoded elapsed time where the timer passes a real
# one, so `decide` could never report the cooldown. Exactly one occurrence of
# the sentinel is allowed: the "no record" return inside last_fire_ms.
check "the cooldown sentinel appears once, as a return value" "1" \
      "$(sed 's/#.*//' oledwatch | grep -c -- '-\?999999999')"
check "decide is not called with a literal elapsed time" "" \
      "$(sed 's/#.*//' oledwatch | grep -oE 'decide .*[0-9]{6,}' | tr '\n' ' ')"

# `oledwatch run` once fired a wash without recording it, because record_fire
# was called at one of the two call sites rather than inside start_wash. The
# result was a `status` line reporting a wash from minutes earlier and a manual
# wash that did not count towards the cooldown. Assert the recording lives
# inside start_wash, where no caller can forget it.
check "start_wash records the fire itself" "yes" \
      "$(awk '/^start_wash\(\) \{/,/^\}/' oledwatch | grep -q 'record_fire' && echo yes || echo no)"
check "record_fire is not called from anywhere else" "1" \
      "$(sed 's/#.*//' oledwatch | grep -c '^[^#]*[^_]record_fire$')"

check "no pkill/pgrep -f anywhere" "" \
      "$(sed 's/#.*//' oledwatch "${SHIPPED[@]}" \
         | grep -oE '\b(pkill|pgrep)\b[^|]*' | tr '\n' ' ')"

# --- this module touches no hardware and no power state ----------------------
# Strip comments and string literals first: these files discuss brightness and
# wakelocks in prose, and matching prose is how an assertion like this produces
# a false positive on correct code.
echo
echo " does nothing it should not"
strip() { sed -e 's/#.*//' -e "s/'[^']*'//g" -e 's/"[^"]*"//g' "$@"; }

check "writes nothing under /sys" "" \
      "$(strip oledwatch "${SHIPPED[@]}" | grep -oE '>\s*/sys[^ ]*' | tr '\n' ' ')"
check "never sets brightness" "" \
      "$(strip oledwatch "${SHIPPED[@]}" | grep -oE 'set-brightness|screen_brightness' | tr '\n' ' ')"
# Matching WAKE_LOCK here was the first version of this check and it failed on
# correct code: the daemon READS wake locks out of `dumpsys power` to notice
# that some other app is holding the screen up. Detecting one is the feature.
# What must never appear is anything that CHANGES power state.
check "never wakes the device or forces the screen on" "" \
      "$(strip oledwatch "${SHIPPED[@]}" \
         | grep -oE 'svc power|input keyevent|KEYCODE_WAKEUP|wakeup|goToSleep|acquire' \
         | tr '\n' ' ')"
check "the daemon only ever reads wake locks, never asks for one" "" \
      "$(strip oledwatch | grep -oE 'newWakeLock|PowerManager|acquire\(' | tr '\n' ' ')"
check "never changes a framework setting" "" \
      "$(strip oledwatch "${SHIPPED[@]}" | grep -oE 'settings put' | tr '\n' ' ')"

# --- the dependency points one way -------------------------------------------
# This module needs the app. The app must NOT need this module, because the
# manual wash has to keep working with nothing installed and no root.
echo
echo " dependency direction"
check "the daemon names the app" "yes" \
      "$(grep -q 'com\.thorfeatures\.oledwash' oledwatch && echo yes || echo no)"
check "the installer refuses without the app" "yes" \
      "$(grep -q 'abort' magisk/customize.sh && echo yes || echo no)"
if [ -d ../thor-oledwash/src ]; then
    # Strip comments first. Three of the app's files DISCUSS this module in
    # their javadoc, and that prose is worth having -- it is where the reasoning
    # about the split lives. What matters is that no code depends on it. A
    # sibling's tests once failed on correct code for exactly this reason, by
    # matching prose instead of intent.
    strip_java() {
        sed -e 's|//.*||' -e 's|^[[:space:]]*\*.*||' -e 's|^[[:space:]]*/\*.*||' "$@"
    }
    named=""
    for f in ../thor-oledwash/src/com/thorfeatures/oledwash/*.java; do
        if strip_java "$f" | grep -q 'oledwatch'; then
            named="$named$(basename "$f") "
        fi
    done
    # Exactly ONE class may name this module in code, and its whole job is
    # answering "is it installed", so the app can say so instead of offering a
    # switch that silently does nothing. Naming the file here is the point: a
    # second one appearing is the thing worth noticing.
    check "only ModuleState names this module in code" "ModuleState.java " "$named"

    # And the wash path itself must be clean even of the prose, because those
    # are the classes that have to work with nothing flashed.
    check "the wash path never names it at all" "" \
          "$(grep -lE 'oledwatch' \
               ../thor-oledwash/src/com/thorfeatures/oledwash/WashLauncher.java \
               ../thor-oledwash/src/com/thorfeatures/oledwash/WashActivity.java \
               ../thor-oledwash/src/com/thorfeatures/oledwash/WashView.java \
               ../thor-oledwash/src/com/thorfeatures/oledwash/DisplayChoice.java \
               ../thor-oledwash/src/com/thorfeatures/oledwash/Displays.java \
               2>/dev/null | tr '\n' ' ')"
else
    echo "  skip  app sources not beside this component"
fi

# --- the kill switch ---------------------------------------------------------
# Test behaviour, not text: a sibling's tests once failed on correct code by
# grepping for a literal that the script spelled differently.
echo
echo " kill switch"
check "uninstall stops the daemon" "yes" \
      "$(strip magisk/uninstall.sh | grep -qE 'oledwatch.*stop|stop' && echo yes || echo no)"
# Comments stripped but NOT string literals: the path being removed lives
# inside the quotes, so the strip that protects the other checks would eat the
# very thing this one is looking for.
check "uninstall removes the state directory" "yes" \
      "$(sed 's/#.*//' magisk/uninstall.sh | grep -qE 'rm -rf +"?\$STATE' && echo yes || echo no)"
check "uninstall restores nothing, because nothing was changed" "" \
      "$(strip magisk/uninstall.sh | grep -oE 'set-brightness|settings put|>\s*/sys' | tr '\n' ' ')"

# --- boot ordering -----------------------------------------------------------
echo
echo " boot"
check "service.sh waits for boot_completed" "yes" \
      "$(grep -q 'sys.boot_completed' magisk/service.sh && echo yes || echo no)"
check "service.sh starts the daemon" "yes" \
      "$(grep -q 'oledwatch start' magisk/service.sh && echo yes || echo no)"
check "nothing is applied at boot" "" \
      "$(strip magisk/service.sh | grep -oE 'set-brightness|settings put|>\s*/sys' | tr '\n' ' ')"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ] || exit 1
