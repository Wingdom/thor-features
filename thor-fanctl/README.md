# fanctl — fan profiles for the AYN Thor on LineageOS

A Magisk module that changes fan behaviour by **adjusting thermal trip points**,
never by overriding `pwm1` or `cur_state`. The `step_wise` governor stays in
charge, every trip remains present, and thermal protection is never removed —
only rescheduled.

## Requires

Magisk and root. Nothing else.

Optionally paired with **`thor-fan-tile`**, a Quick Settings tile. The module is
fully usable without it — the CLI is the whole feature.

## Version compatibility

`thor-fan-tile` depends on exactly these:

```
fanctl get
fanctl quiet | balanced | performance
```

`fanctl get` is also the tile's presence check: a non-zero exit is read as
"module not installed or root denied". Removing or renaming `get` makes the
tile look permanently inactive.

## Why trip points

The fan is a thermal cooling device (`type=pwm-fan`, `max_state 8`) driven by
the governor from five zones. **Always resolve it by type, never by index** —
`cooling_device*` numbering follows driver probe order and shifts across
boots (it was 34 on 2026-10-20 and 35 on 2026-10-21, same build). Writing `pwm1` directly
is overwritten at the next evaluation. Moving a trip changes the governor's
*input*, which is both effective and safe.

The specific defect being fixed: `usb-therm` trips at **43 °C → fan state 6-7**
(pwm 120-150), while the CPU ladder only reaches state 6-7 at **80 °C**. Those
values are inherited verbatim from Qualcomm's `kalama-sg-hhg` reference design,
where they drove a 0-100 `fancontroller` at 80/100.

## Modes

| Mode | usb-therm | CPU/GPU ladder |
|---|---|---|
| `quiet` | 43/45 °C -> **60/62 °C** | untouched (stock) |
| `default` | restored from recorded originals | restored |
| `performance` | 43/45 °C -> 60/62 °C | shifted **-10 °C** (ramps sooner) |

```
fanctl status | quiet | default | performance [--dry-run]
fanctl get | json | selftest | discover | capture [--force]
```

## Safety design

- Trips are **discovered**, not hardcoded: the script walks every zone and only
  touches trips bound to a cooling device whose `type` is `pwm-fan`. It adapts
  if Lineage renumbers zones.
- **Invariants** enforced in `plan_for_mode()` and unit-tested: never raise a
  CPU/GPU fan trip, never move `usb-therm` outside `[35000, 70000]`, never
  switch a zone to `user_space`, never write `pwm1`/`cur_state`.
- Plans are computed from **recorded originals**, never from current values, so
  repeated applies cannot drift.
- Every write is **verified by read-back**, not by exit code.
- `customize.sh` aborts installation on non-Thor hardware or if no pwm-fan
  cooling device / writable fan trip exists.
- `service.sh` runs `capture --force` at boot *before* applying. At boot the
  kernel has just parsed the device tree, so trips are guaranteed stock — the
  only moment originals can be recorded with certainty. This makes the stored
  originals self-healing.

## Two bugs found during bring-up

Both were caught by verification rather than assumption, and both now have
regression guards in `tests/test_module.sh`.

**1. `selftest` took over 14 minutes.** It called `discover()` twice per trip
per mode — roughly 192 full scans of every thermal zone, each spawning a process
per read. The churn heated the SoC enough to spin the fan up by itself. Fixed by
snapshotting discovery once and doing each check as a single `awk` pass:
**14 minutes -> 5 seconds**.

**2. The fan latched at state 6 after applying.** These zones have
`polling-delay = <0>` in the device tree — there is no `polling_delay` sysfs
attribute, and they only re-evaluate on a sensor threshold event. A fan state
latched *before* a trip change therefore persisted indefinitely: observed sitting
at state 6 / pwm 120 for minutes with every zone at ~40 °C. Fixed with
`settle()`, which re-writes one trip per affected zone after applying to force
re-evaluation. A single nudge took the fan 6 -> 0.

## Testing

```
./build.sh          # runs both suites, refuses to package on failure
./tests/test_plan.sh    # 15 tests, pure logic, no device
./tests/test_module.sh  # 31 tests, module structure + safety guards
adb shell su -c 'fanctl selftest'   # 9 checks, on-device, read-only
```

Verified on device (LineageOS `24.0-20261019`, kernel 5.15.220):

- apply `quiet`: 2 trips changed, 30 untouched, all read back correct
- revert `default`: 32 trips checked, 0 mismatches
- survives reboot: `service.sh` re-captures originals and re-applies the mode
- **kill switch**: `touch /data/adb/modules/thor-fanctl/disable` + reboot ->
  `fanctl` gone from PATH, every trip back to stock (43000/45000, 55000)
- idle after fix: state 0 / pwm 0 at ~42 °C, where it previously latched at 6

**Not yet verified:** that `quiet` prevents a trip that would otherwise fire in
real use. Through all testing `usb-therm` stayed around 40-42 °C and never
crossed the stock 43 °C threshold on its own. The mechanism is proven; the
day-to-day benefit needs a real charge-plus-game session to confirm.
`performance` has been dry-run and unit-tested but **never applied to hardware**.

## Contract for the app layer

The QS tile should shell out through root rather than touching sysfs:

| Call | Purpose |
|---|---|
| `fanctl get` | current mode, one word — cheap enough for a tile refresh |
| `fanctl json` | machine-readable status: mode, fan state, pwm, every trip |
| `fanctl quiet\|default\|performance` | apply; exit status is meaningful |
| `fanctl selftest` | read-only health check |

`fanctl` installs to `/system/bin`, so `su -c 'fanctl quiet'` works from any
app with root. Mode persists in `/data/adb/thor-fanctl/mode` and is re-applied
at boot, so the tile only needs to reflect state, not maintain it.
