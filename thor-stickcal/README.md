# thor-stickcal

Joystick calibration for the AYN Thor that accounts for the shape of the
stick gate, so the **diagonals** reach full deflection.

An app, no Magisk module. It needs root.

---

## The problem it fixes

In an emulated game, pushing a stick to any of the four diagonals makes the
character **walk instead of run**, while all four cardinals run correctly.
Both sticks.

Nothing is misreading the stick. The kernel, the Android input stack, the
emulator and the game are all faithful. The fault is in the calibration data,
and it is not something care during calibration can avoid:

- The kernel's calibration interface is **per axis** —
  `min:max:center:deadzone`, four axes, no radial term anywhere.
- The Thor's stick gate is only **84–86% round**, and its narrowest point is
  at a **cardinal**.

So a stick calibrated to reach exactly full scale on all four cardinals
*must* fall short at the diagonals. The LineageOS calibration wizard in
AynParts records per-axis extremes from a freehand sweep, which is the best
its interface can express — and on this gate that means the diagonals come out
at 89–94% of full scale, just under the threshold a game uses for "running".
Running the wizard again does not help, because nothing about it is wrong.

AYN's own ROM did not have the problem, because it applied a dead-zone
**radius** in userspace instead of per-axis limits.

## What it does instead

It measures the gate and then solves the limits so that **every** direction
saturates:

1. **Maps each gate** by polling `/sys/class/moorechip-joystick/joystick/raw`
   while you roll the stick hard around its travel, in 24 sectors of 15°.
2. **Measures hysteresis** from two hands-off phases, one before any
   deflection and one after. A stick does not return to quite where it
   started — about 40 counts here — and that, not noise, is what sets the
   deadzone. Noise on this hardware measures *zero*: the MCU already filters.
3. **Solves the four limits in closed form** so the worst direction reaches
   112% of full scale, shrinking all four uniformly rather than giving up the
   diagonals.
4. **Writes it in both places**: the kernel node, which takes effect at once,
   and AynParts' `joystick_calibration` preference, which is what restores it
   after a reboot.
5. **Checks it downstream** by reading the framework's own axis values back,
   which is the same number a game sees. A calibration that is stored but not
   in effect fails this step and would pass any readback.

### What it costs

The sticks saturate at roughly **85% of their physical travel** instead of
100%. That is unavoidable on a gate this shape — reaching full scale in the
narrow direction means reaching it early in the wide ones — and the output is
clamped beyond that point anyway. The gain is that all 24 directions now carry
112–128% of full scale instead of 89–94% in the worst of them.

---

## Using it

Install the APK, grant it root, open **Thor Stick Cal** and follow the four
phases. It takes about a minute, and **nothing is written until you tap Apply**
on the review screen.

**Grant root first.** Magisk 31 removed the interactive su prompt and defaults
to Deny, so there is no dialog to accept: in the Magisk app, *Superuser* →
**+** → pick *Thor Stick Cal*. Without it the app says so on its first screen
rather than failing silently later.

### The phases

| | |
|---|---|
| **Hands off** | Let go of both sticks. Finds where they rest cold, and how much they move on their own. Advances once they have held still for four seconds. |
| **Left stick** | Roll it slowly all the way round, pressed hard against the outside of its travel. Two or three laps. |
| **Right stick** | The same. |
| **Hands off again** | Let go. This is the other end of the hysteresis range. |
| **The result** | The solved limits, the per-angle headroom table, and what it costs in travel. Tap **Apply** to write it. |
| **Check it** | Roll both sticks again. Every one of the 24 sectors must read 100% or more. |

A roll phase **advances by itself** once every sector has been swept, no
sector is short of its neighbours, and nothing is still reaching further out.
Sectors that are still short are marked on the plot, so you can see which part
of the circle to go back over. **Next** forces it through on what it has, for
a stick that cannot satisfy the criteria.

### Reading the plots

The blue blob is the measured gate — note that it is not a circle, and that it
is narrowest at a cardinal. The orange contour is where output reaches full
scale. **Inside the blue everywhere** is the result being looked for.

In the check phase the dashed green circle is full scale. Bars reaching past
it pass; a short red bar is a direction that still cannot saturate.

Cardinals read about 100–103% in any sweep. That is the per-axis clamp — one
axis at its limit, the other near zero — not headroom, and the cardinals were
never the failing case.

### Advanced

Two numbers, with the shipped defaults being the ones measured as right for
this hardware:

- **deadzone**, 60 raw counts. The app works out the floor your unit actually
  needs from the measured hysteresis and says so if you go below it. For
  reference, AynParts defaults to 200, which costs magnitude on *both* axes at
  once for noise that is not there.
- **target margin**, 1.12. How far past full scale the worst direction should
  reach. Room for the gate to have been measured slightly short and for the
  stick to wear; it is not caution about the arithmetic.

Changing either **re-solves from the same capture** — no need to roll the
sticks again.

### Restoring

The calibration found before the app's first run is recorded on the device and
never overwritten, and **Restore original** writes it back. It is also saved
off-device by hand, if you took a copy before installing.

---

## How it coexists with AynParts

AynParts owns the same node, and this app stays out of its way rather than
fighting it:

- It has **no persistent service** — one exported activity, two private ones,
  a boot receiver and two androidx services. It writes the node at boot and
  when you change something in its panel, and nothing holds it in between.
- Before writing its preferences, this app **force-stops it**, because it
  caches them in memory and would otherwise write its own copy back over ours.
- The preferences file is **edited, not regenerated**: it also carries the RGB
  lighting configuration, the gamepad layout, the trigger mode and the digital
  trigger threshold, and all of those are left byte-identical.
- The two analogue triggers in the calibration string are **carried through
  untouched**. Nothing here measures them, so nothing here writes them.

Opening the AynParts wizard again will overwrite this calibration with a
per-axis one. That is not a conflict to resolve; it is the wizard doing its
job. Run this app again afterwards.

---

## Building

```sh
./build.sh
```

`aapt2` + `javac` + `d8`. No Gradle. Needs `build-tools;37.0.0` and
`platforms;android-37.0` under `$ANDROID_SDK` (default `~/android`), and
targets **Android 17** because Play Protect on this device refuses installs
targeting anything older.

The build **runs the host test suite first and refuses to package if it
fails**. The solver is pure arithmetic over a measured gate, and the suite
includes the two gates actually measured on this unit, so a change that would
produce a worse calibration fails on a laptop rather than on the hardware. The
central assertion is the one the whole component exists for — every one of the
24 directions saturates — paired with its opposite: a per-axis calibration
built from the same gate must *fail* to.

It also compiles one file from `thor-screenpanel`, `RootProtocol.java`, rather
than copying it. How a long-lived `su` process is fed commands and told where
each reply ends is one behaviour, and a second copy of it would be a second
thing to keep right.

## Notes on the device side

- The node is labelled `vendor_sysfs_joystick`, whose allowlist is narrow: it
  denies the shell domain but permits Magisk's, so real root reads and writes
  both `raw` and `calibration` without trouble. A permission error here is
  worth checking twice — assert `id -u` in the *same* shell first, because
  MagiskSU intermittently hands back a non-root shell on this device, and a
  multi-line string passed to `su -c` never reaches `su` at all.
- Every shell command this app runs is a single line of absolute paths, for
  the same two reasons.
- A node readback is never treated as proof. That is what the check phase is
  for.
