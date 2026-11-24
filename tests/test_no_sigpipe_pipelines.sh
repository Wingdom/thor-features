#!/usr/bin/env bash
# Repo-wide invariant: no script that sets `pipefail` may end a pipeline in a
# quitting `grep -q`.
#
# Why this is a test and not a comment. `grep -q` exits as soon as it matches
# and closes its end of the pipe. The producer still has output to write, so it
# dies of SIGPIPE with status 141, and `set -o pipefail` promotes that 141 to
# the status of the whole pipeline -- so an assertion that FOUND its match
# reports that it did not.
#
# It is a race, not a certainty: if the producer finishes writing before grep
# quits, nothing goes wrong. Measured on this repo 2026-11-25 by running one
# such assertion 400 times:
#
#     set -o pipefail      1 wrong answer in 400
#     pipefail off         0 wrong answers in 400
#
# ~0.25% per assertion, over ~60 of them, is why the suites passed almost
# always and failed inexplicably now and then. thor-aynkey reported
# "SIGQUIT is trapped -- expected [yes] got [no]" once and passed on every
# re-run. This is the same trap as `yes | sdkmanager --licenses`, which cost
# two CI runs (see the comment in .github/workflows/ci.yml).
#
# The fix is never to turn pipefail off -- it catches real failures elsewhere.
# It is to make the assertion depend on grep's OWN status by buffering the
# producer's output first:
#
#     out=$(producer); grep -q PAT <<<"$out"      # or
#     grep -q PAT <<<"$(producer)"
#
# Scope, and why it stops at `grep -q`. `grep -q` writes nothing, so a pipeline
# ending in it exists only for its exit status -- precisely the status pipefail
# corrupts. `| head -1` quits early too, but it is used for its OUTPUT, and
# every instance here feeds a variable whose status nothing checks. Those are
# latent, not live: they would start lying the day a script adds `set -e`.
# Checked 2026-11-25 -- no script here combines `-e` with pipefail.
#
# Run standalone, or via CI. It is deliberately NOT wired into any component's
# build.sh: those must assert only on their own component, because release.yml
# builds a single tagged component and nothing else.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  ok    $1"; }
bad() { fail=$((fail+1)); echo "  FAIL  $1"; }

echo "no SIGPIPE-racy pipelines (pipefail + quitting grep -q)"

# A pipe, then `grep` with any option bundle containing `q`, or `--quiet`.
# Catches `| grep -q`, `| grep -qE`, `| grep -qv`, `| grep -A1 -q`, and a
# continuation line that begins with `| grep -q`.
RACY='\|[[:space:]]*grep([[:space:]]+-[A-Za-z-]+)*[[:space:]]+(-[A-Za-z]*q|--quiet)'

# Every tracked script with a shebang. Whole-line comments are dropped so that
# prose describing the trap -- this file, ci.yml -- is not itself a violation.
# Inline code is NOT comment-stripped: `sed 's/#.*//'` is real code here, and
# stripping it is how a first draft of this scanner missed four live sites.
if files=$(git ls-files 2>/dev/null) && [ -n "$files" ]; then :; else
    files=$(find . -type f -name '*.sh' | sed 's|^\./||')
fi

scanned=0
offenders=""
while IFS= read -r f; do
    [ -f "$f" ] || continue
    grep -q '#!' <<<"$(head -c2 "$f")" || continue
    grep -q 'pipefail' "$f" || continue
    scanned=$((scanned+1))
    hits=$(grep -nvE '^[[:space:]]*#' "$f" | grep -E "$RACY" || true)
    [ -n "$hits" ] && offenders="$offenders$(printf '%s\n' "$hits" | sed "s|^|$f:|")
"
done <<<"$files"

[ "$scanned" -ge 20 ] \
    && ok "scanned $scanned pipefail scripts (the scan is not vacuous)" \
    || bad "scanned only $scanned pipefail scripts - the file list is broken"

if [ -z "${offenders//[[:space:]]/}" ]; then
    ok "no pipeline ends in a quitting grep -q"
else
    bad "a pipeline ends in a quitting grep -q - buffer it instead"
    printf '%s' "$offenders" | sed '/^$/d; s|^|        |'
    echo "        fix: out=\$(producer); grep -q PAT <<<\"\$out\""
fi

# The scanner must actually be able to see a violation. Without this, a broken
# regex would report a clean repo and the guard would be worse than nothing.
probe=$(mktemp); trap 'rm -f "$probe"' EXIT
PIPE='|'
printf '#!/usr/bin/env bash\nset -uo pipefail\n%s\n' \
    "sed 's/#.*//' somefile ${PIPE} grep -qE 'trap .* QUIT\$' && echo yes || echo no" \
    > "$probe"
grep -qE "$RACY" <<<"$(grep -nvE '^[[:space:]]*#' "$probe")" \
    && ok "the scanner detects a known-bad pipeline" \
    || bad "the scanner does NOT detect a known-bad pipeline - the regex is broken"

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ]
