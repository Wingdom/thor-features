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

As of this release: **596 host assertions, all passing** — 349 in shell test
scripts and 247 in Java (`thor-appprofiles` 60, `thor-screenpanel` 46,
`thor-screenmove-tile` 52, `thor-stickfx-tile` 46, `thor-fan-tile` 43).

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
