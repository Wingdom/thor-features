# The AYN button and its mode panel

The AYN Thor has a physical **AYN button** below its 3.92" bottom screen. On
the stock ROM it opens a panel for controlling the two screens. LineageOS does
not bind it at all — nothing happens when you press it.

This feature brings both halves back: the button does something, and what it
opens is stock's **Mode** panel.

## Per-screen brightness

The bottom panel is a brightness *follower* of the main one in
`/vendor/etc/displayconfig/display_layout_configuration.xml`, which is why both
screens always shared a level. `cmd display set-brightness --id 1` looks like
it works on a stock ROM — the value is accepted and read back — but never
reaches the hardware: with display 1 set to `0.1` its backlight node stayed at
`4095`.

`thor-screenbright` removes the one `<leadDisplayAddress>` element. After a
reboot the same command drives each panel independently and linearly
(`0.01 → 41`, `1.0 → 4095` against `max_brightness` 4095).

Three scales are in play on this device and are easy to confuse: the framework
float `[0,1]`, the backlight node `0..4095`, and the `screen_brightness`
setting `0..255`. `Brightness.java` converts between them in one place.

## Scope: the panel is UI, the modules are not its business

The panel invokes each CLI the same way that CLI's own Quick Settings tile
does, and stops there. No pre-checks, no retries, no interpreting *why* a
command failed.

`fanctl`, `stickfx` and `screenmove` pre-date the panel and have their own
tiles — treat those as pure pass-through. `thor-aynkey` and `thor-screenblank`
were written *for* the panel, so their behaviour is in scope here.

Decided 2026-10-27, after the panel grew a `screenmove status` pre-check so
"Move app" could say "Nothing to move". `screenmove` was being tuned
independently at the time, and a copy of its rules inside the panel is a second
opinion that goes stale.


## What the button does

| press | effect |
|---|---|
| short press | open or close the mode panel on the bottom screen |
| hold 2 seconds | turn the bottom screen off, or back on |

The long press fires **mid-hold**, at the 2-second mark, rather than waiting
for you to let go — so it feels like a button, not a delayed action. It also
wakes the device from doze.

The long press is deliberately independent of the panel and of anything that
draws on screen. It is your way back when the bottom screen is off.

## The panel

The panel appears on the bottom screen only, never the top, and offers stock's
three options:

- **Dual-screen display** — both screens on
- **Only top screen** — bottom screen off
- **Only bottom screen** — top screen off

"Off" means the panel is genuinely powered down: dark with no backlight glow,
and **not accepting touch**. That second part matters. Turning off only the
backlight leaves the digitiser fully live — during testing, a panel the user
could not see registered 24 touch down/up pairs, including an accidental
notification-shade pull. Touch is disabled before the light goes out and
restored after it comes back, so a dark panel never accepts input.

Switching to a single-screen mode also moves whatever was running onto the
screen that stays on.

## What it is made of

Three pieces, and you need all three:

| piece | what it is | why it is separate |
|---|---|---|
| `thor-aynkey` | Magisk module. A root daemon watching the button. | Detects presses; decides nothing. |
| `thor-screenblank` | Magisk module. The `screenblank` command. | Does the actual screen on/off. Useful on its own. |
| `thor-screenpanel` | The APK, plus an action script. | The panel UI, and the decision about what a press means. |

The join between them is a small script at
`/data/adb/thor-aynkey/action.sh`. The daemon runs it with `short` or `long`;
the script decides what that means. This is why `thor-aynkey` does nothing at
all on its own — by design, there is even a test asserting it has no built-in
action.

`thor-screenmove` is also required, because switching modes moves apps between
screens.

## Installing

Order matters: the panel's installer checks for its dependencies and refuses to
run if any are missing.

```sh
cd thor-aynkey      && ./build.sh    # flash dist/*.zip in Magisk
cd thor-screenblank && ./build.sh    # flash dist/*.zip in Magisk
cd thor-screenmove  && ./build.sh    # flash dist/*.zip in Magisk
# reboot, so the modules are live
cd thor-screenpanel && ./build.sh && ./install.sh
```

`install.sh` installs the APK, places `action.sh`, and restarts the button
daemon. Reboot once more and the button works.

Note that **Magisk stages module updates and swaps them at the next boot**, so
flashing a zip does not change the running copy until you reboot.

## If a screen is off and you want it back

**Hold the AYN button for 2 seconds.** That is the whole answer, and it is why
the long press does not depend on the panel.

Failing that, from a host:

```sh
adb shell su -c 'screenblank restore'
```

`screenblank restore` is an unconditional kill switch: it unblanks everything
and never fails on "was not blanked". A reboot also clears both writes, since
neither is persistent.

The module also refuses to blank one panel while the other is already
blanked — both screens dark is a device that looks dead.

## Things worth knowing

- **Suspend and resume reset the screen mode.** If the device sleeps while on
  "Only bottom screen", it wakes with both screens on. This is accepted
  behaviour rather than a bug, and is not going to be changed.
- **Only the Mode tab is built.** Stock's panel also had Settings, Task and
  Application tabs, brightness and volume sliders and a 60/90/120 FPS control.
  Mode is the half that carries screen on/off; the rest is not implemented.
- **`thor-screenpanel` has no Magisk module.** It is an ordinary APK plus the
  action script, which is also why it has an `install.sh` rather than a zip.
- **Apps do not default to the top screen.** That is a different problem and
  still unsolved — the relevant framework switch is not reachable from a Magisk
  module.

## Uninstalling

Remove in the reverse order, so nothing is left pointing at something missing:

```sh
adb uninstall com.thorfeatures.screenpanel
adb shell "su -c 'rm -f /data/adb/thor-aynkey/action.sh'"
# then disable or remove the modules in Magisk, and reboot
```

With `action.sh` gone the button becomes inert again — the daemon still detects
presses and simply has nothing to run. `screenblank` and `screenmove` remain
useful on their own from a shell.
