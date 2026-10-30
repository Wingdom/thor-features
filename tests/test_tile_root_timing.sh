#!/usr/bin/env bash
# Cross-tile invariant: a Quick Settings tile must not request root while the
# shade is opening.
#
# Why this is a test and not a comment. Quick Settings calls
# onStartListening() every time the shade opens. Magisk's prompt cannot take
# focus over the open shade, so an su request made from there is *rejected*
# rather than prompted -- the tile lands in "no root" and the user is never
# asked anything. Confirmed on device 2026-10-29: rejections in logcat, no
# prompt shown, no policy row for any of the three apps. The request has to
# come from onClick(), which is a foreground action the prompt can appear over.
#
# Each tile's own host tests cover what it renders from cache. They cannot
# cover this, because it is a property of the TileService -- which needs the
# Android framework and so is never exercised on the host. So this checks the
# source instead, and it checks reachability rather than looking for a literal
# string: a regression would most likely reappear as onStartListening() calling
# some helper that calls the client, not as a direct call.
#
# Run standalone, or via any tile's build.sh.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  ok    $1"; }
bad() { fail=$((fail+1)); echo "  FAIL  $1"; }

echo "tile root timing (shade must not request root)"

# $1 service source file. Prints, for each of onStartListening and onClick,
# "<method> <yes|no>" -- whether the client field is reachable from it.
#
# Comments are stripped first. A comment explaining that we never call the
# client here would otherwise read as a call: exactly the trap that made two
# earlier module tests fail on correct code.
reach() {
    perl -0777 -pe 's{/\*.*?\*/}{}gs; s{//[^\n]*}{}g' "$1" | awk '
    # Collect each method body, keyed by name, tracking brace depth.
    #
    # Two things this has to get right, both found by it getting them wrong:
    #
    #  - the annotation may share the line. thor-screenmove-tile writes
    #    "@Override public void onClick() {" on one line while the other two
    #    put @Override on its own, and a pattern anchored on the modifier
    #    silently collected no body at all -- which reads as "clean".
    #
    #  - an anonymous Runnable run() is NOT a new method. Every one of these
    #    tiles does its CLI work inside "new Thread(new Runnable() { ... })",
    #    so treating run() as a separate method would move the client call out
    #    of the enclosing method and break the reachability trail. Hence the
    #    cur == "" guard: nested declarations stay part of their enclosing body.
    cur == "" \
      && /^[[:space:]]*(@[A-Za-z0-9_]+[[:space:]]+)*(public|private|protected)[^;=]*\(/ \
      && /\{/ {
        name = $0
        sub(/\(.*/, "", name)
        gsub(/.*[^A-Za-z0-9_]/, "", name)
        cur = name
        depth = gsub(/\{/, "{") - gsub(/\}/, "}")
        body[cur] = body[cur] $0 "\n"
        if (depth <= 0) cur = ""
        next
    }
    cur != "" {
        body[cur] = body[cur] $0 "\n"
        depth += gsub(/\{/, "{") - gsub(/\}/, "}")
        if (depth <= 0) cur = ""
        next
    }
    END {
        split("onStartListening onClick", roots, " ")
        for (r in roots) print roots[r], (touches(roots[r]) ? "yes" : "no")
    }
    # True when the client field is reachable from method m, following calls to
    # other methods declared in this file. Depth-limited rather than a visited
    # set, because awk has no recursion-safe sets -- these classes are shallow.
    function touches(m,    b, n, i, parts, callee) {
        b = body[m]
        if (b == "") return 0
        if (b ~ /(^|[^A-Za-z0-9_])client[^A-Za-z0-9_]*\./) return 1
        if (b ~ /(^|[^A-Za-z0-9_])client[[:space:]]*=/) return 1
        for (n in body) {
            if (n == m) continue
            if (b ~ ("(^|[^A-Za-z0-9_])" n "[[:space:]]*\\(")) {
                if (touches(n)) return 1
            }
        }
        return 0
    }'
}

for tile in thor-fan-tile thor-stickfx-tile thor-screenmove-tile; do
    svc=$(find "$ROOT/$tile/src" -name '*TileService.java' 2>/dev/null | head -1)
    if [ -z "$svc" ]; then bad "$tile: no TileService found"; continue; fi

    out=$(reach "$svc")
    listen=$(echo "$out" | awk '$1=="onStartListening"{print $2}')
    click=$(echo "$out" | awk '$1=="onClick"{print $2}')

    if [ "$listen" = "no" ]; then
        ok "$tile: onStartListening cannot reach the CLI client"
    elif [ "$listen" = "yes" ]; then
        bad "$tile: onStartListening reaches the CLI client -- the shade would request root, and Magisk cannot prompt over it"
    else
        bad "$tile: could not determine what onStartListening reaches"
    fi

    # The mirror of the rule. Moving the request out of the shade is only
    # correct if a tap still makes it; a tile that never asks for root at all
    # would satisfy the check above and be useless.
    if [ "$click" = "yes" ]; then
        ok "$tile: onClick does reach the CLI client (root is still obtainable)"
    elif [ "$click" = "no" ]; then
        bad "$tile: onClick never reaches the CLI client -- nothing would ever request root"
    else
        bad "$tile: could not determine what onClick reaches"
    fi
done

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ] || exit 1
