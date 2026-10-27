#!/usr/bin/env bash
# Host-side tests for screenmove's task parser. No device required.
#
# The parser is the only non-trivial logic in the module: everything else is a
# single `am` call. It runs against *recorded* dumpsys output in fixtures/,
# captured from the real device on 2026-10-22 with the stock launcher, so these
# tests exercise the exact text the script will see rather than an idealised
# version of it.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/../screenmove"
FIX="$HERE/fixtures"

pass=0; fail=0

# The script's shebang is /system/bin/sh, which does not exist on the host, so
# invoke it through sh explicitly.
parse() { sh "$SCRIPT" _parse_stdin "$2" < "$FIX/$1"; }
# Same, with a launcher skip list - the second argument screenmove passes in
# real use. Kept separate so the existing no-skip cases still assert the
# parser's behaviour when the launcher query yields nothing (fail-open).
parse_sk() { sh "$SCRIPT" _parse_stdin "$2" "$3" < "$FIX/$1"; }
launchers() { sh "$SCRIPT" _launchers_stdin < "$FIX/$1"; }

check() {
    local label=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        pass=$((pass+1)); printf '  ok    %s\n' "$label"
    else
        fail=$((fail+1)); printf '  FAIL  %s\n       expected [%s] got [%s]\n' "$label" "$expected" "$actual"
    fi
}

echo "screenmove parser tests"

# --- fixture 1: idle, stock launcher on both screens --------------------------
# Display 0 has a *standard* task (#259, Settings) but visible=false, plus the
# Bubbles/SplitRoot type=undefined containers. Nothing should be movable:
# an invisible task is not what the user is looking at, and the containers are
# not user apps. This is the case that makes the tile say "nothing to move".
check "idle: nothing movable on top"    "" "$(parse idle-stock-launcher.txt 0)"
check "idle: nothing movable on bottom" "" "$(parse idle-stock-launcher.txt 1)"

# --- fixture 2: Files open on the top screen ----------------------------------
check "files on 0: finds #261 on top"      "261" "$(parse files-on-display0.txt 0)"
check "files on 0: bottom still launcher"  ""    "$(parse files-on-display0.txt 1)"

# --- fixture 3: app on each screen, display 1 listed FIRST --------------------
# This fixture is the regression guard for display ordering. dumpsys lists the
# *focused* display first, so here "Display #1" appears before "Display #0".
# A parser that assumed id order, or that took the first section it saw, would
# return 262 for display 0.
check "both: finds #262 on bottom" "262" "$(parse app-on-display1.txt 1)"
check "both: finds #261 on top"    "261" "$(parse app-on-display1.txt 0)"

# --- unknown display ----------------------------------------------------------
check "unknown display yields nothing" "" "$(parse app-on-display1.txt 7)"

# --- root tasks only ----------------------------------------------------------
# Child tasks are indented deeper and carry rootTaskId=. `am display move-stack`
# takes a root id, so a parser that matched children would hand it an id that
# belongs to a nested task - which either fails or moves something unexpected.
# Assert directly that no id we return is a child id in the same fixture.
for f in idle-stock-launcher.txt files-on-display0.txt app-on-display1.txt; do
    for d in 0 1; do
        got=$(parse "$f" "$d")
        [ -n "$got" ] || continue
        if grep -qE "^    \* Task\{[0-9a-f]+ #$got .*rootTaskId=" "$FIX/$f"; then
            fail=$((fail+1)); printf '  FAIL  %s d%s returned child task #%s\n' "$f" "$d" "$got"
        else
            pass=$((pass+1)); printf '  ok    %s d%s returned a root task (#%s)\n' "$f" "$d" "$got"
        fi
    done
done

# --- synthetic: never return home/recents ------------------------------------
# Built by hand because the device would not readily produce a *visible*
# standard task sitting below a visible home task.
tmp=$(mktemp)
cat > "$tmp" <<'EOF'
Display #0 (activities from top to bottom):
  * Task{aaa #10 type=home U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
  * Task{bbb #11 type=recents U=0 visible=true visibleRequested=true mode=fullscreen translucent=true sz=1}
  * Task{ccc #12 type=undefined name=Bubbles U=0 visible=true visibleRequested=true mode=multi-window translucent=true sz=1}
  * Task{ddd #13 type=standard U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
EOF
check "skips home/recents/undefined, picks standard" "13" "$(sh "$SCRIPT" _parse_stdin 0 < "$tmp")"
rm -f "$tmp"

# --- synthetic: topmost standard wins ----------------------------------------
tmp=$(mktemp)
cat > "$tmp" <<'EOF'
Display #0 (activities from top to bottom):
  * Task{aaa #20 type=standard U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
  * Task{bbb #21 type=standard U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
EOF
check "topmost standard wins" "20" "$(sh "$SCRIPT" _parse_stdin 0 < "$tmp")"
rm -f "$tmp"

# --- synthetic: later repeated blocks must not leak --------------------------
# The real dump repeats per-display information further down under different
# headers. Only `^Display #N (activities` starts a task listing; anything else
# must leave the parser closed.
tmp=$(mktemp)
cat > "$tmp" <<'EOF'
Display #0 (activities from top to bottom):
  * Task{aaa #30 type=standard U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
WindowManager display contents
Display #0 some other block:
  * Task{bbb #99 type=standard U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
EOF
check "ignores non-activity Display blocks" "30" "$(sh "$SCRIPT" _parse_stdin 0 < "$tmp")"
rm -f "$tmp"

# --- launcher resolution -----------------------------------------------------
# Recorded `cmd package query-activities` output for CATEGORY_HOME and
# CATEGORY_SECONDARY_HOME, captured 2026-10-27 with CocoonFE set as home.
check "launchers: resolves launcher3 + cocoon" \
      "com.android.launcher3 rip.moth.cocoonshell " \
      "$(launchers launcher-query.txt)"

# com.android.settings/.FallbackHome registers as HOME at priority=-1000.
# Treating Settings as a launcher would make Settings unmovable - a worse bug
# than the one being fixed - so negative-priority matches must be dropped.
if launchers launcher-query.txt | grep -q "com.android.settings"; then
    fail=$((fail+1)); printf '  FAIL  launchers: FallbackHome made Settings unmovable\n'
else
    pass=$((pass+1)); printf '  ok    launchers: drops negative-priority FallbackHome\n'
fi

# --- fixture 4: CocoonFE idle on both screens --------------------------------
# Real capture, 2026-10-27. Cocoon's second-screen UI
# (rip.moth.cocoonshell/.ExternalDisplayActivity) runs as an ordinary activity,
# so its task is type=standard and the type=home check does not catch it.
# Without the launcher list the parser offers it up as movable - that is the
# bug, and this asserts it so the fix cannot silently stop mattering.
check "cocoon: without skip list, offers Cocoon's own UI (the bug)" \
      "38" "$(parse cocoon-both-screens.txt 1)"

COCOON="com.android.launcher3 rip.moth.cocoonshell"
check "cocoon: top is launcher only"    "" "$(parse_sk cocoon-both-screens.txt 0 "$COCOON")"
check "cocoon: bottom is launcher only" "" "$(parse_sk cocoon-both-screens.txt 1 "$COCOON")"

# --- synthetic: emulator launched from Cocoon onto the bottom screen ---------
# Synthetic rather than captured: producing it needs a game running, and the
# device was in use elsewhere. Shapes copied verbatim from real task lines -
# Cocoon's from cocoon-both-screens.txt, the emulator's from a 2026-10-27
# capture of Azahar (A=10172:io.github.lime3ds.android).
tmp=$(mktemp)
cat > "$tmp" <<'EOF'
Display #0 (activities from top to bottom):
  * Task{aaa #40 type=standard A=10178:rip.moth.cocoonshell U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
Display #1 (activities from top to bottom):
  * Task{bbb #41 type=standard A=10172:io.github.lime3ds.android U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
EOF
check "emulator: Cocoon on top is not movable" \
      "" "$(sh "$SCRIPT" _parse_stdin 0 " $COCOON " < "$tmp")"
check "emulator: the emulator on the bottom is" \
      "41" "$(sh "$SCRIPT" _parse_stdin 1 " $COCOON " < "$tmp")"
rm -f "$tmp"

# --- skip matching is whole-package, not substring ---------------------------
# " pkg " delimiters matter: a prefix match would wrongly protect any package
# whose name starts with a launcher's.
tmp=$(mktemp)
cat > "$tmp" <<'EOF'
Display #0 (activities from top to bottom):
  * Task{aaa #50 type=standard A=10001:com.android.launcher3x U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
EOF
check "skip list does not prefix-match" \
      "50" "$(sh "$SCRIPT" _parse_stdin 0 " com.android.launcher3 " < "$tmp")"
rm -f "$tmp"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
