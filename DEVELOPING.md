# Developing

Technical notes for building, testing and releasing. For what the project is
and how to install it, see [`README.md`](README.md).

## Dependencies

Most pieces are independent. Two groups are not:

```
thor-aynkey  ──> /data/adb/thor-aynkey/action.sh ──> thor-screenpanel (APK)
                                                 └─> thor-screenblank
thor-screenpanel ──> screenblank + screenmove

thor-appprofiles (APK) ──> thor-appwatch ──> thor-fanctl
```

- **`thor-screenpanel` needs three things present**: `screenblank`,
  `screenmove` and `aynkeyd`. Its `install.sh` checks for all three and
  refuses to proceed if any is missing.
- **`thor-screenpanel` has no Magisk module of its own.** The APK installs
  with `adb install`, and its `install.sh` additionally places `action.sh` into
  `/data/adb/thor-aynkey/`. That script is the seam between the button daemon
  and the panel — detection and meaning are kept separable on purpose, and two
  Magisk modules writing the same `/system` path would conflict.
- Each tile requires **its own module**, and nothing else. Each tile's README
  lists the exact CLI commands it calls.
- **`thor-appwatch` needs `thor-fanctl`** and refuses to install without it,
  because the fan tier is the only thing it applies. `thor-appprofiles` in turn
  needs `thor-appwatch`. Unusually, the two also share a **file format** — the
  profile table — not just a set of commands, so that format is part of the
  contract between them.

### Version skew is the thing to watch

A tile calling a CLI command its installed module does not have fails at the
tap, not at install time. This has already bitten once: a `screenblank
litscreen` subcommand appeared and was removed inside a single session.

Every tile README has a **Version compatibility** section listing the exact
commands that tile invokes. Keep a module and its APK at matching versions, and
re-read that section before mixing versions deliberately.

## Building and testing

Every component has a `build.sh` that **runs its tests first and refuses to
package if they fail.** All tests run on the host — no device attached, no
root, no emulator.

```sh
cd thor-fanctl && ./build.sh
```

Measured 2026-10-29: **890 host assertions across 24 suites, all passing** —
561 in shell test scripts and 329 in Java (`thor-screenpanel` 95,
`thor-appprofiles` 60, `thor-stickfx-tile` 59, `thor-screenmove-tile` 59,
`thor-fan-tile` 56).

Counted by running each suite, not by addition: `tests/test_tile_root_timing.sh`
is shared, so all three tile builds run it and a naive sum triple-counts its 6
assertions.

Two components additionally run checks **on the device itself**, because the
host suite runs under bash while the modules run under Android's mksh:
`fanctl selftest` and `appwatch selftest`.

The Java tests need only a JDK and, for the tile components, an `android.jar`
to compile against. Packaging an APK additionally needs `aapt2`, `d8`,
`apksigner` and `zipalign` from the Android build-tools.

## CI

Two workflows in `.github/workflows/`, so releases do not depend on anyone's
laptop.

**`ci.yml`** — every push to `main` and every pull request. Installs JDK 21 plus
`build-tools;37.0.0` and `platforms;android-37.0`, then runs every component's
`build.sh`. It uses **no secrets**, so it works on forks: the APKs it builds are
signed with the throwaway dev key `build.sh` generates on demand, and are
deliberately not installable as upgrades over a release build. Each APK's
certificate is printed, so which key was used is never ambiguous.

**`release.yml`** — on a `v*` tag. Same build, signed with the project release
key, attached to a GitHub Release with a `SHA256SUMS` over every file.

The release workflow pins the expected signing certificate and fails if any APK
does not carry it, and asserts that exactly five APKs were produced. The
dev-key fallback is silent by design, which makes a mis-signed release the
failure mode worth guarding; a component dropping out of the build is caught by
the same check.

Three components — `thor-sdcard-fsck`, `thor-wifi-kiwi-fw` and
`thor-wifi-no6g` — have no `build.sh`. They are plain file overlays with no
packaging step, so CI skips them and they produce no release artifact.
Installing those is still manual.

## Conventions

- Shell: `set -uo pipefail`, `</dev/null` on every `adb` call, and **explicit
  verification after every operation** rather than trusting an exit code.
- **Resolve input and sysfs nodes by name, never by index.** Device indices
  move between boots — `gpio-keys` was `event0`, `event3`, `event0`, `event2`
  across four boots in one session. There are tests asserting no literal index
  appears.
- **Never write a hardware node without reading it first** and recording the
  original. No module here writes a thermal or battery node, and there are
  tests asserting that mechanically.
- Every module keeps a kill switch. Several need an explicit `revert` as well
  as `disable`, because a framework setting outlives the module that applied
  it — each README says so where it applies.
- Tests are written against **behaviour, not text**, wherever possible: a
  comment mentioning the right number will satisfy a grep even when the
  constant is wrong.

## Install mechanics worth knowing

- **Magisk stages module updates into `/data/adb/modules_update/` and swaps
  them at the next boot.** Reinstalling a zip does *not* change the running
  `/system/bin` copy. Verify with `sha256sum` against the source before
  concluding a fix is live.
- **APKs target `targetSdk=37`** (Android 17). Play Protect on this device
  rejects installs targeting anything older with
  `INSTALL_FAILED_VERIFICATION_FAILURE`.
- All five APKs are signed with one release key. An older build signed with the
  per-app development keys **cannot be upgraded over** — Android refuses a
  signature change, so it must be uninstalled first.

## Research notes

These features were rebuilt by observing the stock ROM's behaviour on hardware
the author owns. The research notes behind them are **not published**: they
quote decompiled class and method names from proprietary AYN system APKs, so
they are kept private, and the code here is written against documented
behaviour rather than copied from them. Component READMEs therefore describe
*what* each piece does and omit citations to that material.

## Releasing

One release per component, each with its own tag:

```
thor-<component>-v<version>

thor-fan-tile-v0.5.0     builds thor-fan-tile, releases its APK
thor-stickfx-v0.5.0      builds thor-stickfx, releases its module zip
```

`release.yml` parses the component out of the tag and **refuses to build if the
tag version disagrees with what the component declares** — `VERSION` in an app's
`build.sh`, or `version=` in a module's `module.prop`. Publishing an artifact
whose filename and metadata contradict its tag is exactly what would confuse
Obtainium, so it fails instead.

Per-component tags rather than one combined release, so each app has its own
version stream and Obtainium only offers an update when that app actually
changed.

### App versioning

Each app's `build.sh` carries a single `VERSION`, and derives `versionCode` from
it arithmetically (`0.5.0 -> 500`) so the two cannot drift. Both are passed to
`aapt2 link`, and the version lands in the filename too.

This matters more than it looks: before 2026-10-29 **no APK declared a version
at all**. Android treats a missing `versionCode` as `0`, so every build was
version 0 — it would not recognise a newer build as an upgrade, and Obtainium
had nothing to compare. Anything that bumps a version must change `VERSION`
only; `versionCode` follows.

`versionCode` must only ever increase. Android refuses to install an APK whose
code is lower than the installed one.

### Components that cannot be released

`thor-sdcard-fsck`, `thor-wifi-kiwi-fw` and `thor-wifi-no6g` have no `build.sh`,
so a tag naming one of them is rejected. They are plain file overlays with no
packaging step; installing them is manual.

## Magisk 31 removed the su prompt — and what that cost us

### What we believed, and why it was wrong

Up to v0.5.0 all three tiles called their CLI from `onStartListening()`, so
they asked for root **as soon as Quick Settings opened**. Requests came back
`su: request rejected (<uid>)` and the user never saw a prompt.

The diagnosis written up on 2026-10-29 was that Magisk's prompt cannot take
focus over the open Quick Settings panel, so a request made from there is
rejected rather than prompted, and that moving the first request to `onClick`
— a foreground action — would let the prompt appear.

**That was wrong, and the hardware test is what proved it.** Verified
2026-10-30 on LineageOS 24 / Android 17, Magisk **31.1**: with the policy rows
for uids 10168 / 10179 / 10182 deleted, tapping a tile produced
`su: request rejected (10182)` *instantly* — same millisecond as the tap — and
no prompt. Moving the request to a tap changed nothing, because the problem was
never *when* the request was made.

### What is actually happening

**Magisk 31.0 removed the interactive su request entirely** and changed the
default policy from Query to Deny. Superuser access is now granted *ahead of
time* from the Superuser tab in the Magisk app. The stated reason is that
intercepting a root call in the daemon and launching a blocking UI prompt is
fundamentally incompatible with HSUM, particularly for non-app uids like
`adb shell`.

See `topjohnwu/Magisk` PR #10139, "Remove interactive su request".

This project's notes recorded Magisk **30.7**, which still prompted. The device
was updated to 31.1 somewhere between the install that worked and the one that
did not — so the apparent regression was a Magisk behaviour change, not a tile
defect, and no amount of code could have brought the prompt back.

### The Superuser tab lists the policy table, nothing more

Confirmed by screenshot 2026-10-30. With rows present for 2000, 10145, 10180
and 10183, the tab showed exactly Shell, Fan profiles, Solid Explorer and Thor
Screen Panel — and nothing for the three tiles whose rows had been deleted.

An app with no policy row simply **does not appear**, and since nothing prompts
any more, there is no event that would create one. The way in is the **"+"
button** at the bottom right of the Superuser tab, which adds an app ahead of
time. That is the supported path on 31.x, not a workaround.

This also explains the original 2026-10-29 symptom completely: the signing-key
change forced a reinstall, a reinstall assigns a new uid, the new uid had no
policy row, and on Magisk 31 nothing ever asks for one.

### What we changed anyway, and why it still earns its place

`onStartListening()` no longer touches the CLI in any of the three tiles. That
is worth keeping on its own merits, independent of the prompt story:

- it stops **three `su` process spawns every time the shade opens**, which is
  the "process spawns on a phone are not free" lesson applied to a path that
  runs many times a day;
- when root is not granted it stops writing three rejection lines to logcat per
  shade open;
- the tile renders immediately instead of after a `su` round trip.

`thor-fan-tile` and `thor-stickfx-tile` render the shade from `ModeStore`, a
`SharedPreferences` copy of the last mode the tile observed under root. Before
any grant they show "tap to set up" in `STATE_INACTIVE`; `STATE_UNAVAILABLE`
would be fatal here, because an unavailable tile receives no `onClick()` and
`onClick()` is the only place root is requested.

A refused probe **clears** the cache rather than leaving a label claiming a
mode that is no longer applied. That path was exercised on hardware during the
2026-10-30 test: the refused tap left an empty prefs file rather than a stale
mode.

`thor-screenmove-tile` caches nothing. Its subtitle names a *direction*, which
depends on which display is focused and what each is showing right now, so a
remembered direction would be wrong as often as right — and wrong in a way that
names a specific action. It shows the verb, "Switch screens", until tapped.

The cost is a label that can lag: the AYN panel drives the same `fanctl` and
`stickfx` binaries, so a change made there leaves a tile's label behind until
the tile is next tapped, which re-reads under root and corrects it.

### The lesson

The 2026-10-29 diagnosis was confident, internally consistent, and fitted every
piece of evidence then available — rejections in logcat, no policy rows, and a
user who remembered the prompt working on first install. It was still wrong,
because it never checked the Magisk version against what Magisk currently does.

**When a tool's behaviour changes under you, no amount of reasoning about your
own code will find it.** The brief said to verify on hardware by revoking the
policy rather than by reasoning about it, and that instruction is the only
reason this was caught before shipping a fix for the wrong problem.

### The guard

`tests/test_tile_root_timing.sh` — the first component-spanning test in the
repo, hence the new top-level `tests/` directory — fails the build if
`onStartListening()` can reach the CLI client in any of the three tiles. All
three `build.sh` scripts run it before their own unit tests.

It checks **reachability, not a literal string**: it strips comments, collects
each method body, and follows calls between them, because the likeliest
regression is `onStartListening()` calling a helper that calls the client
rather than calling it directly. Both shapes were reintroduced deliberately and
both failed the build. It also asserts the mirror image — that `onClick()`
*does* reach the client — since a tile that never asks for root at all would
otherwise satisfy it.

Two bugs in that script, both found by it passing when it should not have:
annotations may share the declaration line (`thor-screenmove-tile` writes
`@Override public void onClick() {` on one line, and a pattern anchored on the
modifier collected no body at all, which reads as clean); and an anonymous
`Runnable`'s `run()` is not a new method — every tile does its CLI work inside
one, so treating it as a separate method breaks the reachability trail.
