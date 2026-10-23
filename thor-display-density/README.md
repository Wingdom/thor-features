# thor-display-density

Sets the AYN Thor's **main** screen density from a fixed four-entry list.
The secondary 1240x1080 display is never touched — every `wm` call is pinned
to `-d 0`, and there is a test that fails if one ever is not.

## Requires

Magisk and root. Nothing else — no APK, no other module.

## Version compatibility

Nothing else in this repository calls `display-density`. The CLI is
`status | native | 320 | 369 | 288 | revert | resolve`.

**`revert` is not optional.** A forced density is a framework setting that
outlives the module, so disabling or uninstalling alone does not undo it. Run
`display-density revert` first.

## The presets

Panel is 6.88in diagonal, 1920x1080, ~320 dpi physical.

| command | dpi | screen in dp | what it is |
|---|---|---|---|
| `display-density native` | — | 960 x 540 | panel default, no forced density |
| `display-density 320` | 320 | 960 x 540 | the panel's true density, forced |
| `display-density 369` | 369 | 833 x 468 | what stock AYN used, ~15% larger UI |
| `display-density 288` | 288 | 1067 x 600 | `sw600dp` — tablet layouts |

`native` and `320` put the same number on screen but by different means:
`native` clears `display_density_forced` entirely, `320` sets it. `native` is
the clean hand-back state.

**288 is the only one that changes behaviour rather than scale.** `sw600dp` is
the threshold at which Android switches many apps to tablet / two-pane
layouts. Everything else just resizes.

There is deliberately **no arbitrary-value option**. Off-list values are
refused before `wm` is called.

## Why a module at all

LineageOS already ships Settings -> Display -> Display size, which writes the
same `display_density_forced`. Its steps are computed as fractions of the
default (roughly 0.85x-1.3x), so from 320 it can reach about 272 / 352 / 384 /
416 — **it cannot reach 288, and it cannot reach exactly 369**. Those two are
the reason this exists.

The saved preset is re-applied at every boot, so the choice survives a ROM
update rather than depending on a setting the ROM may reset.

## Kill switch

A forced density is a framework setting and outlives the module, so
`touch disable` + reboot does **not** revert it — the same trap documented in
`../thor-display-match/README.md`. The explicit way back:

    display-density revert      # or: display-density native

Uninstalling does it automatically: `uninstall.sh` resets display 0 if a preset
was set, and removes the state.

## Verified on device

LineageOS `24.0-20261019-NIGHTLY-odin2thor`, Magisk 30.7, 2026-10-22:

- install applies nothing of its own; status reads `none (native)`;
- `display-density 400` is refused without calling `wm`;
- `display-density 288` -> display 0 `Override density: 288`,
  config `sw600dp w1067dp h600dp 288dpi`, display 1 still
  `Physical density: 320` with no override;
- after a reboot the preset is still applied, `service.sh` logging
  `applied: display 0 now 288`;
- 320 -> `sw540dp w960dp`, 369 -> `sw468dp w833dp`, both as calculated;
- the rotation module (`thor-display-match`) is unaffected throughout.

Not tested: an actual ROM update.

## Tests

    ./build.sh

`tests/test_logic.sh` covers preset resolution and runs every preset against a
stubbed `wm`, asserting that each invocation pins a display id and that none
writes to display 1. `tests/test_module.sh` executes `customize.sh`,
`service.sh` and `uninstall.sh` against stubs.
