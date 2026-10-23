#!/usr/bin/env bash
# Module structure + behaviour. customize.sh / service.sh / uninstall.sh are
# executed against stubs rather than grepped.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE/.."
PASS=0; FAIL=0
ok(){ PASS=$((PASS+1)); echo "  ok   $1"; }
no(){ FAIL=$((FAIL+1)); echo "  FAIL $1"; }

for k in id name version versionCode author description; do
  grep -q "^$k=" magisk/module.prop && ok "module.prop has $k" || no "module.prop has $k"
done
[ "$(awk -F= '/^id=/{print $2}' magisk/module.prop)" = thor-display-density ] \
  && ok "module id is thor-display-density" || no "module id is thor-display-density"
for f in magisk/customize.sh magisk/service.sh magisk/uninstall.sh magisk/system/bin/display-density; do
  [ -f "$f" ] && ok "present: $f" || no "present: $f"
done
cmp -s display-density magisk/system/bin/display-density \
  && ok "shipped display-density matches source" || no "shipped display-density matches source"
grep -qi "rotation\|ignoreOrientationRequest" display-density magisk/*.sh \
  && no "module is free of rotation logic" || ok "module is free of rotation logic"

mkstub(){ # $1 dir, $2 device, $3 physical density
  mkdir -p "$1/bin"
  printf '#!/bin/sh\ncase "$1" in ro.product.device) echo "%s";; ro.product.model) echo "AYN Thor";; sys.boot_completed) echo 1;; *) echo "";; esac\n' "$2" > "$1/bin/getprop"
  cat > "$1/bin/wm" <<STUB
#!/bin/sh
echo "\$*" >> "$1/wm.calls"
if [ "\$1" = density ]; then
  case "\$2" in
    -d) echo "Physical density: $3"
        [ "\$3" = 0 ] && [ -s "$1/forced" ] && echo "Override density: \$(cat "$1/forced")" ;;
    reset) : > "$1/forced" ;;
    *) echo "\$2" > "$1/forced" ;;
  esac
fi
exit 0
STUB
  printf '#!/bin/sh\nexit 0\n' > "$1/bin/sleep"
  printf '#!/bin/sh\nexit 0\n' > "$1/bin/dumpsys"
  chmod 755 "$1"/bin/*
}
runcustomize(){
  cat > "$1/run.sh" <<'PRE'
ui_print(){ echo "UI: $*"; }
abort(){ echo "ABORT: $*"; exit 9; }
set_perm_recursive(){ :; }; set_perm(){ :; }
. "$CUSTOMIZE"
PRE
  PATH="$1/bin:$PATH" CUSTOMIZE="$PWD/magisk/customize.sh" MODPATH="$1/mod" \
    THOR_DD_STATE="$1/state" sh "$1/run.sh" 2>&1
}

# happy path: installs, applies nothing
A=$(mktemp -d); mkstub "$A" odin2thor 320; mkdir -p "$A/mod/system/bin"
cp display-density "$A/mod/system/bin/"; : > "$A/wm.calls"
OUT=$(runcustomize "$A"); RC=$?
[ $RC -eq 0 ] && ok "customize.sh succeeds on odin2thor" || no "customize.sh succeeds on odin2thor ($OUT)"
grep -qE '^density (reset|[0-9]+)' "$A/wm.calls" 2>/dev/null \
  && no "install applies no density of its own" || ok "install applies no density of its own"

# wrong device
B=$(mktemp -d); mkstub "$B" sunfish 320; mkdir -p "$B/mod/system/bin"; cp display-density "$B/mod/system/bin/"
OUT=$(runcustomize "$B"); RC=$?
{ [ $RC -ne 0 ] && echo "$OUT" | grep -qi "not an odin2thor"; } \
  && ok "aborts on a non-odin2thor device" || no "aborts on a non-odin2thor device"

# unexpected panel density -> the preset descriptions would be wrong
C=$(mktemp -d); mkstub "$C" odin2thor 480; mkdir -p "$C/mod/system/bin"; cp display-density "$C/mod/system/bin/"
OUT=$(runcustomize "$C"); RC=$?
{ [ $RC -ne 0 ] && echo "$OUT" | grep -qi "Unexpected panel"; } \
  && ok "aborts on an unexpected panel density" || no "aborts on an unexpected panel density"

# service.sh: nothing saved -> does nothing
D=$(mktemp -d); mkstub "$D" odin2thor 320; mkdir -p "$D/state"; : > "$D/wm.calls"
PATH="$D/bin:$PATH" THOR_DD_STATE="$D/state" sh magisk/service.sh
[ -s "$D/wm.calls" ] && no "service.sh is a no-op with no saved preset" \
  || ok "service.sh is a no-op with no saved preset"
[ -f "$D/state/boot.log" ] && no "service.sh writes no log with nothing to do" \
  || ok "service.sh writes no log with nothing to do"

# service.sh: preset saved -> re-applies it to display 0 and verifies
E=$(mktemp -d); mkstub "$E" odin2thor 320; mkdir -p "$E/state"; echo 288 > "$E/state/preset"; : > "$E/wm.calls"
PATH="$E/bin:$PATH" THOR_DD_STATE="$E/state" sh magisk/service.sh
grep -q '^density 288 -d 0$' "$E/wm.calls" && ok "service.sh re-applies the saved preset to display 0" \
  || no "service.sh re-applies the saved preset to display 0"
grep '^density' "$E/wm.calls" | grep -qE '^density (reset|[0-9]+) .*-d 1' \
  && no "service.sh never writes display 1" || ok "service.sh never writes display 1"
grep -q "applied: display 0 now 288" "$E/state/boot.log" 2>/dev/null \
  && ok "service.sh verifies and logs the effect" || no "service.sh verifies and logs the effect"

# uninstall: clears a forced density, only if one was set
F=$(mktemp -d); mkstub "$F" odin2thor 320; mkdir -p "$F/state"; echo 369 > "$F/state/preset"; : > "$F/wm.calls"
PATH="$F/bin:$PATH" THOR_DD_STATE="$F/state" sh magisk/uninstall.sh
grep -q '^density reset -d 0$' "$F/wm.calls" && ok "uninstall resets the forced density" \
  || no "uninstall resets the forced density"
[ -d "$F/state" ] && no "uninstall removes its state dir" || ok "uninstall removes its state dir"

G=$(mktemp -d); mkstub "$G" odin2thor 320; mkdir -p "$G/state"; : > "$G/wm.calls"
PATH="$G/bin:$PATH" THOR_DD_STATE="$G/state" sh magisk/uninstall.sh
[ -s "$G/wm.calls" ] && no "uninstall touches nothing if no preset was set" \
  || ok "uninstall touches nothing if no preset was set"
rm -rf "$A" "$B" "$C" "$D" "$E" "$F" "$G"

echo; echo "  module: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
