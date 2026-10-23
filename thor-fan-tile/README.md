# thor-fan-tile

A Quick Settings tile for `fanctl`: tap to open a three-way picker and switch
the fan tier without a terminal.

Requires the **`thor-fanctl` Magisk module v0.2.0 or newer** (which installs
`/system/bin/fanctl`) and root. The tile is only a front end — it holds no
permissions of its own and shells out to the CLI under `su`, the same shape as
`thor-screenmove-tile`.

## What it consists of

| file | role |
|---|---|
| `FanMode` | the three tiers, their CLI words and labels. Pure logic. |
| `TileRender` | which Quick Settings state to show. Pure logic. |
| `FanctlClient` | builds the `su -c` argv and parses `fanctl` output. Runner is injectable. |
| `FanTileService` | the `TileService` itself — Android plumbing only. |

No Activity is declared. The service holds `BIND_QUICK_SETTINGS_TILE` so that
only the system can bind it.

## The three tiers, and the one that is missing

`Quiet`, `Balanced` and `Performance` differ only in how far the CPU/GPU fan
ladder is shifted down. All three apply the same `usb-therm` fix, which is the
reason the module exists.

**"Stock" is deliberately not offered.** Going back to LineageOS behaviour
means *disabling the Magisk module*, not picking a mode — offering it in the
picker would be offering the loud 43 °C `usb-therm` behaviour back. `FanMode`
still carries a `STOCK` value so the tile can report honestly when the module
is installed but no tier has been chosen yet.

## What the tile shows

| situation | state |
|---|---|
| module missing / root denied | `INACTIVE` |
| installed, no tier chosen | `INACTIVE` |
| a tier applied | `ACTIVE` |
| change in flight | `UNAVAILABLE` |

## Why it is never `STATE_UNAVAILABLE` at rest

SystemUI does not deliver `onClick()` to a tile in `STATE_UNAVAILABLE`. A tile
rendering that state when it cannot act becomes a dimmed square that does
nothing and never explains why, because the explanatory dialog is then
unreachable.

This was found on device with **this tile** on 2026-10-22: tapping an
unavailable tile produced no `su` request at all. So every resting state is
`INACTIVE`, keeping the tile clickable so the dialog can explain itself.
`STATE_UNAVAILABLE` is reserved for the transient "applying" moment, where
being unclickable is the point. `TileRender.state()` is host-tested, including
an assertion that it never returns `UNAVAILABLE` for a resting tile.

## Version compatibility

The tile calls exactly these, and nothing else:

```
fanctl get
fanctl quiet | balanced | performance
```

`fanctl get` is also how the tile decides whether the module is present at
all — a non-zero exit is read as "not installed or root denied", which is why
a missing binary degrades to a clickable `INACTIVE` tile rather than an error.

An older `thor-fanctl` that does not implement `get`, or is missing a tier,
therefore shows up as a permanently inactive tile rather than a crash. Keep
the module and the tile at matching versions.

## Safety

The tile holds **no Android permissions**. Its only capability comes from `su`,
which Magisk gates per app. Arguments passed to the CLI come from
`FanMode.wire()` or a fixed literal — never user input, never anything read
back off the device — and a test asserts no shell metacharacter can reach the
`su -c` string.

The tile never touches a thermal node itself. Everything it changes, it changes
by asking `fanctl`, which keeps `step_wise` in charge and leaves every trip
point present.

## Tests

```
bash build.sh     # runs 43 host tests, then packages; refuses to package on failure
```

43 tests across `FanMode` (wire words, tier ordering, the `STOCK` exclusion),
`TileRender` (state selection, including the never-unavailable-at-rest rule)
and `FanctlClient` (argv construction, output parsing and failure fallbacks,
through a fake runner).

`FanTileService` has no host tests: it is deliberately a thin shell over the
pure classes, and what remains is Android plumbing that only means anything on
a device.

## Install

```
bash build.sh
adb install -r dist/thor-fan-tile.apk
```

Then add **Fan** from the Quick Settings edit screen.

`targetSdk` is **37** (Android 17). Play Protect on this device blocks installs
targeting older releases with `INSTALL_FAILED_VERIFICATION_FAILURE`.
