# thor-features

Stock AYN gaming features, rebuilt for **LineageOS 24** on the **AYN Thor**
(`odin2thor`), as Magisk modules plus small Quick Settings apps.

The AYN Thor is a dual-screen Android handheld. Its stock ROM ships fan
control, joystick RGB, a dual-screen mode panel and a bindable hardware
button; LineageOS ships none of these, and has declined to add some of them.
From the device maintainer on
[LineageOS/issues#816](https://github.com/LineageOS/issues/issues/816):
"we will not be adding any sort of user facing fan control".

So the controls live outside the ROM. Everything here runs on an otherwise
stock LineageOS build with Magisk.

## What's here

Seven features. Most are a **Magisk module** (a root CLI) paired with an
**APK** that drives it — usually a Quick Settings tile, once a full app. The
module is the functional half, and in most cases it is fully usable on its
own, which is also the low-risk way to try one.

| Feature | Magisk module | APK | Module useful without the APK? |
|---|---|---|---|
| Fan control | `thor-fanctl` v0.2.0 | `thor-fan-tile` | **Yes** — full CLI |
| Joystick RGB | `thor-stickfx` v0.1.3 | `thor-stickfx-tile` | **Yes** — full CLI |
| Move apps between screens | `thor-screenmove` v0.1.0 | `thor-screenmove-tile` | **Yes** — full CLI |
| Screen on/off | `thor-screenblank` v0.1.0 | — | **Yes** — module only |
| AYN button binding | `thor-aynkey` v0.1.0 | — | Installs, but **inert** until an action script exists |
| The AYN button panel | — | `thor-screenpanel` | **APK only** — see below |
| Per-app fan profiles | `thor-appwatch` v0.1.0 | `thor-appprofiles` | CLI works, but the **app is the interface** |

And five standalone modules, with no APK and no counterpart:

| Module | What it does | State |
|---|---|---|
| `thor-wifi-kiwi-fw` v1.0 | Fixes 6 GHz WPA3-SAE by supplying the correct `kiwi` firmware | Working |
| `thor-sdcard-fsck` v2.0 | Makes large exFAT SD cards mountable again (`vold` 45 s fsck budget) | Working |
| `thor-display-density` v0.1.0 | Main-screen density presets Settings cannot reach | Working |
| `thor-wifi-no6g` v1.0 | Disables 6 GHz entirely | **Superseded** by `thor-wifi-kiwi-fw` |
| `thor-display-match` v0.2.1 | Stops portrait-only apps rotating the display | **Not recommended** — see its README |

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

## Install

Each component's README has its own install section; they are independent, so
install only what you want. In general:

```sh
cd <component>
./build.sh                                  # runs tests, then packages
# Magisk module:
#   flash dist/<name>-vX.Y.Z.zip in Magisk, then reboot
# APK:
adb install -r dist/<name>.apk
```

Then add the tile from the Quick Settings edit screen.

Two things that will waste your time otherwise:

- **Magisk stages module updates into `/data/adb/modules_update/` and swaps
  them at the next boot.** Reinstalling a zip does *not* change the running
  `/system/bin` copy. Verify with `md5sum` against the source before concluding
  a fix is live.
- **APKs target `targetSdk=37`** (Android 17). Play Protect on this device
  rejects installs targeting anything older with
  `INSTALL_FAILED_VERIFICATION_FAILURE`.

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

## Guides

- [`docs/ayn-button-panel.md`](docs/ayn-button-panel.md) — the AYN button and
  its mode panel: what the button does, what the panel offers, and how the
  three components fit together.

## Third-party files

Three files are not original to this project — a Qualcomm WLAN firmware blob, a
GPL-2.0 `exfatprogs` binary and a LineageOS XML test fixture. Their origins and
terms are set out in [`THIRD-PARTY.md`](THIRD-PARTY.md). Read it before
redistributing.

## Research notes

These features were rebuilt by observing the stock ROM's behaviour on hardware
the author owns. The research notes behind them are **not published**: they
quote decompiled class and method names from proprietary AYN system APKs, so
they are kept private, and the code here is written against documented
behaviour rather than copied from them. Component READMEs therefore describe
*what* each piece does and omit citations to that material.

## Licence

**Apache-2.0** — see [`LICENSE`](LICENSE).

Chosen to match LineageOS, which this project extends and from which several
files derive. Five third-party files keep their own terms and are itemised in
[`THIRD-PARTY.md`](THIRD-PARTY.md): one Qualcomm firmware blob, one GPL-2.0
binary built here from upstream source, and three Apache-2.0 files from
LineageOS.

## Status

Every feature in the first table is installed and working on the author's
device. This is a personal project for one device on one ROM; it is not
affiliated with AYN or with LineageOS.
