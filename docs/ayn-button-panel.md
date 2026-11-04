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

## The root transport — read this before adding a feature

Everything the panel runs as root goes over **one persistent `su` shell**,
opened when the panel opens and closed with it. Measured on the device
2026-11-01:

| | per command |
|---|---|
| a fresh `su` per command | 38 ms |
| the persistent session | 17 ms |

### Adding a feature needs no change to the transport

Commands are plain shell strings. Build yours, hand it to `RootClient`, and it
runs over the session:

```java
RootClient.Result r = root.run("/system/bin/mything status");
if (r.code == 0) { /* r.out */ }
```

`RootClient` takes a `Runner` in its constructor, so a fake runner tests your
feature on the host with no device and no root — that is how all 119 tests
work. Keep new logic above that seam (pure text in, pure text out) and it is
testable; put it in the activity and it is not.

### The two things worth knowing

**It always falls back.** Any failure — session will not open, times out,
dies mid-command — returns to one-shot `su`. A dead session makes the panel
slower, not broken. Do not add a code path that assumes the session is live.

**Caching in the session was investigated and rejected.** The session was
built on the theory that `prelude()` would be the place to cache
`screenblank`'s digitiser resolution. Measuring that resolution showed the
theory was wrong twice over.

It was not slow because it was uncached. It was slow because it forked `cat`
once per directory, and because `status` asked for a digitiser four times --
once directly and once inside `touch_disabled`, for each of two screens.
Over nine directories on this device:

| resolving one digitiser | |
|---|---|
| `cat` per directory | 87 ms |
| `read` builtin | 13 ms |

Fixing that -- one pass resolving both digitisers, `read` instead of `cat`,
and `grep -m1` so `dumpsys` is killed by SIGPIPE rather than read to the end
-- took `screenblank status` from **482 ms to 179 ms** with byte-identical
output. Afterwards the resolution is 13 ms of 187 ms, so caching it in the
session would save about 7%, for real complexity.

The fix also helps every caller -- the CLI, the Quick Settings tile,
`action.sh` on every AYN button press -- where a session cache helps only the
panel.

**The larger win was a deletion.** `RootClient.available()` and
`blankState()` both ran `screenblank status`, and `refresh()` called them in
sequence, so the panel ran its most expensive command twice on every open.
They are now one call returning both answers. Open cost, both changes
together:

| | before | after |
|---|---|---|
| `screenblank status` | 513 x2 | 200 x1 |
| `screenmove status` | 211 | 211 |
| `fanctl` / `stickfx` / brightness x2 | 176 | 176 |
| **total** | **~1413 ms** | **~587 ms** |

> Measure before optimising here, and measure again after. Three predictions
> in a row were wrong in this file's history: that `su` spawn was the
> bottleneck (it was 174 ms of 1109), that caching was the next win (the
> forks were), and that dropping `dumpsys window` from `screenmove status`
> would save 56 ms (it saved 28).

**`screenmove status` was looked at and left alone.** It is ~200 ms, of which
about 120 ms is three different `dumpsys` services -- `window` for the
focused display, `display` for the bottom panel, `activity activities` for
the tasks. None is removable: the focused display id appears only in the full
`dumpsys window` output, not in `window displays`, `window policy`, or the
activity dump. A narrower `movable` subcommand that skips `dumpsys window`
measured 172 ms against 200 ms, and merging its two `awk` passes would save
~20 ms more at the cost of duplicating a host-tested parser. A 14% win for a
second copy of a parsing rule is the trade this project already regretted
once, so it was not taken.

### Threading

One shell, one stdin, one stdout, so commands are serialised on the session.
The panel issues everything from a single-threaded executor. If you add a
second caller, the lock already protects the stream, but two slow commands
will now queue behind each other.

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

If the button itself does nothing, the daemon has died — see **If the button is
dead** below. Failing that, from a host:

```sh
adb shell su -c 'screenblank restore'
```

`screenblank restore` is an unconditional kill switch: it unblanks everything
and never fails on "was not blanked". A reboot also clears both writes, since
neither is persistent.

The module also refuses to blank one panel while the other is already
blanked — both screens dark is a device that looks dead.

## If the button is dead

When `aynkeyd` dies the button goes completely dead: no panel from a short
press, and no long press to bring the bottom screen back. Both halves of the
binding are gone, because both are delivered by that one daemon.

**Tap the "AYN Panel" icon in the app list.** It restarts the daemon if one is
not running, then opens the panel. That is the whole fix, and it needs no host.

It is deliberately not a second implementation of what a press means. The icon
runs `aynkeyd start` — idempotent, so it reports an already-running daemon and
does nothing — and then `action.sh short`, the same script the daemon runs for
a real short press. One path, so a tap and a press cannot drift apart.

The icon is on `LaunchActivity`, not on `PanelActivity`. A launcher tap starts
an activity on the display the launcher is on, which is the top screen, and
this panel is bottom-screen only; `action.sh` is what resolves the bottom
display and places it. `LaunchActivity` renders nothing of its own — it runs
two root commands and finishes, and the panel appearing is the feedback. It
toasts only when something went wrong, or when it had to restart a dead daemon,
because that last fact is worth knowing.

From a host the equivalent is:

```sh
adb shell su -c 'aynkeyd start'
```

### Then find out why it died

```sh
adb shell su -c 'aynkeyd log 200'
```

`/data/adb/thor-aynkey/aynkeyd.log` is bounded at 128 KiB with one generation
kept, and it survives the death and a reboot. **Do not reach for
`logcat -s aynkeyd` after the fact** — the daemon does log there, but the main
buffer on this device retains under six minutes (`Logspan 5:52`), so it is
empty long before anyone notices a dead button. It is still the better live
view while you are pressing the button.

The log names which part of the daemon went. It is a pipeline of three
independently mortal processes plus a timer per press, and each labels its own
lines and reports its own exit status. `thor-aynkey/README.md` has the table
that reads a death off the last few lines; the short version is that
**no exit line at all means SIGKILL or the kernel**, because neither can be
trapped, so the absence is the evidence.

## Things worth knowing

- **Suspend and resume reset the screen mode.** If the device sleeps while on
  "Only bottom screen", it wakes with both screens on. This is accepted
  behaviour rather than a bug, and is not going to be changed.
- **Only the Mode tab is built.** Stock's panel also had Settings, Task and
  Application tabs, brightness and volume sliders and a 60/90/120 FPS control.
  Mode is the half that carries screen on/off; the rest is not implemented.
- **`thor-screenpanel` has no Magisk module.** It is an ordinary APK plus the
  action script, which is also why it has an `install.sh` rather than a zip.
- **The app has two activities and one icon.** `PanelActivity` is the panel and
  has no launcher category; `LaunchActivity` is the icon and the repair path.
  Neither holds any permission — everything goes through `su`.
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

`thor-aynkey`'s uninstall leaves `/data/adb/thor-aynkey/aynkeyd.log` behind, on
purpose and for the same reason it leaves `action.sh`: if the module is being
reinstalled to fix something, the log is the record of what needed fixing.

With `action.sh` gone the button becomes inert again — the daemon still detects
presses and simply has nothing to run. `screenblank` and `screenmove` remain
useful on their own from a shell.
