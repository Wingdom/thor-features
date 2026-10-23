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

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
