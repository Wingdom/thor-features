#!/usr/bin/env bash
# Pure-logic tests for the XML transform. No device, no root.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$HERE/.."
DM="sh ./display-match"
PASS=0; FAIL=0
ok(){ PASS=$((PASS+1)); echo "  ok   $1"; }
no(){ FAIL=$((FAIL+1)); echo "  FAIL $1"; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT

STOCK="$T/stock.xml"
cat > "$STOCK" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<display-settings>
    <config identifier="0" />
    <display
        name="local:222"
        isHomeSupported="true"
        shouldShowSystemDecors="true"
        shouldShowIme="true" />
</display-settings>
XML

OUT=$($DM make-xml "$STOCK" local:111)

echo "$OUT" | grep -q 'name="local:111"' \
  && ok "inserts the primary display entry" || no "inserts the primary display entry"

echo "$OUT" | grep -A1 'name="local:111"' | grep -q 'ignoreOrientationRequest="true"' \
  && ok "entry carries ignoreOrientationRequest=true" \
  || no "entry carries ignoreOrientationRequest=true"

echo "$OUT" | grep -q 'name="local:222"' \
  && ok "preserves the ROM's existing secondary entry" \
  || no "preserves the ROM's existing secondary entry"

echo "$OUT" | grep -q 'shouldShowIme="true"' \
  && ok "preserves the secondary entry's attributes" \
  || no "preserves the secondary entry's attributes"

echo "$OUT" | grep -q '<config identifier="0" />' \
  && ok "preserves the config element" || no "preserves the config element"

# Insertion must land INSIDE the root element.
LAST=$(echo "$OUT" | grep -n '</display-settings>' | cut -d: -f1)
MINE=$(echo "$OUT" | grep -n 'name="local:111"' | cut -d: -f1)
[ -n "$LAST" ] && [ -n "$MINE" ] && [ "$MINE" -lt "$LAST" ] \
  && ok "entry is inside the root element" || no "entry is inside the root element"

# Idempotent: running on its own output changes nothing.
echo "$OUT" > "$T/once.xml"
TWICE=$($DM make-xml "$T/once.xml" local:111)
[ "$OUT" = "$TWICE" ] && ok "idempotent on re-run" || no "idempotent on re-run"

N=$(echo "$TWICE" | grep -c 'name="local:111"')
[ "$N" -eq 1 ] && ok "no duplicate entry on re-run" || no "no duplicate entry on re-run ($N found)"

# Well-formedness, if a parser is available.
if command -v python3 >/dev/null 2>&1; then
  echo "$OUT" > "$T/wf.xml"
  if python3 -c "import xml.etree.ElementTree as E,sys; E.parse(sys.argv[1])" "$T/wf.xml" 2>/dev/null
  then ok "output parses as XML"; else no "output parses as XML"; fi
else
  echo "  skip output parses as XML (no python3)"
fi

# Failure modes must fail, not silently emit something wrong.
$DM make-xml "$T/missing.xml" local:111 >/dev/null 2>&1 \
  && no "rejects a missing input file" || ok "rejects a missing input file"

$DM make-xml "$STOCK" "" >/dev/null 2>&1 \
  && no "rejects an empty display id" || ok "rejects an empty display id"

printf '<nope/>\n' > "$T/bad.xml"
$DM make-xml "$T/bad.xml" local:111 >/dev/null 2>&1 \
  && no "rejects XML with no </display-settings>" \
  || ok "rejects XML with no </display-settings>"

# Against the real captured ROM file.
if [ -f tests/fixtures/vendor-display_settings.xml ]; then
  R=$($DM make-xml tests/fixtures/vendor-display_settings.xml local:4630946441858561667)
  echo "$R" | grep -q 'name="local:4630946482288158084"' \
    && ok "real ROM file: secondary entry preserved" \
    || no "real ROM file: secondary entry preserved"
  echo "$R" | grep -q 'name="local:4630946441858561667"' \
    && ok "real ROM file: primary entry added" \
    || no "real ROM file: primary entry added"
fi

echo; echo "  logic: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
