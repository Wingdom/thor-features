#!/usr/bin/env bash
# Host tests for the AYN button action script. No device.
#
# This script is small but it is the one piece that runs on every button
# press, as root, and it is the way back from a dark bottom screen. A syntax
# error here bricks the button.
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

echo "action script tests"

check "action.sh is valid shell" "ok" \
      "$(sh -n action.sh 2>/dev/null && echo ok || echo broken)"
check "install.sh is valid shell" "ok" \
      "$(bash -n install.sh 2>/dev/null && echo ok || echo broken)"

# The panel is bottom-screen only, by the user's decision on 2026-10-22: a
# short press with the bottom screen off must turn that screen on and show the
# panel there, never fall back to the top screen. Assert there is no path that
# targets display 0.
check "no path targets the top display" "" \
      "$(grep -oE -- '--display +0' action.sh | tr '\n' ' ')"

# The short press must unblank before it launches, or it would open the panel
# onto a powered-down panel.
check "short press unblanks the bottom screen first" "yes" \
      "$(awk '/^ *short\)/,/;;/' action.sh | grep -q 'screenblank on bottom' && echo yes || echo no)"
check "the unblank precedes the am start" "yes" \
      "$(awk '/^ *short\)/,/;;/' action.sh \
         | awk '/screenblank on bottom/{u=NR} /am start/{a=NR} END{print (u && a && u < a) ? "yes" : "no"}')"

# --- haptic confirmation ------------------------------------------------------
# The long press fires mid-hold, so without a cue the user is holding a button
# with no idea whether the threshold passed.
check "long press gives haptic feedback" "yes" \
      "$(awk '/^ *long\)/,/;;/' action.sh | grep -q 'vibrator_manager' && echo yes || echo no)"

# `predefined` effects are a no-op on this device's vibrator HAL - they exit 0
# and do nothing, confirmed by feel. Only `oneshot` actually fires, so a
# regression to predefined would silently remove the feedback.
check "the haptic uses oneshot, not predefined" "" \
      "$(grep -oE 'vibrator_manager[^&|]*predefined[^&|]*' action.sh | tr '\n' ' ')"

# The haptic must never delay or block the toggle - it is backgrounded.
check "the haptic does not block the toggle" "yes" \
      "$(grep -qE 'vibrator_manager.*&[[:space:]]*$' action.sh && echo yes || echo no)"

# The short press gets no buzz: the panel appearing is its own feedback.
check "short press has no haptic" "" \
      "$(awk '/^ *short\)/,/;;/' action.sh | grep -oE 'vibrator_manager' | tr '\n' ' ')"

# The long press is the way back from a dark screen, so it must not depend on
# the panel, the app, or anything that has to draw. Stated as what it may not
# reference, rather than as a whitelist of binaries - the haptic is a binary
# too, and a whitelist would have had to be loosened to admit it, which is
# exactly how such a test stops meaning anything.
check "long press does not launch the panel" "" \
      "$(awk '/^ *long\)/,/;;/' action.sh | grep -oE 'am start|screenpanel|PanelActivity' | tr '\n' ' ')"
check "long press does not call screenmove" "" \
      "$(awk '/^ *long\)/,/;;/' action.sh | grep -oE 'screenmove' | tr '\n' ' ')"

# --- the long press must always be a way back --------------------------------
# From "Only bottom screen" the TOP panel is dark. `screenblank toggle bottom`
# would then be refused (never both dark), so the button would strand the user
# in the one mode they most need an escape from. Run the real awk predicate
# from action.sh against recorded screenblank output.
topdark() {
    printf '%s' "$1" | awk '$1 == "top" && $4 == "OFF" { f = 1 } END { exit !f }' \
        && echo restore || echo "toggle bottom"
}
BOTH_ON='top     screen : on    touch enabled   backlight /sys/class/backlight/panel0-backlight
bottom  screen : on    touch enabled   backlight /sys/class/backlight/panel1-backlight'
BOTTOM_OFF='top     screen : on    touch enabled   backlight /sys/class/backlight/panel0-backlight
bottom  screen : OFF   touch disabled  backlight /sys/class/backlight/panel1-backlight'
TOP_OFF='top     screen : OFF   touch disabled  backlight /sys/class/backlight/panel0-backlight
bottom  screen : on    touch enabled   backlight /sys/class/backlight/panel1-backlight'

check "both on: long press toggles the bottom screen" "toggle bottom" "$(topdark "$BOTH_ON")"
check "bottom off: long press toggles it back on"     "toggle bottom" "$(topdark "$BOTTOM_OFF")"
# The one that matters: do not toggle the bottom screen while the top is dark.
check "top off: long press restores instead"          "restore"       "$(topdark "$TOP_OFF")"

# The word OFF appears in the "touch disabled" column too on some lines; the
# predicate must key on the screen column, not on OFF appearing anywhere.
check "the touch column does not trigger a false restore" "toggle bottom" \
      "$(topdark 'top     screen : on    touch enabled   backlight /sys/OFF/panel0')"

# The bottom display must be resolved, not hardcoded - display ids are
# assigned at boot and this device renumbers across reboots.
check "the bottom display id is not hardcoded" "" \
      "$(grep -oE -- '--display +[1-9]' action.sh | tr '\n' ' ')"

# Absolute paths only: aynkeyd runs this with whatever PATH it inherited from
# init, which is not the interactive one. Checked over every command the
# script invokes, not just a named few - the bare `cmd` for the haptic slipped
# past the earlier version of this test because it only listed three names.
check "every command is called by absolute path" "" \
      "$(grep -oE '(^|[;&|(]|then |else |do )[[:space:]]*(screenblank|screenmove|aynkeyd|am|cmd|dumpsys|input|log)[[:space:]]' action.sh \
         | tr -d ' ' | tr '\n' ' ')"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
