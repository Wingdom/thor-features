# thor-screenmove

Move the foreground app between the AYN Thor's two screens, from a root
command or a Quick Settings tile.

```
screenmove status                 show displays and what can be moved
screenmove toggle    [--dry-run]  move the focused screen's app to the other
screenmove to-top    [--dry-run]  move the bottom screen's app to the top
screenmove to-bottom [--dry-run]  move the top screen's app to the bottom
```

## Requires

Magisk and root. Nothing else. There is no boot-time behaviour, and no
persistent state — every move is undone by a reboot.

## Version compatibility

Two consumers depend on this CLI:

| caller | commands used |
|---|---|
| `thor-screenmove-tile` | `screenmove status`, `screenmove toggle` |
| `thor-screenpanel` | `screenmove status`, `screenmove to-top`, `screenmove to-bottom` |

**`screenmove` exits `1` for "nothing to move".** That is the normal idle
state, not a failure. A caller that treats a non-zero exit as fatal will break:
this exact mistake broke the panel's "Only bottom screen" mode entirely.

`screenmove status` output wording is parsed by both callers, so changing it is
a breaking change.

## Why it exists

LineageOS starts an activity on whichever display currently holds top focus
(`mTopFocusedDisplayId`). On the Thor, `/vendor/etc/display_settings.xml` marks
the bottom panel `isHomeSupported="true"`, so a launcher runs down there;
touching the bottom screen makes it the focused display, and the next app opens
on the 3.92" panel. Nothing in the ROM moves it back.

Stock AYN had no answer to this either. Its `display_app_whiteList` key, which
looks like a per-app screen assignment, is written by the stock settings UI and
**read by nothing in any of the eight stock APKs** — the placement policy lived
in AYN's modified framework.

## Why `am display move-stack`

Moving an existing task across displays requires `MANAGE_ACTIVITY_TASKS`, which
is signature-level. No ordinary app can hold it — which is why no launcher
offers this, including ones built for dual-screen handhelds. The activity
manager shell already exposes the operation, so as root we get it with no
framework patch and no privileged app.

Verified on hardware 2026-10-22: a task moved 0 → 1 → 0 with a `dumpsys`
read-back after each move, and the round trip restored the exact baseline —
same display, same visibility, same pid.

## What it does not do

**It does not change where apps launch by default.** That is a focus-policy
question. The framework's own switch for it is
`DisplayContent.canStealTopFocus()`, and it is unreachable: not one of the 20
attributes `display_settings.xml` accepts, no `wm`/`cmd window` verb, and the
alternative gate is a display-device flag.

`dontMoveToTop="true"` *does* reach the behaviour — tested, and it correctly
stopped the bottom screen taking focus — but **it turns the bottom panel off
entirely**, so it was rejected.

## Safety

This module is unusually low-risk for this project, and deliberately so:

- It writes **no hardware node** — nothing under `/sys` or `/proc`.
- It writes **no persistent state** — no settings key, no system property, no
  state directory. A reboot undoes any move.
- It has **no boot-time behaviour**. There is no `service.sh`,
  `post-fs-data.sh` or `boot-completed.sh`, and a test asserts their absence.

That makes the kill switch trivial: `touch /data/adb/modules/thor-screenmove/disable`
plus a reboot removes the command and changes nothing else. There is no
recorded original to restore, because nothing was modified.

Each move is verified by re-reading `dumpsys` afterwards rather than trusting
`am`'s exit code — `am` reports success for calls the window manager declines.

## Behaviour notes, all measured

- **The move fronts the task.** A backgrounded app comes to the foreground on
  the destination and pushes that display's launcher out of view.
- **Focus follows the move.** `mTopFocusedDisplayId` tracks the task. Useful
  side effect: moving an app to the top screen also makes the top screen the
  next launch target.
- **Task ids are reassigned on every launch**, so they are read fresh on each
  invocation and never cached.
- **Only root tasks are movable.** `move-stack` takes a root-task id; the
  parser ignores nested tasks, which carry `rootTaskId=`.
- **Launchers are never moved.** `type=home`, `type=recents` and the
  `type=undefined` Bubbles/SplitRoot containers are excluded — moving a home
  task would strand that display.
- **Invisible tasks are not candidates.** If an app is backgrounded it is not
  what the user is looking at.

## Tests

```
bash tests/test_parse.sh    # 13 - the dumpsys parser, against recorded fixtures
bash tests/test_module.sh   # 17 - packaging and the safety invariants
bash build.sh               # sync, then test, then package
```

Fixtures in `tests/fixtures/` are **real `dumpsys activity activities` output**
captured from the device on 2026-10-22 with the stock launcher, not
hand-written approximations. One of them exists specifically because dumpsys
lists the *focused* display first, so `Display #1` can appear before
`Display #0` — a parser that assumed id order returns the wrong task, and that
fixture is the regression guard.

## Install

```
build.sh                      # produces dist/thor-screenmove-v0.1.0.zip
```

Flash in Magisk. `customize.sh` refuses to install unless the device is a Thor,
a second internal display exists, and the build's activity manager actually
supports `display move-stack`.
