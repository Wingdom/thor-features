#!/usr/bin/env bash
# Pure-logic tests for preset resolution. No device, no root.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE/.."
DD="sh ./display-density"
PASS=0; FAIL=0
ok(){ PASS=$((PASS+1)); echo "  ok   $1"; }
no(){ FAIL=$((FAIL+1)); echo "  FAIL $1"; }

expect(){ # expect <input> <output>
  got=$($DD resolve "$1" 2>/dev/null)
  [ "$got" = "$2" ] && ok "resolve '$1' -> $2" || no "resolve '$1' -> $2 (got '$got')"
}
expect native reset
expect reset  reset
expect 320    320
expect 369    369
expect 288    288
expect stock    369
expect tablet   288
expect physical 320

# Anything outside the list must be refused, not forwarded to wm.
for bad in 400 480 240 272 1 0 -1 abc "" "320; rm -rf /" "3 2 0"; do
  $DD resolve "$bad" >/dev/null 2>&1 \
    && no "refuses '$bad'" || ok "refuses '$bad'"
done

# The four documented presets, and only those, must be offered in usage.
U=$($DD --help 2>&1)
for p in native 320 369 288; do
  echo "$U" | grep -qE "^  $p " && ok "usage lists $p" || no "usage lists $p"
done
for p in 400 240 272; do
  echo "$U" | grep -qE "^  $p " && no "usage does not offer $p" || ok "usage does not offer $p"
done
echo "$U" | grep -qi "secondary display is never touched" \
  && ok "usage states the secondary display is untouched" \
  || no "usage states the secondary display is untouched"

# Behavioural: stub wm, run every preset for real, and inspect the actual
# invocations. Grepping the source instead gives false positives on the text
# inside error messages.
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/state"
cat > "$T/bin/wm" <<'STUB'
#!/bin/sh
echo "$*" >> "$WM_CALLS"
if [ "$1" = density ]; then
  case "$2" in
    -d)     # read:  wm density -d N
            if [ "$3" = 0 ] && [ -s "$WM_STATE" ]; then
              echo "Physical density: 320"; echo "Override density: $(cat "$WM_STATE")"
            else
              echo "Physical density: 320"
            fi ;;
    reset)  : > "$WM_STATE" ;;
    *)      echo "$2" > "$WM_STATE" ;;
  esac
fi
exit 0
STUB
chmod 755 "$T/bin/wm"
printf '#!/bin/sh\nexit 0\n' > "$T/bin/sleep"; chmod 755 "$T/bin/sleep"

for preset in 320 369 288 native; do
  : > "$T/calls.$preset"; : > "$T/state.$preset"
  OUT=$(PATH="$T/bin:$PATH" WM_CALLS="$T/calls.$preset" WM_STATE="$T/state.$preset" \
        THOR_DD_STATE="$T/state" sh ./display-density "$preset" 2>&1); RC=$?
  [ $RC -eq 0 ] && ok "applies preset $preset" || no "applies preset $preset (rc=$RC: $OUT)"
  # every density call must name a display
  if grep -q '^density' "$T/calls.$preset" && ! grep '^density' "$T/calls.$preset" | grep -qv -- '-d '; then
    ok "preset $preset: all density calls pin a display"
  else
    no "preset $preset: all density calls pin a display ($(tr '\n' '; ' < "$T/calls.$preset"))"
  fi
  # and none may write to display 1
  if grep '^density' "$T/calls.$preset" | grep -qE '^density (reset|[0-9]+) .*-d 1'; then
    no "preset $preset: never writes display 1"
  else
    ok "preset $preset: never writes display 1"
  fi
done

# the saved preset is persisted for non-native, and cleared for native
: > "$T/calls.p"; : > "$T/state.p"; rm -rf "$T/st2"; mkdir -p "$T/st2"
PATH="$T/bin:$PATH" WM_CALLS="$T/calls.p" WM_STATE="$T/state.p" THOR_DD_STATE="$T/st2" \
  sh ./display-density 288 >/dev/null 2>&1
[ "$(cat "$T/st2/preset" 2>/dev/null)" = 288 ] && ok "saves the chosen preset" || no "saves the chosen preset"
PATH="$T/bin:$PATH" WM_CALLS="$T/calls.p" WM_STATE="$T/state.p" THOR_DD_STATE="$T/st2" \
  sh ./display-density native >/dev/null 2>&1
[ -f "$T/st2/preset" ] && no "native clears the saved preset" || ok "native clears the saved preset"

# revert clears both the forced density and the saved preset
: > "$T/calls.r"; echo 288 > "$T/state.r"; mkdir -p "$T/st3"; echo 288 > "$T/st3/preset"
PATH="$T/bin:$PATH" WM_CALLS="$T/calls.r" WM_STATE="$T/state.r" THOR_DD_STATE="$T/st3" \
  sh ./display-density revert >/dev/null 2>&1
grep -q '^density reset -d 0$' "$T/calls.r" && ok "revert resets display 0" || no "revert resets display 0"
[ -f "$T/st3/preset" ] && no "revert clears the saved preset" || ok "revert clears the saved preset"

# a refused preset must not reach wm at all
: > "$T/calls.b"; : > "$T/state.b"
PATH="$T/bin:$PATH" WM_CALLS="$T/calls.b" WM_STATE="$T/state.b" THOR_DD_STATE="$T/state" \
  sh ./display-density 400 >/dev/null 2>&1
[ -s "$T/calls.b" ] && no "a refused preset never calls wm" || ok "a refused preset never calls wm"

echo; echo "  logic: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
