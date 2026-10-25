# thor-stickfx

Charging indication and breathe effects for the AYN Thor's 8 joystick RGB LEDs
on LineageOS. **v0.1.4 — installed, charge path verified on hardware.**

## Version compatibility

`thor-stickfx-tile` depends on exactly these:

```
stickfx get
stickfx off | charge | breathe | usb
```

`stickfx get` doubles as the tile's presence check — a non-zero exit is read as
"not installed or root denied". The tile does not pass `--color`, `--trigger`
or `--force`; those are CLI-only.

## This is an add-on, not a replacement

LineageOS already ships `org.lineageos.settings.ayn` (**AynParts**) at
`/system_ext/app/AynParts/`, whose RGB panel owns joystick LED **colour**:
global / per-side / per-LED pickers with a brightness channel, restored at every
boot.

What it does **not** do: it never writes `trigger` at all, so the kernel's
`battery-charging`, `battery-full`, `usb-online` and `timer` LED triggers go
unused. There is also no QS tile. That gap is what this module fills.

**The two coexist because colour and brightness are separate channels:**

| Attribute | Owner |
|---|---|
| `multi_intensity` | **AynParts** — the colour |
| `brightness`, `trigger`, `delay_on`, `delay_off` | **stickfx** — the behaviour |

stickfx writes `multi_intensity` only under an explicit `--color` takeover,
which records the originals and is undone by `stickfx off`.

## Measurements behind the design

All verified on hardware, 2026-10-21, build `24.0-20261019-NIGHTLY`, on a single
LED with originals recorded and restored.

**1. A trigger drives `brightness` and never touches `multi_intensity`.**

```
multi_intensity="43 224 216", brightness=128   -> teal at ~50%
echo timer > trigger
  -> delay_on / delay_off APPEAR in the directory
  -> multi_intensity still "43 224 216"
  -> brightness sampled every 300ms (delay 500/500): 0 0 200 200 0 200 0 0
```

**2. Power-supply LED triggers are EDGE-driven, not level-driven.**

`usb/online` was `1` and `[usb-online]` was bound, yet `brightness` stayed `0`.
Confirmed in AYN's own kernel drop,
`drivers/power/supply/power_supply_leds.c`: `power_supply_update_leds()` is
called only from `power_supply_changed()`, and it fires `led_trigger_event()` on
a *transition*:

```c
case POWER_SUPPLY_STATUS_FULL:
    led_trigger_event(psy->charging_blink_full_solid_trig, LED_OFF);
    led_trigger_event(psy->charging_blink_full_solid_trig, LED_FULL);  /* solid */
case POWER_SUPPLY_STATUS_CHARGING:
    led_trigger_blink(psy->charging_blink_full_solid_trig, ...);       /* blink */
default:
    led_trigger_event(psy->charging_blink_full_solid_trig, LED_OFF);
```

So binding a trigger does **not** sample current state. Booting while plugged in
would leave the LED dark until the next unplug. `stickfx` therefore *seeds* the
current state itself and lets the kernel maintain it from the next event on.
`battery-charging-blink-full-solid` is the right trigger: blink while charging,
solid when full — straight from the source above.

**3. Brightness and triggers are mutually constrained — bind first, seed second,
never seed zero.**

Two kernel behaviours pull in opposite directions, and both were found only on
hardware:

```
led_trigger_set()   tears down the previous trigger with LED_OFF, so BINDING a
                    trigger resets brightness to 0 -- any seed written before
                    binding is wiped.
brightness_store()  calls led_trigger_remove() on LED_OFF, so writing a ZERO
                    brightness after binding UNBINDS the trigger.
```

Demonstrated:

```
echo battery-charging-blink-full-solid > trigger  -> [battery-charging-blink-full-solid]
echo 0 > brightness                               -> [none]        <-- unbound
echo battery-charging-blink-full-solid > trigger  -> bound again
echo 255 > brightness                             -> still bound
```

v0.1.0 seeded *before* binding. That looked fine with
`battery-charging-blink-full-solid`, because the kernel drives brightness itself
once a power-supply event fires — the wiped seed never showed. It failed
visibly the moment the trigger was switched to `battery-charging-or-full`, which
only sets brightness on an event: the LEDs sat bound but **dark**.

The only shape that satisfies both constraints is **bind, then write a non-zero
seed**; when the seed would be zero, write nothing, because binding already left
the LED off. Guarded by three regression tests.

One residual nuance: with the blink trigger, the seed can only say "on", so the
LEDs are briefly solid after arming and start blinking at the next power-supply
event (seconds, while charging). Strictly better than dark.

**3b. Historical note — writing `brightness=0` UNBINDS the trigger.**

```
echo battery-charging-blink-full-solid > trigger  -> [battery-charging-blink-full-solid]
echo 0 > brightness                               -> [none]           <-- unbound
echo battery-charging-blink-full-solid > trigger  -> bound again
echo 255 > brightness                             -> still bound
```

Upstream `brightness_store()` calls `led_trigger_remove()` on `LED_OFF`. The
first hardware run seeded *after* binding and silently reverted all 8 triggers
to `none`. **Order is now seed-then-bind**, with a regression guard.

**4. Trigger activation is asynchronous.** An immediate read-back after writing
`trigger` can show the old value or no bracketed entry at all, so verification
polls (bounded, 20 × 50 ms) — the same shape stock's own node writer used.

## A shell bug worth remembering

The first prototype reported **all 8 trigger writes as failed** while the
kernel file in fact read `[battery-charging-blink-full-solid]`.

The parser did `for t in $list`. Unquoted expansion performs **pathname
expansion** as well as word splitting — and `[battery-charging-blink-full-solid]`
is a valid glob character class. With cwd `/`, it expanded to the
single-character entry `d` (which is in that set), destroying the bracket before
any parsing happened. A simpler test string like `[battery-charging]` happened
not to match anything in `/`, which is exactly why the host tests passed.

Both parsers now use string manipulation only. Note also that `${x#*[}` is an
**invalid pattern** (unterminated bracket expression) and errors outright on
some shells; the bracket must be escaped. Both are covered by regression tests
that reproduce the original conditions — real trigger string, cwd full of
single-character files.

## Usage

```
stickfx status                        every LED's trigger, brightness, colour
stickfx charge                        bind battery-charging-or-full (default)
stickfx charge --color 2BE0D8         ... and take the colour channel (stock AYN teal)
stickfx breathe [ms]                  timer trigger, default 1200ms period
stickfx usb                           usb-online -- observable without a charging battery
stickfx off                           restore the originals recorded at boot
stickfx get | getcolor | selftest | discover | capture [--force]
```

If every LED colour is `0 0 0` — which is the default, since AynParts' own
`rgb_lighting_enable` defaults to `false` — a trigger would drive an invisible
LED. `stickfx charge` detects that and **refuses with instructions** rather than
appearing to work.

## Installing

Not yet installed. A fresh install is **inert**: `service.sh` applies nothing
until a mode is selected, so installing and rebooting changes no behaviour.

```
# flash dist/thor-stickfx-v0.1.3.zip in Magisk, reboot, then:
adb shell "su -c 'stickfx charge --color 2BE0D8'"

# kill switch
adb shell "su -c 'touch /data/adb/modules/thor-stickfx/disable'"   # then reboot
adb shell "su -c 'rm -rf /data/adb/modules/thor-stickfx'"          # then reboot
```

`service.sh` waits for `sys.boot_completed` **plus 8 s** before recording
originals, because AynParts' own `BootCompletedReceiver` writes these LEDs at
boot. Capturing earlier would record a pre-AynParts state and `stickfx off`
would restore something the user never chose.

## Tests

**114 host-side tests, no device required:**

- `tests/test_logic.sh` — 72. Colour parsing, breathe-period clamping,
  `is_black`, trigger-list parsing, `seed_for_status`, plus regression tests for
  the globbing bug and for helpers clobbering the caller's variables.
- `tests/test_module.sh` — 42. Module structure, install-time gating, uninstall
  restore, boot ordering, and regression guards for: hardcoded sysfs indices,
  word-splitting a trigger list, unescaped bracket patterns, seed-before-bind
  ordering, and the colour channel being written only by `set_color`/`restore`.

Plus `stickfx selftest` — 5 read-only on-device checks, including a real
colour-drift check that fails if the colour channel has moved without a
recorded takeover.

Every regression guard was verified by reintroducing the bug and watching it
fail. Two of them were broken when first written and passed vacuously:

- the sysfs-index guard in `thor-fanctl` scanned nothing, because `$HERE/..`
  expands to `…/tests/../x` and its own `*/tests/*` exclusion ate every path;
- the colour-channel guard matched shell redirects, but writes go through
  `wr()`, so it reported "0 write sites" and could never fail.

A guard that passes is not evidence until it has been seen to fail.

## Verified on hardware

Runtime prototype (before install): apply, status, selftest, breathe and the
full restore path, all over adb with originals recorded first.

Installed as a Magisk module and verified:

| Behaviour | Evidence |
|---|---|
| **Install-time gating** | `device: odin2thor (Thor)` / `stick LEDs found: 8` / `battery-charging-blink-full-solid: available` |
| **Module migration** | staged in `modules_update/`, moved into `modules/` on reboot; all three shipped files sha256-identical to the build |
| **Inert on first boot** | `boot.log`: `persisted mode: off` -> `nothing to apply`; all 8 LEDs stayed dark |
| **Charge indication fires** | observed blinking by eye, and measured: `brightness` sampled every 0.3 s at 99% `Charging` gave `255 0 255 255 0 255 255 0 255 ...` |
| **Seeding while charging** | `seeded brightness 255 for battery status 'Charging'` — the non-zero seed path, which could not be reached at 100% |
| **Colour takeover** | all 8 LEDs `multi_intensity = 43 224 216` (stock AYN `#2BE0D8`), `trigger` bound on all 8 |
| **Kill switch** | `disable` + reboot -> `stickfx` off PATH, overlay unmounted, all 8 LEDs back to the recorded originals (`b=0 multi="0 0 0" trig=none`) |
| **State survives disable** | `mode=charge`, `color=2BE0D8`, `original.tsv` intact across the disabled boot |

Boot re-apply, verified 2026-10-21 22:39 on v0.1.3:

```
--- boot Wed Oct 21 22:39:38 EDT 2026 (waited 9s for boot_completed) ---
recorded 8 LEDs
persisted mode: charge   colour: 2BE0D8
  colour '43 224 216' set on 8 LED(s)  (explicit --color takeover)
  trigger 'battery-charging-or-full' active on 8 LED(s)
  no seed needed for battery status 'Discharging' (LED off)
```

Worth reading closely, because it exercises the v0.1.2 fix on a path that was
never reachable by hand. Nine seconds after `boot_completed` the charger had not
settled, so the battery read `Discharging` and `seed_state()` correctly wrote
**nothing** — a zero write there would have unbound the trigger it had just set.
The power supply then settled, the kernel fired the event, and the trigger drove
brightness to 255 by itself. The LEDs came up solid teal with no further input.

The persisted trigger was honoured without `service.sh` passing it, via the
environment > persisted > default resolution.

v0.1.4, after reboot:

| Behaviour | Evidence |
|---|---|
| Module active | migrated out of `modules_update`, `stickfx` on PATH, `version=v0.1.4` |
| Boot re-apply | `boot.log` re-applied `charge` with the persisted `usb-online` trigger and seeded 255 from `usb online=1` |
| Selftest | 5/5, including that every LED advertises `usb-online` and the `--color` takeover is recorded |
| Stays lit through the charging-control hold | measured on the previous cycle with `usb-online` bound: hold at 85%, `chg_en=0`, **0 dark samples of 36** (against 42 of 278 with a battery-status trigger) |

### Verified by direct observation (not in the logs)

- **Unplug -> dark.**
- **Fan ramp from `usb-therm`.** With `thor-fanctl` disabled, on a fast charger
  with nothing else running, the fan audibly ramps — the inherited 43 C trip
  firing, which is what the 60 C value exists to stop.

### Not separately re-measured

- The 85% hold was measured with a temp copy of the same script at the same
  trigger, not with the installed module. The boot log and selftest show the
  installed module binds the same trigger the same way, so this was called done
  rather than re-run.
- **`FULL` -> solid.** `status` never reaches `Full` here: charging control
  stops charging long before termination. Not on the critical path, because the
  shipped default does not depend on it.

## "100%" is not `Full` — expect blinking past 100%

Observed 2026-10-21 and **not a bug**. The kernel trigger reads
`POWER_SUPPLY_PROP_STATUS`, never `capacity`:

```
capacity    = 100%
status      = Charging        <-- what the trigger reads
current_now = 464366 uA       <-- still ~0.46 A going in
voltage_now = 4320786 uV      <-- 4.32 V, below full-charge voltage
dumpsys     = status: 2       (BATTERY_STATUS_CHARGING)
```

The fuel gauge reports 100% well before charge *termination*. The pack is still
in constant-voltage top-off, so `status` stays `Charging` and
`battery-charging-blink-full-solid` keeps blinking — exactly as
`power_supply_update_bat_leds()` specifies. It switches to solid when `status`
becomes `Full`, which can be tens of minutes after the UI says 100%.

If blinking past 100% is unwanted, the trigger is already selectable without a
code change — `CHARGE_TRIGGER` is an environment tunable:

| Want | Command |
|---|---|
| solid whenever plugged in (**default**) | `stickfx charge --color 2BE0D8` |
| blink charging, solid full | `stickfx charge --color 2BE0D8 --trigger battery-charging-blink-full-solid` |
| lit only once truly full | `stickfx charge --color 2BE0D8 --trigger battery-full` |

`--trigger` is **persisted** (`/data/adb/thor-stickfx/trigger`) and honoured by
`service.sh` at boot. v0.1.1 and earlier only had the `CHARGE_TRIGGER`
environment variable, which meant a chosen trigger silently reverted to blinking
on the next reboot.

A capacity-based rule ("solid at 100% regardless of status") would need a
polling daemon, which this module deliberately does not have — the kernel
triggers exist precisely to avoid one.

## Will `status` ever reach `Full`? Often not — which picks the trigger

Measured 2026-10-21, 13 minutes of 2 s samples, screen awake with an app open
and "never sleep while plugged in" set.

```
usb_real_type   = SDP              <-- a PC data port, not a charger
usb current_max = 900000 uA        <-- 0.9 A, ~4.3 W total input
AC powered      = false
batt voltage_now= 4328960 uV       <-- 4.329 V
batt voltage_max= 4400000 uV       <-- 71 mV short of the CV plateau
```

Battery current over those 13 minutes wobbled between roughly 250 and 650 mA
and went **negative 13 times, as low as -1.23 A** — the pack was *discharging*
while plugged in, because the awake screen plus the app outdrew the 0.9 A input
budget.

Charge termination needs the cell to reach constant voltage (4.40 V here) and
then taper below a termination threshold. A device that cannot hold a steady
positive charge current will not get there, so `status` stays `Charging`
indefinitely and `Full` never arrives.

On a real charger (DCP/PD, several amps) the surplus over system load is large
enough to push through CV and terminate, even with the screen on — it just
takes longer than with the screen off.

**This is why `battery-charging-or-full` is the default** as of v0.1.3. On any
slow source `battery-charging-blink-full-solid` blinks essentially forever and
`battery-full` almost never lights; solid-whenever-plugged is the only option
that does not depend on charge termination ever happening, which is the right
assumption for a handheld that lives plugged in with the screen on.

The blink variant remains available and persisted:
`--trigger battery-charging-blink-full-solid`. Precedence is environment >
persisted choice > default, verified on hardware:

```
fresh state dir      -> battery-charging-or-full      (default)
persisted file       -> battery-full                  (overrides default)
CHARGE_TRIGGER env   -> battery-charging              (overrides both)
```

## A note on v0.1.0 -> v0.1.1

v0.1.0 called `set_perm` on `system/bin/stickfx` at the top level of
`customize.sh`. Magisk **sources `customize.sh` before extracting the module
files**, so that ran against a `$MODPATH/system` that did not exist yet. It was
harmless in practice only because the zip already carried the executable bit
(the staged binary was `-rwxr-xr-x`), but it was wrong. v0.1.1 moves it into
`set_permissions()`, the hook Magisk calls *after* extraction, and a regression
test asserts no `set_perm` on `system/bin` remains at top level.

The installed v0.1.0 works; v0.1.1 is a correctness fix, not an urgent one.
