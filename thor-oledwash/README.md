# thor-oledwash

Sweeps a band of bright noise down each of the AYN Thor's OLED panels, so that
static content — a launcher, a status bar, a paused game — does not sit on the
same pixels indefinitely.

AYN's stock ROM gained this in **v1.0.0.360 (2026-01-13)** as *Settings → Thor
Settings → Advanced Settings → OLED Screen Protection*. LineageOS has no
equivalent and neither does AynParts.

## Requires

**Nothing.** No Magisk, no root, no permissions — this app holds none at all,
and the Superuser step the other apps in this repository need does not apply to
it. Press **Run now** and both panels wash.

That is a property rather than an accident: the build refuses to package if
anything on the manual path acquires a shell or a root dependency.

### To have it run by itself

Install **`thor-oledwatch`**, the companion Magisk module. It watches for
inactivity and starts a wash; the app still owns everything about what gets
drawn. The module needs the app; the app never needs the module.

The **Run by itself** section at the top of this app chooses the interval and
turns it on or off. Those settings are the app's own preferences; the daemon
reads them, so the app never needs root to write them.

Root is unavoidable for that half, for two independent reasons: `dumpsys power`
is the only idle signal that sees gamepad input and it requires
`android.permission.DUMP` (`signature|privileged`), and an app cannot start an
Activity from the background without `SYSTEM_ALERT_WINDOW` or a visible window
— a root `am start` is not subject to that at all.

## What it draws

One band per panel, sweeping top to bottom, both panels at once.

| | default | why |
|---|---|---|
| band height | 33 % of the panel | measured from photographs of the stock feature, and it is a *power* parameter — see below |
| sweep time | 2.5 s | a judgement call, which is what this build is for |
| sweeps | 4 | about ten seconds in total |
| noise grain | 2 | one noise pixel per 2×2 screen pixels, four times cheaper than 1:1 |
| brightness | **untouched** | no evidence that forcing it higher helps, and it would accelerate the ageing this is meant to avoid |

Every one is a picker in the app, so they can be compared side by side.

### Why a band and not a white screen

Every OLED limits luminance as the average picture level rises. This unit says
so itself: display 0 reports a peak of **420 nits** against a sustained
full-field maximum of **210**. A band covering a third of the panel stays below
where that limiter bites, so the lit third can be driven hard — which a
full-screen wash could never be.

### Why noise and not a solid bar

A solid bar exercises every pixel identically, and is itself a static pattern
being dragged across the screen. Random noise, regenerated every frame, gives
each subpixel its own drive level and its own history.

### Why sweep speed is a matter of taste

For a band of height `h` on a panel of height `H` moving at speed `v`, each row
is lit for `h/v` out of every `(H+h)/v`. **`v` cancels.** The energy each pixel
receives per second is set by band height alone, identically at 100 px/s and
2000 px/s — speed only changes whether that arrives as a few long exposures or
many short ones. So there is no "correct" value to derive, only one to choose.

## Stopping it

Touch either screen, press any button, or move a stick or trigger. It also
stops on its own after the configured number of sweeps, and if the screen goes
off or anything else takes the panel.

It **never** holds the screen awake and **never** changes brightness.

## What it is and is not

Four sweeps gives each row about 2.5 seconds of drive in total. That is
**prophylaxis** — it stops static content accumulating on one set of pixels. It
is not a cure for retention you can already see; clearing that is a minutes-long
job and this is not it.

## Safety

Unusually benign for this repository. It writes no hardware node, keeps no
state, has no boot behaviour and holds no permissions. The kill switch is
uninstalling it.

The one real cost while it runs is power and heat: a third of each panel driven
bright, for about ten seconds.

## Not stock's mechanism

Stock is a system app and can use a signature-level window type. This is an
ordinary fullscreen Activity in immersive mode, which hides the system bars with
no privilege — and that matters, because this project already built, tested and
rejected a `TYPE_APPLICATION_OVERLAY` version of a different feature for being
unable to outrank the status bar.
