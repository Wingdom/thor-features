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
feature on the host with no device and no root — that is how all 421 tests
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


## The telemetry tab

The panel has two tabs — **Controls** and **Telemetry** — and **Mode is in
neither**. It sits below them and stays on screen in both, nearest the button.

That is not a layout accident. The panel was deliberately one scrolling column
rather than stock's tabs, because "a tap to reach the fan is a tap the
physical button was supposed to save". A gauge card cannot be stacked onto the
end of that and still leave Mode at the bottom, so one thing had to go behind
a tab. The readings are the right thing: nobody presses a hardware button in
order to look at a temperature.

### Everything on it is a read

No writes, no revert path, nothing to restore at the end of a session. The
tab's whole risk surface is reading the wrong node or reading it too often.

### Two commands, and the second one repeats

Discovery resolves every path once. A tick then reads those paths. Both are a
single `grep -H .` over a file list, which is one fork for any number of
files — the same forks-not-bytes lesson that took `screenblank status` from
482 ms to 179 ms with byte-identical output.

`-H` is explicit rather than assumed. With a glob matching exactly one file,
grep omits the filename prefix, and every line then parses as a value
belonging to nothing.

Measured on the device 2026-11-04, ten iterations each:

| | per tick |
|---|---|
| two greps, `/proc/meminfo` filtered to two keys | 41 ms |
| one grep, `/proc/meminfo` matched whole | 26 ms |
| one grep, one file — the fork floor | 13 ms |

So a fork costs 13 ms, and the 29 thermal sensors in that read cost about
9 ms between them. Filtering meminfo was paying 15 ms to avoid carrying 48
short lines, which is the wrong way round; the tick is now one grep. It also
cost nothing to change, because the parser already collected every meminfo
line and picked the two it wanted.

The real generated command — 42 paths — measures **25 ms**, which at a
one-second tick is 2.5% of one shell.

**Discovery is lazy**, on the first tap of the tab rather than on panel open.
Open cost is the number this file spends most of its length on; most opens
never look at the gauges.

### The tick rate is a decision, not a default

One second. The reason is not the panel's own cost — it is that this project
has already had a measurement heat its own subject: a `fanctl selftest` that
re-ran discovery per item took over fourteen minutes and spun the fan up by
itself. One second is also the slowest rate that still reads as live, and
every reading except CPU clock moves far slower than that.

The tick reschedules **after** each reply rather than on a fixed period. A
fixed `postDelayed` loop would queue reads behind each other the moment one
exceeded the interval, and the queue would never drain; this way a slow device
ticks more slowly instead.

Ticks run only while the tab is in front and the activity is resumed.

### Resolve by type, never by index

Thermal zone and cooling device numbers are assigned at boot. The fan's
cooling device was 34 on one boot and 35 on another on this same build.
Nothing in the telemetry code names an index: discovery reads every `type`
file and derives the paths from whichever matched.

### The height budget is tight, and it is load-bearing

The bottom screen is 620x540dp. Between the status bar (56px) and the
navigation bar (96px) there are **928px**, and after the column's own margins
this layout gets **816px**. Adding the tab strip pushed the Controls tab to
928px of content, and because the window is `WRAP_CONTENT` and bottom-anchored
the overflow came off the *last* child: the Mode card rendered at 101px of its
221, with all three of its labels gone. It looked like a design choice rather
than a bug, which is what made it worth measuring instead of eyeballing.

Reclaimed by trimming padding throughout — the tab strip to 30dp, card
paddings from 10/12dp to 6dp, item paddings from 6dp to 3dp — for a content
height of **778px against 816 available**, about 38px of headroom. Nothing was
resized: the sliders are still 40dp and the icons still 52dp.

Do not restore those paddings without re-measuring. The quickest check is the
bounds, not a screenshot:

```sh
adb shell uiautomator dump --display 1 /data/local/tmp/ui.xml
adb pull /data/local/tmp/ui.xml && grep -o 'text="Only bottom screen"' ui.xml
```

If the Mode labels are missing from the dump, the card is being clipped.

One caveat learned the hard way: `uiautomator dump` fails silently when the UI
is not idle and leaves the *previous* file in place, so a stale dump reads as
a confident wrong answer. Check the timestamp, or compare screenshot byte
sizes, which differ clearly between the two tabs.

### Four rings over four bars

The Telemetry tab is shaped after stock's own overlay: coloured ring gauges
for the readings that move while a game runs — CPU, GPU, power, memory — over
bars for the ones you check rather than watch, the three temperatures and fan
duty. Two shapes rather than one is what makes the hierarchy readable at a
glance; a circle is a headline number and a bar is a supporting one.

### The rings are three sizes, not one

Five equal circles give five equal claims on the eye, and these are not
equal. The size says what the reading is for:

| | | |
|---|---|---|
| **FPS** | 132dp | the only reading about the game rather than the machine, and what the tab gets opened for |
| **CPU**, **GPU** | 112dp | what is being asked of the chip — matched to each other, because that comparison is the point when a game drops frames |
| **PWR**, **RAM** | 88dp | real readings, but checked rather than watched; neither changes what you would do in the next few seconds |

A cell's width is weighted by the same number its diameter uses, so the two
cannot drift into a cell much wider than the circle it holds, and the row is
centred vertically so the small rings sit on its axis rather than hanging from
its top. The gauge draws on `min(width, height)`, so the height is what sets
the diameter.

`RingGauge` is a plain custom `View`. It draws the arc and all three pieces of
text in one pass against one set of bounds, which is the only way the text
stays centred as the cell width changes — a circular `ProgressBar` is sized by
its own bitmap and cannot carry text at all. It holds no logic: the caller
hands it a label, the strings to print and a fraction, and the mapping from a
reading to that fraction lives in `Telemetry` where it is host-tested.

It also publishes its reading as a content description, which is not
decoration. **Text drawn on a `Canvas` is invisible to `uiautomator dump`**,
and that dump is how this panel gets verified on the device; without it a ring
would be unreadable to the only tool that can check it.

Each metric keeps one fixed colour. That is the single place this panel
departs from Material You, deliberately: a palette derived from the wallpaper
would give four rings four shades of one colour, and these exist to be told
apart from the corner of an eye.

**The power arc is scaled to a 25 W full scale**, which is a display
convention and not a hardware limit — there is no honest ceiling for battery
draw, and this device cannot even negotiate PD while adb holds the USB-C port.
The printed figure stays exact and the arc saturates rather than misleading.

### Why the Telemetry card has a minHeight

The panel is bottom-anchored and `WRAP_CONTENT`, so a shorter tab makes the
whole window shorter and the tab strip jumps. Before the card was given a
minimum height the window top moved from `[0,190]` on Controls to `[0,461]` on
Telemetry — the strip travelled 271px, so "Controls" was nowhere near where
the finger had just tapped "Telemetry". Sizing the Telemetry tab to match the
Controls tab brought that to **11px**, which is imperceptible.

**Mode never moved either way.** It is anchored to the bottom and stays at
`[52,785]-[1188,968]` on both tabs, which is the property that actually
matters.

One note for anyone scripting the panel anyway: if you change these heights so
the tabs differ again, re-read the tab bounds after every switch. A tap at
stale coordinates lands outside the window, and `windowCloseOnTouchOutside`
dismisses the panel rather than switching tabs.

### The CPU row shows two numbers, and why not a tap

`58%  2.71 GHz` — load and clock together, on the pattern the Fan row already
set with `38%  3/8`.

A tap that toggled between them was considered and rejected. It hides half the
reading behind a gesture nothing advertises; it would leave the CPU row
tappable while the GPU row beside it cannot be, since there is no live GPU
clock to toggle to; and the gauge rows are deliberately styled as readings
rather than controls — a 6dp bar against the brightness slider's 40dp — so
making one of them answer touch argues with what the styling is there to say.

Load leads because it lines up with the GPU row directly below and answers
"is it working hard". The clock follows because on a handheld it answers "is it
being allowed to". The bar tracks load, so the CPU and GPU bars mean the same
thing and can be read against each other.

Load is a delta of `/proc/stat` jiffies, so it does not exist until there are
two samples. The first tick shows the clock alone rather than an em dash beside
a real number, and the bar falls back to the clock against `cpuinfo_max_freq`
for that one tick. `/proc/stat` rides in the same `grep` — 1417 bytes over 16
lines on this device, so carrying it whole costs nothing where a second fork
costs 13 ms.

One trap in parsing it: the aggregate `cpu` line and the per-core `cpu0`..`cpu7`
lines are in the same file and start with the same three letters, so matching
on a prefix reports one core's load as the whole chip's. The match is on the
whole first token.

### Battery direction needs both sources

`status` is preferred — it says "Charging" or "Discharging" in words and cannot
be misread. But it is frequently neither. Plugged in at 85%, this device sits
at **"Not charging"** for long stretches while the charger holds, and taking
the sign from `status` alone rendered a real reading as a bare `6.05 W` with no
direction at all. Worse than wrong: uninterpretable.

So an undecided `status` falls through to the sign of `current_now`. Both
directions of that register have been observed here — `+229619` while charging,
and negative at 6.05 W under a CPU load with the charger still holding, which
is the battery supplementing a charger supplying less than the load wanted.
Positive is into the battery, negative out of it.

### Ask the residency counter before trusting a node

Two gauges here were nearly wrong in the same way, and the same read settled
both. A sysfs node that returns a plausible number is not necessarily
reporting anything: it may be handing back a ceiling, or a request, or a
value nothing updates. The counters beside it say which.

**The GPU clock is dead.** `devfreq/cur_freq` reads `680000000` and never
moves. `trans_stat` is unambiguous — 2,693,080 ms accumulated at 680 MHz and
exactly **0 ms at each of the other eight** available steps, three recorded
transitions in the whole uptime — while `gpu_busy_percentage` over the same
moments read 0–4%. So the GPU row is **utilisation**, not frequency. A clock
gauge would have been a flat "680 MHz" that looked like a GPU at full tilt
forever: always plausible, never right, which is the same failure as measuring
the panel's own frame rate with a Choreographer.

**The CPU clock is alive**, and it looks dead from the inside. Six consecutive
samples returned byte-identical frequencies, because the `cat` doing the
reading *is* the load — by the time the value comes back, the governor has
ramped that cluster. `stats/time_in_state` proves the node fine: policy0 had
visited 14 of its 16 states over 24,682 transitions, policy3 19 of 20,
policy7 19 of 21. It moves; it just cannot be caught idle by an observer that
wakes it.

Same technique, opposite answers. That is the point: check the residency, not
the value.

### What this hardware will not give you

**Fan RPM does not exist.** The fan is the kernel's `pwm-fan` driver bound at
`pm8550@1/pwm-fan@0`, and `pwm1` is its only attribute — `find /sys -name
'fan*'` returns nothing outside the device tree, and there is only one hwmon.
Upstream `pwm-fan` registers `fan1_input` only when the device tree gives it
a tach interrupt, and this one does not. Duty and cooling state are the whole
picture. Verified twice, the second time in the privileged SELinux context in
case the first had been a denial in disguise.

**No skin sensor either.** 60 thermal zones, and not one `skin` or
`quiet-therm` among them. The board row reads `xo-therm` and is labelled
"Board" rather than "Skin", because calling it skin would claim a measurement
this hardware does not make.

### A trap worth knowing: `su -c` is not the context the panel runs in

`/sys/class/kgsl/` reads `Permission denied` under `adb shell su -c '<compound
command>'`, and that is **not** what the panel experiences.

```
$ adb shell su -c 'cat /proc/self/attr/current'          -> u:r:magisk:s0
$ adb shell su -c 'echo "$(cat /proc/self/attr/current)"' -> u:r:shell:s0
```

A command string that needs a shell gets one, and the magisk domain
transitions to `shell` on exec'ing `shell_exec` — uid 0 either way, but that
domain is denied kgsl. A single simple command execs its binary directly and
stays privileged.

`RootSession` feeds a persistent shell on **stdin** with no `-c` at all, and
that shell stays in `u:r:magisk:s0` — including inside the `( ... )` subshell
`RootProtocol.wrap` puts every command in, and including commands with
redirections. So the panel reads kgsl fine.

The moral is about probing, not about SELinux: a read that fails over `adb
shell su -c` has not been shown to fail for the panel. Reproduce a denial
through the same transport the code uses before believing it.

### Why `usb-therm` has its own row

Not because it runs hot. It is an open question on this hardware: `fanctl`'s
60 C quiet trip is reasoned rather than measured, and no log has caught that
sensor above 42 C. It is driven mainly by **charge current**, not SoC load, so
a live reading on the panel during a real charge-plus-game session is the
evidence that has been missing. Note that PD cannot negotiate while adb
occupies the USB-C port, so a full-rate test needs `adb tcpip` over WiFi.

## The frame-rate counter

It measures the game on the **other** screen, and that is the whole difficulty.

`Choreographer` is the obvious answer and it is the wrong one. It measures the
frame rate of the process that registers it, so in the panel it measures the
panel — a static card on the 3.92" screen, which would report a steady 60
while a game stuttered at 24 beside it. A gauge that is always plausible and
never right is worse than no gauge.

So the counter reads SurfaceFlinger, the only process that sees every
application's frames. Verified on the device 2026-11-04.

### `dumpsys SurfaceFlinger --latency` still works on Android 17

It returns what it always did: a refresh period in nanoseconds, then up to 128
rows of three tab-separated timestamps. Against a running game it gave 65
frames about 8.43 ms apart — roughly 119 fps on a 120 Hz panel.

The rate comes from the **middle** column, actual-present: when the compositor
put the frame on the glass. "Desired" is the app's intent and "ready" is when
its buffer was finished, and a game that renders early and waits for vsync has
a "ready" rate far above what anyone sees. The divisor is the number of
*intervals*, not of frames — n timestamps bound n−1 periods, and using n
under-reports worst on a short window, which is exactly the case of a game
that just launched.

One call costs **35 ms**, which is why the layer name is cached rather than
re-resolved per tick.

### Two traps, both of which would have produced a confident wrong number

**The panel itself holds focus while it is open.** It is a focused window on
the bottom screen taking taps, so `dumpsys window | grep mCurrentFocus` finds
*the panel*, and a counter built on that measures this card. Focus has to be
read per display, from inside a display's section of `dumpsys window
displays`. Note the sections are not in display order — on this device display
1 is printed before display 0 — so the parser tracks the section header rather
than assuming.

**Layer names are not what they used to be.** Android 17 wraps every layer:

```
RequestedLayerState{f276fb1 SurfaceView[pkg/act](BLAST)#867 parentId=866}
RequestedLayerState{ActivityRecord{1234 u0 pkg/.Act t80}#854 parentId=853}
```

The second line matters: the name itself contains braces, so the wrapper has
to be stripped at the **last** closing brace, not the first. The trailing
`key=value` attributes are then dropped, and what is left — spaces, brackets
and all — is the name `--latency` wants, single-quoted.

### Only one layer of eight carries frames

A focused app owns eight or so layers and seven return the refresh period and
nothing else. For a SurfaceView game it was the `(BLAST)` child; its parent
SurfaceView and the `VRI-` layer were both empty.

Rather than encode that as a rule, `pickLayers` returns candidates in
preference order — BLAST SurfaceView, then any SurfaceView, then the view-root
layer, then anything else naming the package — and the caller takes the first
that actually yields frames. That is self-correcting if a release reshuffles
them, and the extra calls are paid once at resolve rather than every tick.
Four kinds of layer that can never carry frames (`ActivityRecordInputSink`,
`Background for`, `Bounds for`, `ActivityRecord{`) are dropped outright so no
35 ms call is spent on them.

### When nothing is drawing, the ring is absent

Not blank, not an em dash — absent, and the remaining four rings space out to
fill the row. The probe runs once when the tab is first opened; if no layer
yields frames, no FPS ring is built. A ring that always read an em dash would
be the "looked alive and did nothing" failure this panel has already had.

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
buffer on this device retains minutes, not hours: `Logspan` measured 5:52 on
one boot and 2:11 on another, and on the second the daemon's start lines were
gone 45 seconds after it wrote them. It is empty long before anyone notices a
dead button. It is still the better live view while you are pressing the
button.

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
