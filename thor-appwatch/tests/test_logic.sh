#!/usr/bin/env bash
# Host-side unit tests for appwatchd's pure logic.
# No device, no root, no network. Run: ./tests/test_logic.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPWATCH_LIB=1 . "$HERE/../appwatch"

pass=0; fail=0
ok()  { pass=$((pass+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { fail=$((fail+1)); printf '  \033[31mFAIL\033[0m %s\n     %s\n' "$1" "$2"; }
eq()  { [ "$2" = "$3" ] && ok "$1" || bad "$1" "expected '$3', got '$2'"; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

# --- fixtures -----------------------------------------------------------------
# REAL logcat output captured from the device 2026-10-23 on
# 24.0-20261019-NIGHTLY, in threadtime form, exactly as the daemon will see
# it. Hand-written approximations are deliberately not used: a change in the
# payload shape must break these tests.
COLD_FILES='10-23 18:24:34.017  1909  2475 I wm_set_resumed_activity: [0,com.android.documentsui/.files.FilesActivity,realStartActivityLocked - onActivityStateChanged]'
COLD_TRAMP='10-23 18:24:34.001  1909  2475 I wm_set_resumed_activity: [0,com.android.documentsui/.LauncherActivity,realStartActivityLocked - onActivityStateChanged]'
WARM_CALC='10-23 18:24:43.168  1909  2475 I wm_set_resumed_activity: [0,com.android.calculator2/.Calculator,resumeTopActivity - onActivityStateChanged]'
HOME_PRESS='10-23 18:25:44.759  1909  2475 I wm_set_resumed_activity: [0,rip.moth.cocoonshell/.MainActivity,resumeTopActivity - onActivityStateChanged]'
# Same shape on the bottom panel. Display 1 is the 3.92" screen.
BOTTOM_EMU='10-23 18:30:11.004  1909  2475 I wm_set_resumed_activity: [1,com.example.emu/.MainActivity,resumeTopActivity - onActivityStateChanged]'
UNRELATED='10-23 18:24:33.949  1909  2475 I wm_task_to_front: [0,303,0]'

echo "parse_resume_line - real captured events"
eq "cold launch, display and package"   "$(parse_resume_line "$COLD_FILES")"  "0 com.android.documentsui"
eq "warm resume (resumeTopActivity)"    "$(parse_resume_line "$WARM_CALC")"   "0 com.android.calculator2"
eq "home press resolves the launcher"   "$(parse_resume_line "$HOME_PRESS")"  "0 rip.moth.cocoonshell"
eq "bottom panel reports display 1"     "$(parse_resume_line "$BOTTOM_EMU")"  "1 com.example.emu"

parse_resume_line "$UNRELATED" >/dev/null 2>&1 \
  && bad "a non-resume line is rejected" "accepted wm_task_to_front" \
  || ok  "a non-resume line is rejected"

echo
echo "parse_resume_line - the documentsui trampoline"
# Measured: documentsui emitted two events 16 ms apart, LauncherActivity then
# FilesActivity. Keying on the component would apply a tier twice per switch;
# keying on the package collapses them.
eq "both trampoline events yield one package" \
   "$(printf '%s\n%s\n' "$COLD_TRAMP" "$COLD_FILES" \
      | while IFS= read -r l; do parse_resume_line "$l"; done \
      | awk '{print $2}' | sort -u | wc -l | tr -d ' ')" "1"

echo
echo "parse_resume_line - malformed input"
for bad_line in \
  '10-23 I wm_set_resumed_activity: [' \
  '10-23 I wm_set_resumed_activity: [x,com.foo/.Bar,reason]' \
  '10-23 I wm_set_resumed_activity: []' \
  ''
do
  parse_resume_line "$bad_line" >/dev/null 2>&1 \
    && bad "rejects malformed: ${bad_line:0:46}" "was accepted" \
    || ok  "rejects malformed: ${bad_line:0:46}"
done

echo
echo "parse_resume_line - hostile cwd (forward canary, NOT a regression guard)"
# The payload "[0,pkg/.Act,reason]" is a valid glob character class, and an
# unquoted expansion in a command or for-list context would pathname-expand
# it - the bug that cost thor-stickfx a session.
#
# BE HONEST ABOUT THIS TEST: it cannot currently fail. This parser only
# expands inside assignments and `case` patterns, and neither context performs
# pathname expansion, so unquoting every expansion here was verified to leave
# all tests passing. It is kept as a canary against a future refactor that
# introduces a `for` loop or an unquoted command substitution, not as evidence
# that quoting is load-bearing today.
( cd "$TMP" && touch a c d e f g h i j n o p r s t u v y 0 1 2 \
  && eq "parses with cwd full of single-char files" \
        "$(parse_resume_line "$WARM_CALC")" "0 com.android.calculator2" )

echo
echo "shell portability - the escaped bracket pattern"
# A strictness canary, and an honest account of what it does and does not
# prove. Unescaping the bracket in "${_p#*\[}" leaves every bash test passing
# and makes zsh fail with "bad pattern: *[", so this assertion does fail when
# broken - unlike the canary above.
#
# But it does NOT protect the device. Measured 2026-10-28: /system/bin/sh on
# the Thor is mksh R59, and it accepts the unescaped form exactly as bash
# does. The escape is kept because it is correct and free, not because this
# shell needs it. Real device coverage comes from running the pure logic under
# mksh itself; see NOTES.local.md.
portable=0
for alt in zsh dash mksh ash yash; do
  command -v "$alt" >/dev/null 2>&1 || continue
  got=$("$alt" -c 'APPWATCH_LIB=1 . ./appwatch; parse_resume_line "$1"' "$alt" \
        "$WARM_CALC" 2>&1)
  eq "pure logic parses under $alt" "$got" "0 com.android.calculator2"
  portable=$((portable+1))
done
[ "$portable" -gt 0 ] \
  || printf '  \033[33mSKIP\033[0m no strict shell on this host; bracket guard unexercised\n'

echo
echo "tier_rank - from fanctl's own offsets"
eq "quiet"        "$(tier_rank quiet)"       "1"
eq "balanced"     "$(tier_rank balanced)"    "2"
eq "performance"  "$(tier_rank performance)" "3"
eq "default is NOT a tier"   "$(tier_rank default)"  "0"
eq "unknown word is not a tier" "$(tier_rank wibble)" "0"
eq "empty is not a tier"     "$(tier_rank '')"       "0"

echo
echo "max_tier"
eq "performance beats balanced" "$(max_tier balanced performance)" "performance"
eq "order does not matter"      "$(max_tier performance balanced)" "performance"
eq "equal tiers are stable"     "$(max_tier quiet quiet)"          "quiet"
eq "empty second arg"           "$(max_tier balanced '')"          "balanced"
eq "empty first arg"            "$(max_tier '' balanced)"          "balanced"

echo
echo "table_value - long format, three columns"
TBL="$TMP/profiles"
cat > "$TBL" <<'TEOF'
com.example.game	fan	performance
com.example.game	rgb	breathe
com.example.emu	fan	balanced
com.example.weird	fan	wibble
com.example.other	screen	bottom
TEOF
eq "reads the fan key"          "$(table_value "$TBL" com.example.game fan)"  "performance"
eq "reads a different key"      "$(table_value "$TBL" com.example.game rgb)"  "breathe"
eq "a second package"           "$(table_value "$TBL" com.example.emu fan)"   "balanced"
eq "absent package is empty"    "$(table_value "$TBL" com.example.none fan)"  ""
eq "absent key is empty"        "$(table_value "$TBL" com.example.emu rgb)"   ""
table_value "$TMP/nosuch" com.example.game fan >/dev/null 2>&1 \
  && bad "a missing table file returns non-zero" "returned 0" \
  || ok  "a missing table file returns non-zero"

echo
echo "effective_fan_tier - resolution across both displays"
eq "no overrides yields the baseline" \
   "$(effective_fan_tier "$TBL" balanced rip.moth.cocoonshell rip.moth.cocoonshell)" "balanced"
eq "a profiled app on display 0 raises it" \
   "$(effective_fan_tier "$TBL" quiet com.example.game rip.moth.cocoonshell)" "performance"
eq "a profiled app on display 1 raises it (top screen off)" \
   "$(effective_fan_tier "$TBL" quiet rip.moth.cocoonshell com.example.emu)" "balanced"
eq "game on top is not downgraded by an unprofiled bottom app" \
   "$(effective_fan_tier "$TBL" quiet com.example.game com.android.calculator2)" "performance"
eq "the higher of two profiled apps wins" \
   "$(effective_fan_tier "$TBL" quiet com.example.game com.example.emu)" "performance"
eq "an override never lowers the baseline" \
   "$(effective_fan_tier "$TBL" performance com.example.emu rip.moth.cocoonshell)" "performance"
eq "an unknown tier value in the table is ignored" \
   "$(effective_fan_tier "$TBL" quiet com.example.weird rip.moth.cocoonshell)" "quiet"
eq "a package with only a non-fan key is ignored" \
   "$(effective_fan_tier "$TBL" quiet com.example.other rip.moth.cocoonshell)" "quiet"

echo
echo "effective_fan_tier - it must never produce 'default'"
effective_fan_tier "$TBL" default com.example.game rip.moth.cocoonshell >/dev/null 2>&1 \
  && bad "refuses a 'default' baseline" "accepted it" \
  || ok  "refuses a 'default' baseline"
out=$(effective_fan_tier "$TBL" default com.example.game '' 2>/dev/null)
eq "and emits nothing in that case" "$out" ""
# Exhaustive: no combination of inputs may ever emit the word 'default'.
emitted_default=0
for base in quiet balanced performance default '' wibble; do
  for top in com.example.game com.example.emu com.example.weird rip.moth.cocoonshell ''; do
    r=$(effective_fan_tier "$TBL" "$base" "$top" com.example.emu 2>/dev/null)
    [ "$r" = "default" ] && emitted_default=1
  done
done
eq "no input combination emits 'default'" "$emitted_default" "0"

echo
echo "parse_foreground - the real foreground oracle"
# Fixtures are REAL contiguous slices of `dumpsys activity activities`
# captured from the device, taken from thor-screenmove's own captures
# (2026-10-22 / 2026-10-27): each Display header through that section's
# topResumedActivity line, byte for byte. Not hand-written.
FIX="$HERE/fixtures"
fg() { parse_foreground < "$FIX/$1" | awk -v d="$2" '$1==d {print $2}'; }

eq "files-on-display0: display 0" "$(fg files-on-display0.txt 0)" "com.android.documentsui"
eq "files-on-display0: display 1" "$(fg files-on-display0.txt 1)" "rip.moth.cocoonshell"
eq "idle-stock-launcher: display 0" "$(fg idle-stock-launcher.txt 0)" "com.android.launcher3"
eq "cocoon-both-screens: display 0" "$(fg cocoon-both-screens.txt 0)" "rip.moth.cocoonshell"
eq "cocoon-both-screens: display 1" "$(fg cocoon-both-screens.txt 1)" "rip.moth.cocoonshell"

# THE ORDERING TRAP. dumpsys lists the FOCUSED display first, so this real
# capture has "Display #1" before "Display #0". A parser that inferred the id
# from section position returns these two swapped.
eq "display order is not positional: display 1" "$(fg app-on-display1.txt 1)" "com.android.deskclock"
eq "display order is not positional: display 0" "$(fg app-on-display1.txt 0)" "com.android.documentsui"

echo
echo "parse_foreground - edge cases"
eq "empty input yields nothing" "$(printf '' | parse_foreground)" ""
eq "no topResumedActivity yields nothing" \
   "$(printf 'Display #0 (activities from top to bottom):\n  isSleeping=true\n' | parse_foreground)" ""
# CONSTRUCTED, not captured: no real fixture happens to have a Task line
# naming a different package from its topResumedActivity, but Task lines do
# carry `A=` and `I=pkg/.Activity` components and matching one would read the
# wrong app. This asserts only topResumedActivity is consulted.
eq "a Task line's own component is ignored" \
   "$(printf 'Display #0 (x):\n  * Task{1 #2 type=standard I=com.evil.other/.Main U=0}\n    topResumedActivity=ActivityRecord{9 u0 com.real.app/.Main t2}\n' \
      | parse_foreground)" "0 com.real.app"
eq "only the first resumed line per section counts" \
   "$(printf 'Display #0 (x):\n    topResumedActivity=ActivityRecord{1 u0 com.first.app/.A t1}\n    topResumedActivity=ActivityRecord{2 u0 com.second.app/.B t2}\n' \
      | parse_foreground)" "0 com.first.app"

echo
echo "parse_foreground - cross-check against the FULL captures"
# The trimmed fixtures above could in principle have lost something that
# matters. Re-run the parser over thor-screenmove's untrimmed 56-81 KB
# captures and assert the same answers. Skipped rather than passed if that
# component is not present.
FULL="$HERE/../../thor-screenmove/tests/fixtures"
if [ -d "$FULL" ]; then
  for pair in "files-on-display0.txt 0 com.android.documentsui" \
              "app-on-display1.txt 1 com.android.deskclock" \
              "app-on-display1.txt 0 com.android.documentsui" \
              "idle-stock-launcher.txt 1 rip.moth.cocoonshell"; do
    set -- $pair
    eq "full capture $1 display $2" \
       "$(parse_foreground < "$FULL/$1" | awk -v d="$2" '$1==d {print $2}')" "$3"
  done
else
  printf '  \033[33mSKIP\033[0m thor-screenmove fixtures not present\n'
fi

echo
echo "resolution driven by real dumpsys output"
RTBL="$TMP/rtbl"
printf 'com.android.deskclock\tfan\tperformance\n' > "$RTBL"
res() {
  local f=$1 base=$2
  local t b
  t=$(parse_foreground < "$FIX/$f" | awk '$1==0 {print $2}')
  b=$(parse_foreground < "$FIX/$f" | awk '$1==1 {print $2}')
  effective_fan_tier "$RTBL" "$base" "$t" "$b"
}
# DeskClock is profiled and sits on display 1 with the TOP screen on a
# launcher - the "emulator on the bottom panel" case that made both displays
# count in the first place.
eq "profiled app on the bottom panel raises the tier" "$(res app-on-display1.txt quiet)" "performance"
eq "nothing profiled yields the baseline" "$(res files-on-display0.txt balanced)" "balanced"
eq "launcher on both screens yields the baseline" "$(res cocoon-both-screens.txt quiet)" "quiet"

echo
echo "should_apply - the no-op guard"
should_apply quiet performance && ok "applies on a real change" \
  || bad "applies on a real change" "declined"
should_apply performance performance \
  && bad "re-selecting the same tier is a no-op" "would apply" \
  || ok  "re-selecting the same tier is a no-op"
should_apply quiet '' \
  && bad "an empty effective tier never applies" "would apply" \
  || ok  "an empty effective tier never applies"
should_apply quiet default \
  && bad "'default' never reaches the device" "would apply" \
  || ok  "'default' never reaches the device"
should_apply '' balanced && ok "applies when nothing is applied yet" \
  || bad "applies when nothing is applied yet" "declined"

echo
printf 'appwatch logic: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
