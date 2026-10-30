# thor-fan-tile

A Quick Settings tile for `fanctl`: tap to open a three-way picker and switch
the fan tier without a terminal.

Requires the **`thor-fanctl` Magisk module v0.5.0 or newer** (which installs
`/system/bin/fanctl`) and root. The tile is only a front end — it holds no
permissions of its own and shells out to the CLI under `su`, the same shape as
`thor-screenmove-tile`.

## What it consists of

| file | role |
|---|---|
| `FanMode` | the three tiers, their CLI words and labels. Pure logic. |
| `TileRender` | which Quick Settings state to show, and what to cache. Pure logic. |
| `ModeStore` | the last tier seen under root, so the shade needs no `su`. |
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

| situation | state | label |
|---|---|---|
| never granted root | `INACTIVE` | `Fan: tap to set up` |
| module missing / root denied | `INACTIVE` | `Fan: no root` |
| installed, no tier chosen | `INACTIVE` | `Fan: Stock` |
| a tier applied | `ACTIVE` | `Fan: Quiet` etc. |
| change in flight | `UNAVAILABLE` | `Fan: Quiet` etc. |

## When it asks for root

**Only on tap.** Opening Quick Settings never requests `su`.

Quick Settings calls `onStartListening()` every time the shade opens, and
until v0.5.1 the tile read `fanctl get` from there so it could label itself.
That spawned a `su` process on every shade open — and wrote a rejection line to
logcat every time, on a device where the app had not been granted root.

So the shade is drawn from `ModeStore`, a `SharedPreferences` copy of the last
tier the tile saw, and the only `su` happens in `onClick()`. A refused tap
clears that copy rather than leaving a label claiming a tier that is no longer
applied.

**This does not make a permission prompt appear, and nothing can.** Magisk 31
removed the interactive su request; access is granted ahead of time in the
Magisk app's Superuser tab, with the **+** button. An app with no policy row
does not appear in that tab and nothing will add it for you. Verified on device
2026-10-30 on Magisk 31.1: with the policy row deleted, a tap produced
`su: request rejected` in the same millisecond, with no prompt. Earlier notes
in this repo assumed a tap could raise a prompt; that was true on Magisk 30.7
and is not true now.

The cost is a label that can lag. The AYN panel drives the same
`/system/bin/fanctl`, so changing the tier there leaves this tile's label
behind until it is next tapped, and a tap corrects it. One stale label for one
shade open is a much smaller defect than a tile that cannot obtain root at all.

`tests/test_tile_root_timing.sh`, one level up, fails the build if
`onStartListening()` can reach the CLI client again — directly or through a
helper. All three tiles share it.

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

The same rule now covers the cache: `TileRender.cachedState()` must not return
`UNAVAILABLE` for a tile that has never been granted root either. If it did,
the tile would receive no `onClick()` — and `onClick()` is now the *only* place
root is requested, so root would be permanently unobtainable. That assertion is
in the host tests.

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
adb install -r dist/thor-fan-tile-v*.apk
```

Then add **Fan** from the Quick Settings edit screen.

`targetSdk` is **37** (Android 17). Play Protect on this device blocks installs
targeting older releases with `INSTALL_FAILED_VERIFICATION_FAILURE`.
