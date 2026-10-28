#!/usr/bin/env bash
# Host tests for the display-layout overlay. No device.
#
# stock/display_layout_configuration.xml is the ROM's own file, kept in the
# repo so these tests run for anyone who clones it. It is LineageOS's, Apache
# 2.0, and listed in THIRD-PARTY.md. Keeping it here rather than pointing at
# the local evidence directory is deliberate: a test that only runs on one
# machine is not a test.
#
# The overlay replaces a vendor config that also governs which panel is
# enabled in each display state. Removing or corrupting one of those entries
# would not fail loudly - it would boot with a panel disabled. So these tests
# assert that the overlay differs from stock by exactly one element and in no
# other way.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); cd "$HERE/.."

STOCK=stock/display_layout_configuration.xml
OURS=magisk/system/vendor/etc/displayconfig/display_layout_configuration.xml

pass=0; fail=0
check() {
    local label=$1 expected=$2 actual=$3
    if [ "$expected" = "$actual" ]; then
        pass=$((pass+1)); printf '  ok    %s\n' "$label"
    else
        fail=$((fail+1)); printf '  FAIL  %s\n       expected [%s] got [%s]\n' "$label" "$expected" "$actual"
    fi
}

echo "screenbright overlay tests"

check "the recorded stock file exists" "yes" "$([ -f "$STOCK" ] && echo yes || echo no)"
check "the overlay exists" "yes" "$([ -f "$OURS" ] && echo yes || echo no)"

# --- the whole point: exactly one line differs -------------------------------
check "exactly one line removed" "1" \
      "$(diff "$STOCK" "$OURS" | grep -c '^<')"
check "no line added" "0" \
      "$(diff "$STOCK" "$OURS" | grep -c '^>')"
check "the removed line is the lead-display element" "1" \
      "$(diff "$STOCK" "$OURS" | grep -c '^< *<leadDisplayAddress>')"
check "no leadDisplayAddress survives in the overlay" "0" \
      "$(grep -c 'leadDisplayAddress' "$OURS")"

# --- everything that governs panel enablement is untouched -------------------
# This file decides which panel is enabled in each display state. A mistake
# here boots the device with a screen disabled, which is a much worse failure
# than brightness not decoupling.
sig() { grep -oE '<state>[0-9]+</state>|enabled="[a-z]+"|defaultDisplay="[a-z]+"|<address>[0-9]+</address>' "$1" | tr '\n' ' '; }
check "every state, address and enabled flag is unchanged" "$(sig "$STOCK")" "$(sig "$OURS")"

check "all three layout states are present" "3" "$(grep -c '<state>' "$OURS")"
check "both panel addresses appear in every state" "6" "$(grep -c '<address>' "$OURS")"

# --- it is still well-formed XML ---------------------------------------------
# A truncated or unbalanced file would be parsed at boot, by the framework,
# with no good failure mode.
if command -v python3 >/dev/null 2>&1; then
    check "the overlay is well-formed XML" "ok" \
          "$(python3 -c "import xml.etree.ElementTree as E,sys; E.parse('$OURS'); print('ok')" 2>&1 | tail -1)"
    check "it still has a <layouts> root" "layouts" \
          "$(python3 -c "import xml.etree.ElementTree as E; print(E.parse('$OURS').getroot().tag)" 2>&1 | tail -1)"
    # The bottom panel must still be a display entry in state 0 - we removed
    # its lead relationship, not the display itself.
    check "state 0 still declares two displays" "2" \
          "$(python3 - <<'PY'
import xml.etree.ElementTree as E
r = E.parse('magisk/system/vendor/etc/displayconfig/display_layout_configuration.xml').getroot()
for lay in r.findall('layout'):
    if lay.findtext('state') == '0':
        print(len(lay.findall('display')))
        break
PY
)"
else
    echo "  skip  XML well-formedness (no python3)"
fi

# --- the install guard must actually guard -----------------------------------
# customize.sh compares the ROM's file, stripped of the lead line, against the
# overlay. Assert it does that comparison rather than merely mentioning it.
check "customize.sh diffs stock against the overlay" "yes" \
      "$(grep -q 'diff <(grep -v .<leadDisplayAddress>. "\$STOCK")' magisk/customize.sh && echo yes || echo no)"
check "customize.sh aborts when stock has nothing to remove" "yes" \
      "$(grep -q "grep -q '<leadDisplayAddress>' \"\$STOCK\"" magisk/customize.sh && echo yes || echo no)"

# --- no hardware writes -------------------------------------------------------
check "the module writes no sysfs node" "" \
      "$(grep -rhoE '> *"?/sys/[^ "]*' magisk/ | sort -u | tr '\n' ' ')"

for f in magisk/customize.sh magisk/uninstall.sh; do
    check "$f is valid shell" "ok" "$(sh -n "$f" 2>/dev/null && echo ok || echo broken)"
done

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
