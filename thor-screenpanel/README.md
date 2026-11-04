# thor-screenpanel

Stock's AYN-button panel, recreated — the **Mode** tab, in Material You, on the
bottom screen.

![three options: Dual-screen display / Only top screen / Only bottom screen]

```
./build.sh      runs 333 host tests + 17 action tests, then packages
./install.sh    installs the APK and the AYN button action script
```

## Requires

Three things must already be present. `install.sh` checks for all of them and
refuses to proceed if any is missing:

| dependency | provides | why |
|---|---|---|
| `thor-screenblank` | `/system/bin/screenblank` | turns a panel genuinely off |
| `thor-screenmove` | `/system/bin/screenmove` | moves apps off a screen before it goes dark |
| `thor-aynkey` | `aynkeyd` | detects the button press that opens this panel |

Plus root, and Magisk for the three modules.

**This component has no Magisk module of its own.** It is an APK installed with
`adb install`, plus `action.sh` placed into `/data/adb/thor-aynkey/` by
`install.sh`. That is deliberate: two Magisk modules writing the same
`/system` path would conflict, and keeping press *detection* separate from
press *meaning* is what lets the long press work when the UI cannot.

## Version compatibility

The panel calls exactly these:

```
screenblank status
screenblank off <top|bottom>
screenblank on  <top|bottom>
screenmove  status
screenmove  to-top
screenmove  to-bottom
```

Both `status` outputs are **parsed**, so a change to either command's wording
is a breaking change for this APK.

Two behaviours it relies on: `screenmove` exits `1` for "nothing to move",
which is a no-op rather than a failure; and `screenblank` refuses to blank a
panel while the other is already blanked, which is what makes a single long
press a safe way back.

## What it does

Three options, in stock's own wording:
**Dual-screen display**, **Only top screen**, **Only bottom screen**. The
current mode is read back off the device, not remembered, because the AYN
button's long press can change it while the panel is open.

It calls `screenblank` and `screenmove`. It reimplements neither.

## The launcher icon is the way back

The app shows one icon, **AYN Panel**. It is not a second way to open the panel
for convenience — it is the repair path.

When `aynkeyd` dies the button goes completely dead: no panel, and no long press
to bring the bottom screen back, since both come from that one daemon. Before
this icon existed the only fix was `adb shell su -c 'aynkeyd start'`, which is
no use away from a host.

Tapping it runs two commands as root:

```
/system/bin/aynkeyd start              # idempotent; reports an existing daemon
/data/adb/thor-aynkey/action.sh short  # the daemon's own short-press path
```

The second one is the point. It does **not** reimplement what a press means —
unblanking the bottom screen, resolving the bottom display id, placing the
panel there — it runs the same script the daemon runs. A launcher tap and a
button press therefore cannot drift apart.

Two details that look like oversights and are not:

- **The icon is on `LaunchActivity`, not `PanelActivity`.** A launcher tap
  starts an activity on the display the launcher is on — the top screen — and
  this panel is bottom-screen only. `action.sh` is what places it correctly.
- **`LaunchActivity` draws nothing.** It runs the two commands and finishes;
  the panel appearing on the bottom screen is the feedback. Its window is
  transparent and undimmed rather than `windowNoDisplay`, because a no-display
  activity must finish before it resumes, and finishing first would drop the
  process to background priority with the `su` calls still in flight.

It toasts only when the result is not obvious: that it had to restart a dead
daemon, that the daemon would not start (no root, or `thor-aynkey` missing), or
that the action script failed (`install.sh` never run). A plain success says
nothing — a toast on the top screen announcing a panel you can see on the
bottom one is noise.

Then read `aynkeyd log` to find out why it died; the daemon keeps a bounded
file for exactly that, because logcat here retains under six minutes.

## It is bottom-screen only

A short press with the bottom screen off **turns that screen on and then shows
the panel there**. It never falls back to the top screen. A second short press
closes it.

## Material You with no dependencies

A plain platform APK — `aapt2` + `javac` + `d8`, no Gradle, no AppCompat, no
Material Components. Theming comes from the `@android:color/system_*` dynamic
palette the platform derives from the wallpaper, which is the real Material You
palette, with a `values-night` variant.

## Ordering is the safety property

Blanking a screen that still holds the foreground app leaves that app running
and focused on a panel that is powered down and no longer takes touch. So every
transition is planned, not applied incrementally:

```
unblank  ->  move  ->  blank
```

`ScreenMode` produces that plan and `TestMain` asserts the invariants over
*every* transition: the end state matches the mode, the ordering holds, no plan
ever darkens both screens mid-transition, and re-selecting a mode is a no-op.
That last one caught a real bug — the move was being issued unconditionally,
which re-fronted a backgrounded app on every re-selection.

## `persistableMode="persistNever"` is load-bearing

The panel's window is translucent over a transparent background. Android was
restoring its task across reboots, and a restored panel with nothing behind it
paints the bottom screen black — which looked exactly like the screen failing
to turn on.

## What is in the panel

**Brightness** — a slider per screen, styled after the notification shade's.
 needs `thor-screenbright`, which decouples the two
panels; without it both screens share one brightness and the bottom slider does
nothing. The floor is 1%, not 0: at 0 the panel looks switched off rather than
dim, and "off" has its own control in the Mode row. Volume has no such floor —
silence is a setting people want.

**Quick settings** — five tiles. Fan and Stick LEDs cycle through the same
states their Quick Settings tiles offer, Move app sends the foreground app to
the other screen, Clean memory clears background apps, and Haptics toggles
touch feedback. Stock's vibration tile wrote `vibrate_on`, which does not
exist on this build — it reads `null` — so this drives
`haptic_feedback_enabled` instead. These reuse the tiles' own mode definitions rather than copying them,
so a renamed state breaks the build instead of silently invoking an argument
the CLI no longer accepts.

**Telemetry** — live readings on their own tab: CPU clock, the hottest CPU
core, chassis and USB temperature, fan duty, battery power and memory in use.
Everything there is a read; nothing on that tab writes to the device.

**Mode** — the three screen modes, kept at the bottom, nearest the button.

## Two tabs, and Mode in neither

**Controls** and **Telemetry** take turns. **Mode is outside both** and stays
on screen whichever tab is showing, still at the bottom, still one tap from
the AYN button.

That is the point of the arrangement. The panel used to be one scrolling
column, on the grounds that a tap to reach the fan is a tap the physical
button was supposed to save. A gauge card is too tall to simply stack on the
end of that, so something had to move behind a tab — and the right something
is the readings, which nobody presses a button in order to look at.

## What the telemetry tab can and cannot show

| gauge | source |
|---|---|
| CPU clock | `scaling_cur_freq`, per cluster — three on this chip, not eight cores |
| CPU / chassis / USB temperature | thermal zones, resolved by `type` |
| Fan | `pwm1` duty, and the thermal governor's cooling state |
| Power | `current_now` × `voltage_now`, with the direction taken from `status` |
| RAM | `/proc/meminfo`, the one reading that needs no root |

**There is no fan RPM.** The fan is the kernel's `pwm-fan` driver, and on this
build it exposes a duty node and nothing else — there is no tachometer
anywhere in `/sys`, so no frame of reference beyond duty and cooling state.

**GPU clock may be missing.** Its sysfs directory is unreadable on this build
even with root, so the row appears only when a readable node is found. It is
left out rather than shown as zero, because a GPU reading 0 MHz looks like an
idle GPU rather than an absent one.

Readings refresh once a second, and only while the tab is actually in front.

## Not yet

A frame-rate counter, which needs the compositor rather than this process —
see `docs/ayn-button-panel.md`. Stock's other tabs (Settings / Task /
Application), the per-output volume sliders, and the 60/90/120 FPS control.
