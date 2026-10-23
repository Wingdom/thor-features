#!/usr/bin/env bash
# Module structure + behaviour tests. Runs customize.sh / uninstall.sh / service.sh
# for real against stubs, rather than grepping them for strings.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE/.."
PASS=0; FAIL=0
ok(){ PASS=$((PASS+1)); echo "  ok   $1"; }
no(){ FAIL=$((FAIL+1)); echo "  FAIL $1"; }

# ---- structure ----
for k in id name version versionCode author description; do
  grep -q "^$k=" magisk/module.prop && ok "module.prop has $k" || no "module.prop has $k"
done
[ "$(awk -F= '/^id=/{print $2}' magisk/module.prop)" = thor-display-match ] \
  && ok "module id is thor-display-match" || no "module id is thor-display-match"

for f in magisk/customize.sh magisk/service.sh magisk/uninstall.sh magisk/system/bin/display-match; do
  [ -f "$f" ] && ok "present: $f" || no "present: $f"
done
[ -x magisk/system/bin/display-match ] && ok "display-match is executable" \
  || no "display-match is executable"
cmp -s display-match magisk/system/bin/display-match \
  && ok "shipped display-match matches source" || no "shipped display-match matches source"

# The module must NOT ship a static vendor XML: it is generated at install time
# from the ROM's own file.
[ -e magisk/system/vendor/etc/display_settings.xml ] \
  && no "ships no pre-baked vendor XML" || ok "ships no pre-baked vendor XML"

# ---- behavioural harness ----
mkstub() {   # $1 = dir, $2 = device, $3 = dumpsys output
  mkdir -p "$1/bin"
  printf '#!/bin/sh\ncase "$1" in ro.product.device) echo "%s";; ro.product.model) echo "AYN Thor";; ro.build.date.utc) echo 1760000000;; *) echo "";; esac\n' "$2" > "$1/bin/getprop"
  printf '#!/bin/sh\ncat %s\n' "$3" > "$1/bin/dumpsys"
  printf '#!/bin/sh\necho "wm $*" >> %s/wm.calls\n' "$1" > "$1/bin/wm"
  printf '#!/bin/sh\nexit 0\n' > "$1/bin/sleep"
  printf '#!/bin/sh\necho ""\n' > "$1/bin/settings"
  chmod 755 "$1"/bin/*
}
runcustomize() {  # $1 = workdir ; env THOR_DM_* already set
  cat > "$1/run.sh" <<'PRE'
ui_print(){ echo "UI: $*"; }
abort(){ echo "ABORT: $*"; exit 9; }
set_perm_recursive(){ :; }
set_perm(){ :; }
. "$CUSTOMIZE"
PRE
  PATH="$1/bin:$PATH" CUSTOMIZE="$PWD/magisk/customize.sh" MODPATH="$1/mod" \
    THOR_DM_STOCK="$1/stock.xml" THOR_DM_STATE="$1/state" \
    THOR_DM_DATA_OVERRIDE="$1/dataoverride" \
    sh "$1/run.sh" 2>&1
}

T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
printf "DisplayViewport{type=INTERNAL, displayId=0, uniqueId='local:111', x=1}\n" > "$T/dump.txt"
cat > "$T/stock.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<display-settings>
    <config identifier="0" />
    <display name="local:222" shouldShowIme="true" />
</display-settings>
XML
mkstub "$T" odin2thor "$T/dump.txt"
mkdir -p "$T/mod/system/bin"; cp display-match "$T/mod/system/bin/"

OUT=$(runcustomize "$T"); RC=$?
[ $RC -eq 0 ] && ok "customize.sh succeeds on odin2thor" \
  || no "customize.sh succeeds on odin2thor (rc=$RC: $OUT)"
GEN="$T/mod/system/vendor/etc/display_settings.xml"
[ -f "$GEN" ] && ok "customize.sh generates the overlay" || no "customize.sh generates the overlay"
grep -q 'name="local:111"' "$GEN" 2>/dev/null \
  && ok "overlay targets the live primary display id" || no "overlay targets the live primary display id"
grep -q 'ignoreOrientationRequest="true"' "$GEN" 2>/dev/null \
  && ok "overlay sets ignoreOrientationRequest" || no "overlay sets ignoreOrientationRequest"
grep -q 'name="local:222"' "$GEN" 2>/dev/null \
  && ok "overlay preserves the ROM's secondary entry" || no "overlay preserves the ROM's secondary entry"
grep -q 'display_id=local:111' "$T/state/install.info" 2>/dev/null \
  && ok "records install.info for drift detection" || no "records install.info for drift detection"

# wrong device must abort
T2=$(mktemp -d); trap 'rm -rf "$T" "$T2"' EXIT
cp "$T/stock.xml" "$T2/stock.xml"; cp "$T/dump.txt" "$T2/dump.txt"
mkstub "$T2" sunfish "$T2/dump.txt"; mkdir -p "$T2/mod/system/bin"; cp display-match "$T2/mod/system/bin/"
OUT=$(runcustomize "$T2"); RC=$?
{ [ $RC -ne 0 ] && echo "$OUT" | grep -qi "not an odin2thor"; } \
  && ok "aborts on a non-odin2thor device" || no "aborts on a non-odin2thor device (rc=$RC)"

# missing stock file must abort
T3=$(mktemp -d); trap 'rm -rf "$T" "$T2" "$T3"' EXIT
cp "$T/dump.txt" "$T3/dump.txt"; mkstub "$T3" odin2thor "$T3/dump.txt"
mkdir -p "$T3/mod/system/bin"; cp display-match "$T3/mod/system/bin/"
OUT=$(runcustomize "$T3"); RC=$?
[ $RC -ne 0 ] && ok "aborts when the ROM has no display_settings.xml" \
  || no "aborts when the ROM has no display_settings.xml"

# ROM already carries the entry -> abort rather than no-op silently
T4=$(mktemp -d); trap 'rm -rf "$T" "$T2" "$T3" "$T4"' EXIT
cp "$T/dump.txt" "$T4/dump.txt"
cat > "$T4/stock.xml" <<'XML'
<display-settings>
    <display name="local:111" ignoreOrientationRequest="true" />
</display-settings>
XML
mkstub "$T4" odin2thor "$T4/dump.txt"; mkdir -p "$T4/mod/system/bin"; cp display-match "$T4/mod/system/bin/"
OUT=$(runcustomize "$T4"); RC=$?
[ $RC -ne 0 ] && ok "aborts when the ROM already sets it (nothing to do)" \
  || no "aborts when the ROM already sets it (nothing to do)"

# data override present -> must warn, still install
T5=$(mktemp -d); trap 'rm -rf "$T" "$T2" "$T3" "$T4" "$T5"' EXIT
cp "$T/dump.txt" "$T5/dump.txt"; cp "$T/stock.xml" "$T5/stock.xml"
mkstub "$T5" odin2thor "$T5/dump.txt"; mkdir -p "$T5/mod/system/bin"; cp display-match "$T5/mod/system/bin/"
: > "$T5/dataoverride"
OUT=$(runcustomize "$T5"); RC=$?
{ [ $RC -eq 0 ] && echo "$OUT" | grep -qi "OVERRIDES this module"; } \
  && ok "warns when /data override would mask the module" \
  || no "warns when /data override would mask the module"

# ---- upgrade path: a mounted previous version must not block reinstall ----
T6=$(mktemp -d); trap 'rm -rf "$T" "$T2" "$T3" "$T4" "$T5" "$T6"' EXIT
cp "$T/dump.txt" "$T6/dump.txt"
# what /vendor looks like WITH our overlay mounted: already carries the entry
cat > "$T6/stock.xml" <<'XML'
<display-settings>
    <display name="local:222" shouldShowIme="true" />
    <display name="local:111" ignoreOrientationRequest="true" />
</display-settings>
XML
mkstub "$T6" odin2thor "$T6/dump.txt"; mkdir -p "$T6/mod/system/bin" "$T6/state"
cp display-match "$T6/mod/system/bin/"
# ...but a pristine copy was saved at first install
cat > "$T6/state/stock.xml" <<'XML'
<display-settings>
    <display name="local:222" shouldShowIme="true" />
</display-settings>
XML
echo "rom_build=1760000000" > "$T6/state/install.info"
OUT=$(runcustomize "$T6"); RC=$?
[ $RC -eq 0 ] && ok "reinstall succeeds over a mounted previous version" \
  || no "reinstall succeeds over a mounted previous version (rc=$RC: $OUT)"
N=$(grep -c 'name="local:111"' "$T6/mod/system/vendor/etc/display_settings.xml" 2>/dev/null || echo 0)
[ "$N" = 1 ] && ok "reinstall yields exactly one primary entry" \
  || no "reinstall yields exactly one primary entry (got $N)"

# ROM moved since install -> refuse rather than reapply a stale overlay
T7=$(mktemp -d); trap 'rm -rf "$T" "$T2" "$T3" "$T4" "$T5" "$T6" "$T7"' EXIT
cp "$T/dump.txt" "$T7/dump.txt"; cp "$T6/stock.xml" "$T7/stock.xml"
mkstub "$T7" odin2thor "$T7/dump.txt"; mkdir -p "$T7/mod/system/bin" "$T7/state"
cp display-match "$T7/mod/system/bin/"; cp "$T6/state/stock.xml" "$T7/state/stock.xml"
echo "rom_build=1700000000" > "$T7/state/install.info"
OUT=$(runcustomize "$T7"); RC=$?
{ [ $RC -ne 0 ] && echo "$OUT" | grep -qi "ROM has changed"; } \
  && ok "refuses to reapply a stale overlay after a ROM update" \
  || no "refuses to reapply a stale overlay after a ROM update (rc=$RC)"

# mounted previous version AND no saved copy -> cannot recover, must say so
T8=$(mktemp -d); trap 'rm -rf "$T" "$T2" "$T3" "$T4" "$T5" "$T6" "$T7" "$T8"' EXIT
cp "$T/dump.txt" "$T8/dump.txt"; cp "$T6/stock.xml" "$T8/stock.xml"
mkstub "$T8" odin2thor "$T8/dump.txt"; mkdir -p "$T8/mod/system/bin" "$T8/moddir"
cp display-match "$T8/mod/system/bin/"
OUT=$(PATH="$T8/bin:$PATH" CUSTOMIZE="$PWD/magisk/customize.sh" MODPATH="$T8/mod" \
  THOR_DM_STOCK="$T8/stock.xml" THOR_DM_STATE="$T8/state" \
  THOR_DM_DATA_OVERRIDE="$T8/none" THOR_DM_MODDIR="$T8/moddir" \
  sh -c 'ui_print(){ echo "UI: $*"; }; abort(){ echo "ABORT: $*"; exit 9; };
         set_perm_recursive(){ :; }; set_perm(){ :; }; . "$CUSTOMIZE"' 2>&1); RC=$?
{ [ $RC -ne 0 ] && echo "$OUT" | grep -qi "under our own mount"; } \
  && ok "refuses when mounted with no pristine copy" \
  || no "refuses when mounted with no pristine copy (rc=$RC)"

# ---- uninstall behaviour ----
# uninstall must also clear the baked /data override -- disable alone does not
# revert, proven on device 2026-10-22.
U3=$(mktemp -d); mkdir -p "$U3/bin" "$U3/state"
printf '#!/bin/sh\nexit 0\n' > "$U3/bin/wm"; chmod 755 "$U3/bin/wm"
: > "$U3/override"
PATH="$U3/bin:$PATH" THOR_DM_STATE="$U3/state" THOR_DM_DATA_OVERRIDE="$U3/override" \
  sh magisk/uninstall.sh
[ -f "$U3/override" ] && no "uninstall removes the baked /data override" \
  || ok "uninstall removes the baked /data override"
rm -rf "$U3"

# revert: removes the override and resets a forced density
R=$(mktemp -d); mkdir -p "$R/bin" "$R/state"
printf '#!/bin/sh\nexit 0\n' > "$R/bin/wm"; chmod 755 "$R/bin/wm"
: > "$R/override"
OUT=$(PATH="$R/bin:$PATH" THOR_DM_STATE="$R/state" THOR_DM_DATA_OVERRIDE="$R/override" \
  sh ./display-match revert 2>&1)
[ -f "$R/override" ] && no "revert removes the /data override" || ok "revert removes the /data override"
echo "$OUT" | grep -qi "reboot" && ok "revert tells the user to reboot" \
  || no "revert tells the user to reboot"
rm -rf "$R"

# revert is safe when there is nothing to revert
R2=$(mktemp -d); mkdir -p "$R2/bin" "$R2/state"
printf '#!/bin/sh\nexit 0\n' > "$R2/bin/wm"; chmod 755 "$R2/bin/wm"
PATH="$R2/bin:$PATH" THOR_DM_STATE="$R2/state" THOR_DM_DATA_OVERRIDE="$R2/none" \
  sh ./display-match revert >/dev/null 2>&1 \
  && ok "revert exits clean with nothing to do" || no "revert exits clean with nothing to do"
rm -rf "$R2"

# scope guard: density belongs to a different module
grep -qi density display-match magisk/*.sh && no "module is free of density logic" \
  || ok "module is free of density logic"

echo; echo "  module: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
