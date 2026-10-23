# thor-stickfx-tile

Quick Settings tile for the joystick LED effect layer provided by the
`thor-stickfx` Magisk module. Sibling of `thor-fan-tile` and built the same
way: aapt2 + javac + d8, no Gradle.

This is the Quick Settings tile for joystick RGB, which AynParts does not
provide. It is a front end for `thor-stickfx`; see `thor-stickfx/README.md`.

## Requires

- The **`thor-stickfx` Magisk module v0.1.3 or newer** (which installs
  `/system/bin/stickfx`) and root.
- LineageOS's own **AynParts** RGB panel, for the colour picker the tile opens.

The tile is only a front end — it holds no permissions of its own and shells
out to the CLI under `su`.

## Version compatibility

The tile calls exactly these, and nothing else:

```
stickfx get
stickfx off | charge | breathe | usb
am start -n org.lineageos.settings.ayn/.lights.RGBLightingSettingsActivity
```

An older module missing a mode shows up as a tile that fails at the tap rather
than at install time. Keep the module and the tile at matching versions.

## What it does

Tapping opens a picker:

    Off        stickfx off      -- hands the LEDs back to AynParts
    Charge     stickfx charge   -- charge indication via kernel LED trigger
    Breathe    stickfx breathe
    RGB colours...              -- opens AynParts' RGB panel

The tile reads `stickfx get` and shows active whenever an effect is running.

`Off` is a real choice here, unlike `Stock` in the fan tile: `stickfx off`
restores the LED state recorded at boot rather than disabling a module.

The CLI's `usb` mode is deliberately **not** offered — it is a verification
mode for proving the trigger works without a charging battery. It stays in the
enum so the tile reports honestly if someone selects it from a shell.

## Why colour is a button and not a slider

Colour belongs to AynParts, which ships a full RGB panel. `thor-stickfx` writes
only `trigger`/`delay_on`/`delay_off` and leaves `multi_intensity` alone, so
duplicating a colour picker here would mean two writers on one node. The button
opens AynParts instead.

`RGBLightingSettingsActivity` is `exported=false`, so an ordinary app cannot
start it — and a background activity launch from a service is blocked on modern
Android regardless. It is opened with root `am start`, verified on device.

## Requirements

- the `thor-stickfx` Magisk module (provides `/system/bin/stickfx`);
- Superuser granted to `com.thorfeatures.stickfx`.

Without root the tile shows "Sticks: no root" and explains what is missing
rather than failing silently.

## Build target

`TARGET_SDK=37` (Android 17). **This matters:** Play Protect on this device
blocks installs of anything older with "Unsafe app blocked — built for an older
version of Android", surfacing as
`INSTALL_FAILED_VERIFICATION_FAILURE`. `thor-fan-tile` had the same problem and
was raised to 37 at the same time.

Compiled against `platforms/android-37.0/android.jar` with
`build-tools;37.0.0`, both installed 2026-10-22 — so the declared target and
the compile-time API now match the device exactly (`ro.build.version.sdk=37`,
no minor). Note the platform directory carries the minor: `android-37.0`, not
`android-37`.

### Build environment gotcha

`sdkmanager` warns "This version only understands SDK XML versions up to 3 but
... version 4 was encountered" and still exits 0 — so check that the package
directory actually appeared rather than trusting the exit code.

`JAVA_HOME` previously pointed at `/usr/lib/jvm/java-21-openjdk`, which does
not exist on this host, and `sdkmanager` refused to start. Corrected
2026-10-22 in `~/.zprofile` and `~/.bashrc` to
`/var/home/linuxbrew/.linuxbrew/opt/openjdk/libexec` — Homebrew's `opt`
symlink rather than the versioned Cellar path, so a JDK upgrade will not break
it again. No override is needed now.

## Tests

    ./build.sh      # runs the host tests first, refuses to package on failure

40 host tests, no device: mode parsing (case, whitespace, null, unknown),
picker membership (Off in, USB out), argv construction, failure fallbacks, the
RGB panel component string, and an assertion that no shell metacharacter can
reach the command line.

## Verified on device

LineageOS `24.0-20261019-NIGHTLY-odin2thor`, Magisk 30.7, 2026-10-22:

- installs at `targetSdk=37` (33 was blocked, 35 built but was an interim step);
- tile appears in the shade and reads active while mode is `charge`;
- picker shows `Charge` pre-selected, matching `stickfx get`;
- selecting `Breathe` -> `stickfx get` returns `breathe`;
- `RGB colours...` -> `org.lineageos.settings.ayn/.lights.RGBLightingSettingsActivity`
  becomes the resumed activity;
- restored to `charge` afterwards.

**The no-root path was tested** by setting the Magisk policy for uid 10145 to
deny (1) rather than deleting it, so `su` fails deterministically instead of
prompting. That test found a real bug — see below. After the fix: the tile
stays clickable, shows "Sticks: no root", and tapping raises the Root required
dialog. Grant restored to allow (2) afterwards.

Not tested: tile behaviour after a reboot.

## The bug the no-root test found

The first version rendered `Tile.STATE_UNAVAILABLE` when root was missing.
**SystemUI does not deliver `onClick()` to a tile in that state**, so the tile
sat dimmed and did nothing, and `showNoRoot()` was unreachable dead code — the
user would never learn why it was greyed out. Confirmed on device: tapping
produced no su request at all, while tapping after the fix produced one
immediately.

Fixed by rendering `STATE_INACTIVE` with the "no root" label instead. The
decision now lives in `TileRender.state()`, which is pure and host-tested, with
an explicit assertion that no-root never maps to UNAVAILABLE.
`STATE_UNAVAILABLE` is still used for the transient "applying" moment, where
being unclickable is correct.

`thor-fan-tile` had the identical flaw and got the same fix.
