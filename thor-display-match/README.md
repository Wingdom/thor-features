# thor-display-match

Makes the AYN Thor's display behave the way the stock AYN ROM did.

## Status: works, but not recommended

**This module is deliberately not installed on the author's device.** It is
published because it is still useful and because what it demonstrates is
reusable, not because you should reach for it first.

It did what it claimed: the overlay mounted, portrait-only apps stopped
rotating the display, and `revert` restored stock. But it was treating a
symptom. The real cause of the rotation problem turned out to be a stale
sensor-registry entry on `/mnt/vendor/persist` — a partition that survives both
flashing and a factory reset — shadowing the corrected entry in the vendor
image. The device maintainer's one-line fix resolved it properly:

```sh
rm -rf /mnt/vendor/persist/sensors/registry/*   # then reboot
```

Measured before and after, in an identical pose, the accelerometer went from
reporting "up" on +X to reporting it on +Y. **It was not a LineageOS bug**, and
once the sensor is correct this module is no longer needed for its original
purpose.

Two reasons it is still here:

1. It is the worked example of generating a `display_settings.xml` overlay from
   the ROM's own file plus the live display id, which is a pattern the
   dual-screen work reuses.
2. `ignoreOrientationRequest` on the primary display is independently useful if
   you simply never want apps rotating a landscape handheld, regardless of the
   sensor.

If you install it, read the revert note at the bottom first: `disable` alone
does **not** fully revert it.

## Requires

Magisk and root. Nothing else — no APK, no other module.

## Version compatibility

Nothing else in this repository calls `display-match`. The CLI is
`status | make-xml | primary-id | revert`.

**`revert` is not optional.** The framework persists the value into
`/data/system/display_settings.xml` on first boot, so `disable` alone does not
fully revert it. Run `display-match revert`, then reboot.

## What it does

Overlays one file — `/vendor/etc/display_settings.xml` — adding an
`ignoreOrientationRequest="true"` entry for the **primary** display.

LineageOS already ships that file for this device and already uses it to
configure the **secondary** (1240x1080) screen. It has no entry for the primary
screen, so a portrait-only app can rotate a landscape-only handheld.

The XML is generated at install time from the ROM's own copy plus the live
display id read from `dumpsys`. Nothing is hardcoded and the ROM's existing
secondary-display entry is preserved verbatim.

## What it does NOT do

**It does not fix the rotation bug.** The underlying fault is that the
accelerometer axes are not remapped to match SurfaceFlinger's 90-degree
rotation, so sensor-derived rotation is 90 degrees off. This module stops
*apps* from rotating the display; it does not change what the *sensor*
reports.

Note that the sensor fault itself has since been fixed — see **Status** above —
so the sensor-side workaround it was paired with (auto-rotate on, every angle
except 0 disabled) is no longer needed either.

**It does not touch display density.** That is a separate concern and lives in
its own module. For the record of why it cannot be done with `system.prop`:
the panel's real density is ~320 dpi and the composer reports it, so
`ro.sf.lcd_density=360` in `/vendor/build.prop` is already being ignored.

## Commands

    display-match status            # module state, overlay, and effective values
    display-match revert            # remove the baked /data override
    display-match primary-id        # unique id of the primary display
    display-match make-xml <f> <id> # pure transform, used at install time

## Kill switch

**`disable` alone is not enough.** Proven on device 2026-10-22:

    touch disable + reboot
      vendor file     -> back to ROM stock        (overlay unmounted correctly)
      effective value -> ignoreOrientationRequest STILL true

The framework copies the vendor file's value into
`/data/system/display_settings.xml` on the first boot after install, and that
file outlives the module. So a complete revert is:

    su -c '/system/bin/display-match revert'
    touch /data/adb/modules/thor-display-match/disable
    reboot

Verified: effective value `false`, vendor file at ROM stock, override gone.
Uninstalling does the same thing automatically — `uninstall.sh` removes the
override.

## Upgrades

The first install saves a pristine copy of the ROM's file to
`/data/adb/thor-display-match/stock.xml`. This is not optional bookkeeping: once
the module is mounted, `/vendor/etc/display_settings.xml` **is** our overlay, so
a later install would otherwise read its own output and refuse. Later installs
resolve against the saved copy.

If the ROM changes, the saved copy is stale and the real file is unreachable
from under our own mount, so install refuses and tells you to uninstall, reboot
and install again.

## Verified on device

LineageOS `24.0-20261019-NIGHTLY-odin2thor`, Magisk 30.7, 2026-10-22, v0.2.1:

- install generates the overlay from the ROM's live file and the live display
  id (`local:4630946441858561667`), preserving the secondary entry verbatim;
- after reboot the overlay is mounted and `ignoreOrientationRequest` is `true`;
- **the vendor-file mechanism genuinely works**: `/data/system/display_settings.xml`
  was deleted before the test reboot and came back containing exactly our
  entry, byte-identical to the pre-module copy — i.e. the framework read the
  vendor file and applied it;
- launching a portrait-only app leaves the display at 1920x1080;
- `disable` unmounts the overlay but does **not** revert the effective value
  (see Kill switch);
- `display-match revert` + `disable` + reboot does fully revert.

- removal via `touch remove` + reboot restores everything: module dir gone,
  state gone, `/data` override removed by `uninstall.sh`, effective value
  `false`, and `/vendor/etc/display_settings.xml` byte-identical to the copy
  captured before any module existed (`aa84be72...`);
- a clean reinstall then saves the pristine copy and applies correctly.

Not tested: behaviour across an actual ROM update (the refusal path is covered
by host tests only).

## Caveat: the /data override wins

`/data/system/display_settings.xml` overrides the vendor file. If you have
previously run `wm set-ignore-orientation-request`, that file exists and will
mask this module — with the same value, so the behaviour looks right either
way, which makes it easy to mis-attribute. To test the module honestly, remove
it:

    su -c 'rm /data/system/display_settings.xml' && reboot

`customize.sh` warns at install time if the file is present.

## Caveat: ROM updates

The overlay is generated from the ROM's file at install time. If a nightly
changes that file, this module will mask the new version. `service.sh` compares
`ro.build.date.utc` against the value recorded at install and logs a warning to
`/data/adb/thor-display-match/boot.log` when they differ. Reinstall to
regenerate.

## Tests

    ./build.sh          # runs both suites, then packages

`tests/test_logic.sh` covers the XML transform on the host — insertion,
idempotency, preservation of the ROM's entries, well-formedness, and the
failure modes. `tests/test_module.sh` runs `customize.sh`, `uninstall.sh`
against stubbed `getprop`/`dumpsys`/`wm`, checking behaviour rather than text.
