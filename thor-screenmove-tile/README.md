# thor-screenmove-tile

A Quick Settings tile for `screenmove`: one tap sends the foreground app to
the other screen.

Requires the **`thor-screenmove` Magisk module** (which installs
`/system/bin/screenmove`) and root. The tile is only a front end — it holds no
permissions of its own and shells out to the CLI under `su`, the same shape as
`thor-fan-tile`.

## Version compatibility

The tile calls exactly these, and nothing else:

```
screenmove status
screenmove toggle
```

`screenmove status` is also the presence check — a non-zero exit is read as
"not installed or root denied". The tile parses that command's wording, so a
change to it is a breaking change; the host tests use real captured output
precisely so that such a change fails loudly.

Tested against `thor-screenmove` v0.2.0, which changed only *values* in that
block, never keys or sentinels — so no tile change was needed for it.

## What the tile shows

| situation | state | subtitle |
|---|---|---|
| shade just opened, before any tap | `INACTIVE` | Switch screens |
| module missing / root denied | `INACTIVE` | Root required |
| no second display found | `INACTIVE` | No second screen |
| only launchers on both screens | `INACTIVE` | Nothing to move |
| an app is open | `ACTIVE` | Send to top / Send to bottom |
| move in flight | `UNAVAILABLE` | Moving… |

The subtitle names the **action the tap will take**, not the current state,
because the tile has exactly one verb.

## When it asks for root

**Only on tap.** Opening Quick Settings never requests `su`.

Quick Settings calls `onStartListening()` every time the shade opens, and until
v0.5.1 the tile ran `screenmove status` from there so it could name a direction.
That spawned a `su` process on every shade open — and wrote a rejection line to
logcat every time, on a device where the app had not been granted root.

**This does not make a permission prompt appear, and nothing can.** Magisk 31
removed the interactive su request; access is granted ahead of time in the
Magisk app's Superuser tab, with the **+** button. Verified on device
2026-10-30 on Magisk 31.1. Earlier notes in this repo assumed a tap could raise
a prompt; that was true on Magisk 30.7 and is not true now.

Unlike `thor-fan-tile` and `thor-stickfx-tile`, this tile has nothing worth
caching. Its subtitle names a *direction*, and which direction a tap moves an
app depends on which display is focused and what each one is showing right now.
A remembered "Send to bottom" would be wrong as often as right, and wrong in a
way that names a specific action. So before a tap it shows **"Switch screens"** —
the verb, with no direction claimed — and the tap reads the real state under
the root it can actually obtain. The rows above for Root required, No second
screen, Nothing to move and the live directions are all **post-tap** subtitles.

`tests/test_tile_root_timing.sh`, one level up, fails the build if
`onStartListening()` can reach the CLI client again — directly or through a
helper. All three tiles share it.

**"Nothing to move" is the normal resting state on a dual-screen launcher.**
CocoonFE draws a launcher surface on *both* panels, so at idle neither screen
has anything movable and the tile sits inactive. That is correct, not a fault:
Cocoon does its own switching between its two surfaces. The tile stays
clickable and explains itself if tapped.

This changed with `thor-screenmove` v0.2.0. Before it, a launcher's
second-screen UI was offered as movable whenever it happened to get a
`type=standard` task, and the tile would read "Send to top" while idle —
tapping it dragged the bottom launcher onto the top panel. No tile code
changed; the CLI now reports `<none, launcher only>` for that screen and the
tile has always read any non-numeric value as nothing movable.

## Why it is never `STATE_UNAVAILABLE` at rest

SystemUI does not deliver `onClick()` to a tile in `STATE_UNAVAILABLE`. A tile
that renders that state when it cannot act becomes a dimmed square that does
nothing and never explains why — and the explanatory dialog is unreachable
from the UI even though the code for it exists.

This was found on device with `thor-fan-tile` on 2026-10-22: tapping an
unavailable tile produced no `su` request at all. So every resting state here
is `INACTIVE`, which keeps the tile clickable so the dialog can explain itself.
`STATE_UNAVAILABLE` is reserved for the transient "Moving…" moment, where being
unclickable is the point. `TileRender.state()` is host-tested, including an
assertion that it *never* returns `UNAVAILABLE` for a resting tile.

## Which screen it acts on

Mirrors the CLI's own `toggle` rule, in host-tested `ScreenStatus`:

1. If the **focused** screen is showing an app, move that one — it is the app
   the user is looking at.
2. Otherwise move whatever the other screen is showing, so a single tile works
   in both directions.
3. If neither screen has an app, say so rather than doing nothing silently.

## Observed behaviour with an app on each screen

Confirmed by the user on device 2026-10-22: with an app open on **each**
screen, repeated taps move apps back and forth such that the two effectively
**swap places**.

This is emergent from the rules above rather than designed, and the exact
sequence has **not** been traced step by step — the tile only ever moves one
task per tap, so the swap is a consequence of which task is topmost and where
focus lands after each move. Recorded as observed, not as specified. If this
ever needs to be relied on, trace it properly first.

## Safety

The tile holds **no Android permissions** and declares no Activity. Its only
capability comes from `su`, which Magisk gates per-app. Arguments passed to the
CLI are fixed literals chosen in code — never user input, never anything read
back off the device — and a test asserts no shell metacharacter can reach the
`su -c` string.

After a move it **re-reads status** rather than assuming success, because the
window manager can decline a move that `am` reports as successful.

## Tests

```
bash build.sh     # runs 52 host tests, then packages; refuses to package on failure
```

52 tests across `ScreenStatus` (parsing, including malformed and truncated CLI
output), `ScreenStatus.sourceDisplayForToggle` (direction choice),
`TileRender` (state and labels) and `ScreenmoveClient` (argv construction and
failure fallbacks, through a fake runner). The status strings used as fixtures
are **real output captured from the device**, so a change to the CLI's wording
breaks these tests — which is intended, since the tile parses that wording.

`ScreenmoveTileService` has no host tests: it is deliberately a thin shell over
the pure classes, and what is left in it is Android plumbing that only means
anything on a device.

## Install

```
build.sh
adb install -r dist/thor-screenmove-tile-v*.apk
```

Then add **Screen move** from the Quick Settings edit screen.

`targetSdk` is **37** (Android 17). Play Protect on this device blocks installs
targeting older releases with `INSTALL_FAILED_VERIFICATION_FAILURE`.
