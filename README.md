# thor-features

Stock AYN features, rebuilt for **LineageOS 24** on the **AYN Thor**.

The Thor's own ROM has fan control, joystick lighting, a dual-screen panel and a
programmable AYN button. LineageOS has none of them. This brings them back.

![The AYN button panel on the bottom screen](docs/img/panel.png)

---

## What you get

Most features come in two halves: a **Magisk module** that does the work, and an
**app** that gives you something to tap. You can install just the module if you
prefer, except where noted. Stick calibration is the one that is an app on its
own — it writes a calibration once and has nothing to do at boot.

### The things with a screen

| | Module | App |
|---|---|---|
| **AYN button panel** — press the AYN button to pick a screen mode and set each screen's brightness. | `thor-screenblank`, `thor-screenmove`, `thor-aynkey`, `thor-screenbright` | `thor-screenpanel` |
| **Fan control** — choose how eagerly the fan spins: quiet, balanced, or performance. | `thor-fanctl` | `thor-fan-tile` |
| **Joystick lighting** — light the stick rings while charging, or let them breathe. | `thor-stickfx` | `thor-stickfx-tile` |
| **Move an app to the other screen** — one tap sends whatever you're using across. | `thor-screenmove` | `thor-screenmove-tile` |
| **Per-game fan profiles** — pick a fan setting per game; it switches when you launch. | `thor-appwatch` | `thor-appprofiles` |
| **Stick calibration** — makes the sticks reach full deflection on the diagonals, which the built-in wizard cannot. | — | `thor-stickcal` |

Quick Settings tiles show the current setting at a glance, and tapping one opens
a picker:

![Quick Settings tiles for the stick LEDs and the fan](docs/img/tiles.png)

Per-game fan profiles switch automatically as you move between games:

![The per-game fan profiles app](docs/img/appprofiles.png)

### The things that just fix something

No app, nothing to configure. Install and forget.

| Module | What it fixes |
|---|---|
| `thor-wifi-kiwi-fw` | 6 GHz Wi-Fi, which otherwise fails to connect. |
| `thor-sdcard-fsck` | Large exFAT SD cards that refuse to mount. |
| `thor-display-density` | Adds main-screen density options Settings doesn't offer. |
| `thor-screenbright` | Lets the two screens have separate brightness instead of moving together. |

One older module is kept for reference only and you should skip it:
`thor-display-match`.

---

## Installing

You need an AYN Thor running LineageOS 24 with **Magisk** installed.

**1. Download.** Grab the zips and APKs from the
[latest release](../../releases/latest).

**2. Flash the modules.** In the Magisk app: *Modules → Install from storage*,
pick a `.zip`, repeat for each one you want, then **reboot**.

**3. Install the apps.** Tap each `.apk` to install it, or from a computer:

```
adb install -r thor-fan-tile.apk
```

**4. Give each app root.** This step is easy to miss, and nothing will work
without it.

**Magisk 31 never asks for permission.** Older versions popped up a prompt the
first time an app wanted root. Magisk 31 removed that, so you grant access up
front instead — and an app you haven't added does not appear in the Superuser
list at all.

In the Magisk app: *Superuser* tab → **+** button (bottom right) → pick the
app. Do that for each one you installed:

| You installed | Add this in Superuser |
|---|---|
| Fan tile | Thor Fan |
| Joystick lighting tile | Thor Sticks |
| Move-app tile | Thor screen move |
| AYN button panel | Thor Screen Panel |
| Per-game fan profiles | Fan profiles |
| Stick calibration | Thor Stick Cal |

Each one should show a switch turned on when you're done.

**5. Add the tiles.** Pull down Quick Settings, tap the edit (pencil) button,
and drag in the tiles you installed.

A fresh tile reads **"tap to set up"** until you tap it once — that's it
reading the current setting for the first time.

### Keeping the apps updated with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) can install the apps and
notify you when a new version appears, so you don't have to check back here.

Each app is released separately, with its own tag, so Obtainium only tells you
about an app that actually changed. Add the app once per entry, using this
repository's URL, and set Obtainium's release filter so it only sees that app's
releases:

| App | Release filter |
|---|---|
| Fan tile | `^thor-fan-tile-v` |
| Joystick lighting tile | `^thor-stickfx-tile-v` |
| Move-app tile | `^thor-screenmove-tile-v` |
| AYN button panel | `^thor-screenpanel-v` |
| Per-game fan profiles | `^thor-appprofiles-v` |
| Stick calibration | `^thor-stickcal-v` |

Obtainium calls this a regular-expression filter on releases; the exact wording
of the field varies a little between versions.

The Magisk modules are not covered — Obtainium installs apps, and Magisk has no
equivalent auto-update for sideloaded zips. Those stay manual.

That's it. The AYN button panel appears when you press the AYN button, below the
bottom screen.

### If something doesn't work

- **A tile says it needs root.** You missed step 4. Magisk app → *Superuser* →
  **+** → add the app, then tap the tile again. Magisk 31 never prompts, so
  nothing will remind you.

  Don't go looking for the app in that list first — an app you haven't added
  isn't in it.

- **A tile names the wrong setting.** Tap it — it re-reads the device and
  corrects itself.

  Between taps a tile shows the last setting it saw, so that it does not have to
  ask for root just to draw a label. If you changed the fan or the stick LEDs
  from the AYN panel instead of from the tile, the tile's label lags behind
  until you next tap it.

- **A tile does nothing at all.** Its module isn't installed, or Magisk has
  staged it and is waiting for a reboot.
- **An app won't install.** If you have an older version, uninstall it first.
  Android refuses to upgrade an app whose signing key changed.
- **A tile vanished after reinstalling its app.** It hasn't — Android re-adds it
  at the end of your Quick Settings, so look on the later pages before adding it
  again.
- **Root stops working after reinstalling an app.** Reinstalling gives the app a
  new internal ID, so its Magisk permission doesn't carry over — and the app
  disappears from the Superuser list. Add it again with the **+** button, as in
  step 4. Updating an app in place (same version line) does not do this; only a
  full uninstall and reinstall does.
- **The AYN panel doesn't appear.** It needs four modules present; its own
  installer will tell you which one is missing.

Each component folder has its own README with more detail.

---

## Everything else

- [`DEVELOPING.md`](DEVELOPING.md) — building, testing, CI, how the pieces
  depend on each other.
- [`docs/ayn-button-panel.md`](docs/ayn-button-panel.md) — the AYN button and
  its panel, in depth.
- [`THIRD-PARTY.md`](THIRD-PARTY.md) — the five files here that aren't ours, and
  their terms. Read it before redistributing.
- [`LICENSE`](LICENSE) — Apache-2.0.

Everything here is installed and in daily use on the author's own Thor. It is a
personal project for one device on one ROM, and is not affiliated with AYN or
with LineageOS.
