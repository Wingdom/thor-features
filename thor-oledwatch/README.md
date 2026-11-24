# thor-oledwatch

Runs the OLED wash after a stretch of inactivity.

```
oledwatch status              daemon, config, and the last decision it made
oledwatch start               run it (service.sh does this at boot)
oledwatch stop
oledwatch run [--dry-run]     wash now, by the same path the timer takes
                              (and recorded the same way, so the cooldown applies)
oledwatch decide              say what it would do right now, and why
oledwatch log
```

## Requires

Magisk, root, and the **Thor OLED Wash app** (`com.thorfeatures.oledwash`),
which is where the wash actually lives. The installer refuses without it.

## This module decides only *when*

The app decides what to draw, which panels to draw it on, and when to stop. The
coupling is one `am start` and it points one way:

- the module needs the app
- **the app does not need the module**, and works manually with no root, no
  Magisk and nothing flashed

That is enforced, not just intended: the app's own build fails if anything on
its manual path acquires a shell or root dependency.

## When it fires

All of these must hold:

| | |
|---|---|
| screen on | `mWakefulness=Awake` |
| **unlocked** | `mKeyguardShowing=false` |
| nothing holding the screen up | no non-partial wake lock, no window asking for a longer timeout |
| no input for the configured time | `lastUserActivityTime` |
| not just washed | a full interval since the last one |

A `PARTIAL_WAKE_LOCK` deliberately does **not** block it. Audio playing with
the screen idle is a normal state on a handheld, and it is exactly when a wash
is wanted rather than suppressed.

If the keyguard cannot be read at all, that counts as locked. A parse that
degraded to "go ahead" is how a wash ends up painted over a lock screen.

### Gamepad input counts, and that was measured

Android does not always treat joystick events as user activity — games are
expected to hold their own wakelock, which is why a controller alone does not
normally keep a screen awake. Had that been true here, `lastUserActivityTime`
would not have seen a player moving the sticks and this module would have
dropped a bright wash over somebody mid-game.

Measured on 2026-11-23 by injecting at the evdev layer, from a baseline
climbing past 218 seconds:

| | |
|---|---|
| left trigger | **31 ms ago** |
| left stick | **22 ms ago** |

Both reset it. So the framework's own counter is authoritative and no input
reader is needed.

## Configuring it

In the **app**, under *Run by itself*. There is no config file of this
module's own.

The settings are the app's own preferences, at
`/data/user_de/0/com.thorfeatures.oledwash/shared_prefs/oledwash.xml`, and this
daemon reads them. The direction is the point: the app writes them with no root
and no permission, and the half that already has root does the reading. The
alternative — the app writing under `/data/adb` — would have needed `su` in the
app and cost it its Magisk Superuser grant.

Device-protected storage rather than the default, because credential-encrypted
preferences are unreadable until the first unlock after a boot, and a daemon
reading them early would silently fall back to defaults the user never chose.

```
auto          true        Automatic on/off
idle_minutes  5           minutes of inactivity before a wash
band          0.33        band height as a fraction of the panel
cycle_ms      2500        one sweep
cycles        4           sweeps per wash
grain         2           screen pixels per noise pixel
target        0           0 both panels, 1 primary only, 2 secondary only
```

A missing file, a missing key or a blank value all fall back to the values
above, which are exactly `Params.java`'s — a test asserts that, because the file
does not exist until the settings screen is first opened and a disagreement
would be invisible until then.

## Verified on hardware

Flashed and rebooted on 2026-11-24, LineageOS 24, with the app's interval set to
2 minutes.

```
09:35:58  oledwatch started
09:37:57  wash started (idle 2m, band 0.33, 2500ms x 4, grain 2, target 0)
09:39:59  wash started (idle 2m, band 0.33, 2500ms x 4, grain 2, target 0)
```

- `service.sh` started it at boot; one process, pid matching the pidfile.
- It read **2m** from the app rather than its own 5m fallback.
- The first decision after boot was `hold locked` — the device was on the lock
  screen, and the keyguard gate refused on real state rather than on a fixture.
  Worth noting that the same sample read `override=10000`: the lock screen sets
  a user-activity timeout override, so had the checks been ordered differently
  this would have reported `timeout-override`. Both mean "do not wash", but only
  one is the true reason.
- It fired at `idle=120606` against a 120 000 ms threshold — the first poll past
  the line.
- `hold cooldown` was then observed at `idle=201577`, well past the threshold,
  which is what stops it firing on every poll while the device is left alone.
- The second wash came 122 seconds after the first, so the interval repeats.
- Both panels returned to their launchers afterwards; nothing was left on screen.

## Safety

- **Writes no hardware node**, no system property, no framework setting.
- **Never wakes the device and never holds a wakelock.** It runs inside a
  window the device was already awake for, and never extends it.
- **Never changes brightness.**
- **Nothing to restore.** Disable the module and reboot, or uninstall: there is
  no recorded original, because nothing was ever changed. That is unusual in
  this repository — `thor-fanctl` holds thermal trips, `thor-appwatch` leaves a
  fan tier persisted in someone else's state — and it is why this module's
  `uninstall.sh` is six lines.
- Stopping it without uninstalling: `oledwatch stop`.

## Why a module rather than something in the app

Two independent reasons, either of which alone would force it:

1. `dumpsys power` needs `android.permission.DUMP`, which is
   `signature|privileged`. It is the only idle signal that sees gamepad input.
2. An app cannot start an Activity from the background without
   `SYSTEM_ALERT_WINDOW` or a visible window. A root `am start` is not subject
   to that restriction at all.

A foreground service would need a permanent notification and would *still* need
root for the dumpsys, so it would cost the notification and buy nothing.

Checked and rejected as idle signals: `UsageStatsManager`, which reports
foreground changes, so a player sitting in one game looks idle; and a
`DreamService`, which never fires here because `stay_on_while_plugged_in=15`
means the device never reaches the point where it would dream — the very
configuration that creates the problem also disables the obvious solution.

## Cost

One `dumpsys power` and one `dumpsys activity activities` per decision, and the
interval is computed from how far away the next decision could possibly be —
between 10 and 60 seconds. Process spawns on this device are expensive enough
to change what is being measured; a per-item rescan elsewhere in this project
took 14 minutes and heated the SoC enough to spin the fan up by itself.
