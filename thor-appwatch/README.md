# thor-appwatch

Applies per-app settings when an app comes to the foreground, and restores the
baseline when it leaves.

Only the **fan** key is implemented: a profiled app raises the fan tier above
whatever `thor-fanctl`'s baseline is, and the tier drops back when you leave
that app. The watcher and the table are deliberately general, because the same
foreground-app signal is what any other per-app feature would need.

```
appwatch status      daemon, master switch, baseline, applied tier, override count
appwatch enable      per-app profiles on
appwatch disable     off, restoring the baseline immediately
appwatch start|stop  daemon lifecycle; stop restores the baseline
appwatch selftest    read-only checks, under the device's own shell
appwatch daemon      the watch loop itself (service.sh calls this)
```

The user interface for this module is the **`thor-appprofiles`** app. The CLI
above exists so the app has something to drive and so the module can be
inspected; it is not where profiles are meant to be edited.

## Requires

- Magisk and root.
- **`thor-fanctl`.** `customize.sh` refuses to install without
  `/system/bin/fanctl`, because the fan tier is the only thing this module
  applies — without it the table would record tiers nothing could act on.
- **A fan tier selected globally.** If `fanctl get` reports anything that is
  not `quiet`, `balanced` or `performance`, this module does not engage at
  all. See *The baseline* below.

`thor-fan-tile` is **not** required. The baseline lives in `thor-fanctl`'s own
state and the tile is only one way to set it.

## Version compatibility

`thor-appprofiles` drives this CLI and depends on exactly these:

```
appwatch status      (parsed: the `enabled:`, `daemon:` and `fanctl:` fields)
appwatch enable
appwatch disable
```

It also reads and writes the table directly at
`/data/adb/thor-appwatch/profiles`, so **the table format is part of the
contract**, not an implementation detail.

This module calls `fanctl get` and `fanctl quiet|balanced|performance`. It
never calls `fanctl default`, and there is a test asserting so.

## The table

`/data/adb/thor-appwatch/profiles` — one line per package per key, tab
separated:

```
com.example.game	fan	performance
com.example.emu	fan	balanced
```

Three fixed columns, so a key lookup is a single `awk` pass with no inner
parsing. Adding a future per-app feature is new *lines*, never a format
change, and **unknown keys and unknown values are ignored rather than treated
as errors** — an older daemon tolerates a newer app's file.

## How the tier is chosen

```
effective tier = max( baseline, tier(top screen app), tier(bottom screen app) )
```

The three tiers are ordered by how much cooling they add
(`quiet` < `balanced` < `performance`), so a maximum is well defined and an
ambiguous case errs toward *more* cooling.

**Both displays count.** You can turn the top screen off and play an emulator
on the bottom panel, so an app on either screen can drive the fan. Resolving by
"whichever resumed most recently" was rejected: tapping an unprofiled app on
the bottom screen would drop the fan while a game was still running on top.

## The baseline

The tier in force when no profiled app is in front. It is **whatever you set
globally** — with the Fan tile or `fanctl` — not stock behaviour.
`fanctl default` restores the stock trip points and undoes the `usb-therm` fix
that module exists for, so this one never calls it. If no real tier is
selected, per-app profiles simply do not engage.

The baseline is **recorded when the first override is applied and restored
from that record**, never recomputed. While an override is active `fanctl get`
reports the override, so reading it back later would promote a game's tier to
the new baseline and ratchet upward with no way down.

Changing the baseline while an override is live updates the record rather than
the live tier, so your choice is not discarded when you quit the app.

## How it watches

One long-lived `logcat` pipe on the framework's event log. There is no polling
and no timer: process spawns are expensive enough on this device to distort
what they measure.

**The event is only a trigger.** The authoritative foreground comes from
`dumpsys activity activities`, read per display. The event payload is not
usable for this — the launcher emits resume events carrying the *wrong display
id* for an activity on the other screen, and trusting them made the daemon
apply a tier, revert it and re-apply it on a single app launch.

Two things the dumpsys parser has to get right, both covered by tests against
real captured output: display sections are **not** in id order (the focused
display is listed first), and task lines carry their own component, so only
`topResumedActivity` is consulted.

A locked or sleeping device has no foreground at all, which resolves to the
baseline — the fan does not stay pinned at a game's tier while the device sits
in your pocket.

## Safety

- **This module writes no hardware node.** Every change is `fanctl <tier>`,
  which keeps the `step_wise` governor in charge and leaves every trip point
  present. There are tests asserting nothing under `/sys` is written and that
  no thermal path appears anywhere in the module.
- Repeated applies cannot drift: `fanctl` plans from recorded originals.
- A tier is applied only when it differs from what is already applied.
- The daemon restores the baseline on `stop`, on `disable`, and on uninstall.
- A transient `fanctl` failure is logged and the daemon keeps running rather
  than dying silently.
- Starting is refused when another daemon is already running: two writers on
  one node is a race decided by timing.

### Revert is not optional

**`disable` + reboot does not undo the applied tier.** The tier is persisted by
*`fanctl`*, in `fanctl`'s own state, and re-applied at every boot. Disabling
this module just removes the thing that would have put it back — leaving the
fan at whatever a game last asked for, with nothing installed to explain why.
Verified on hardware.

So, before disabling:

```sh
su -c 'appwatch disable'      # restores the baseline immediately
```

**Uninstalling does it for you.** `uninstall.sh` restores the recorded baseline
and removes all state. It cannot call `fanctl` while doing so — Magisk runs
uninstall scripts before any module's `/system` is mounted — so it writes the
baseline into `fanctl`'s own persisted mode and lets `fanctl` apply it later in
the same boot.

## Install

```sh
./build.sh                    # runs 117 host tests, then packages
```

Flash `dist/thor-appwatch-v0.1.0.zip` in Magisk and reboot. Then install the
`thor-appprofiles` app and add some profiles — until the table has something
in it, this module changes nothing.

**Magisk stages module updates and swaps them at the next boot**, so flashing a
zip does not change the running `/system/bin` copy until you reboot. Verify
with `md5sum` against the source rather than assuming a fix is live.

## Tests

```
./tests/test_logic.sh     62 - the parsers and the tier resolution
./tests/test_module.sh    55 - packaging, the install gate, the revert path
./build.sh                syncs, runs both, then zips
su -c 'appwatch selftest' 11 - read-only, on device, under the real shell
```

The host suite runs under bash; the daemon runs under the device's mksh, so
the logic is also exercised under every strict shell present on the build host
and by `selftest` on the device itself. Fixtures are real captured output, so a
change in a command's wording or a dump's shape breaks the tests — which is
the point, since both are parsed.
