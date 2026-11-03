# thor-screenmove

Move the foreground app between the AYN Thor's two screens, from a root
command or a Quick Settings tile.

```
screenmove status                 show displays and what can be moved
screenmove toggle    [--dry-run]  move the focused screen's app to the other
screenmove to-top    [--dry-run]  move the bottom screen's app to the top
screenmove to-bottom [--dry-run]  move the top screen's app to the bottom
screenmove swap      [--dry-run]  exchange the two screens' apps
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
a breaking change. The launcher-surface rule added in v0.2.0 deliberately
changes only *values*, never keys or sentinels, and the leaf-root rule added in
v0.6.0 does the same.

`swap`, also added in v0.6.0, is purely additive: no caller uses it yet, so its
wording is not yet anyone's contract.

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

## Launcher surfaces are never moved

A `type=home` check is not enough on a device with two launcher surfaces. A
dual-screen launcher runs its second-screen UI as an **ordinary activity**, so
that task comes back `type=standard` and looks exactly like a movable app.

CocoonFE is the case this was built for:

| activity | declares | task type it gets |
|---|---|---|
| `rip.moth.cocoonshell/.MainActivity` | `CATEGORY_HOME` | `type=home` |
| `rip.moth.cocoonshell/.ExternalDisplayActivity` | `CATEGORY_SECONDARY_HOME` | **`type=standard`** |

Measured on hardware 2026-10-27, with Cocoon idle on both panels:

```
movable on bottom : 38
would move task #38 from display 1 to display 0
```

Task 38 was Cocoon's own bottom-screen UI. Moving it drags the launcher onto
the top panel and strands the bottom display. Cocoon does its own switching
between its two launcher surfaces, so the right answer with both screens on
their launchers is **nothing to move**, which is now what happens.

So in addition to the task-type rules, any task whose package registers a
launcher activity — `CATEGORY_HOME` or `CATEGORY_SECONDARY_HOME` — is skipped,
whatever task type it holds. The set is resolved at run time with
`cmd package query-activities`, not hardcoded, so it holds for whatever
launcher is installed.

Two details that matter:

- **Negative-priority matches are dropped.**
  `com.android.settings/.FallbackHome` registers as `CATEGORY_HOME` at
  `priority=-1000`. Treating Settings as a launcher would make Settings itself
  unmovable — a worse bug than the one being fixed.
- **It fails open.** If the query returns nothing, the list is empty and
  behaviour is exactly what it was before the check existed. Better to keep
  working on a build where `cmd package` differs than to refuse every move.

This changes no output wording. A screen showing only launcher surfaces now
reports `<none, launcher only>` where it previously reported a task id, and
both callers already read any non-numeric value as "nothing movable" — so the
QS tile goes inert and the panel drops the move from its plan, which is what
each should do.

## Only leaf root tasks are moved

`am display move-stack` moves a root task **and everything inside it**. A root
holding child tasks would therefore drag unrelated apps across to the other
screen along with the one the user asked about, so since v0.6.0 a root is a
candidate only when no task anywhere in the dump names it as a parent.

Split screen is the obvious case, and it was *already* excluded: it appears as a
`type=undefined` SplitRoot with the two halves nested inside, which the task
type check drops. That is one arrangement, though, not a rule — so what is
rejected now is the shape rather than the name.

**This fixes nothing observed.** No recorded fixture contains a `type=standard`
root with children, and all 21 pre-existing parser tests pass unchanged against
it, which is the evidence that it changes nothing in real output. It closes a
hole rather than a bug. The idea came from reading
`thor-back-button-hero`, which guards the same thing in its own mover
(`childTaskIds.contentEquals(intArrayOf(taskId))`).

A non-leaf root is simply not eligible, and the scan continues below it — the
same thing that already happens past a launcher package, rather than declaring
the whole display unmovable. In practice an opaque fullscreen parent leaves the
tasks below it `visible=false`, so there is usually nothing below to find.

## Swapping both screens at once

`swap` exchanges the two screens' apps. It exists because running `toggle`
twice **cannot** do this correctly: `toggle` re-reads `dumpsys` on each
invocation, and by the second read the first task is already sitting on the
destination display, where it can be picked up and moved straight back.

`swap` resolves **both** task ids from a single `dumpsys` read before issuing
either move, so the first move cannot change the second move's target. It needs
a movable app on *both* screens; one app and one launcher is the idle state and
exits `1`, the same contract the move commands use for "nothing to move".

Both ends are verified by re-reading afterwards. A half-finished swap is
reported as a failure and **deliberately not rolled back** — a second guessed
move on top of a declined one is how an app ends up stranded on the wrong
screen.

The QS tile's observed "two apps effectively swap places" behaviour, recorded in
the tile README, is the emergent result of the `toggle` rules and is unchanged.
`swap` is the explicit version of it; nothing calls it yet.

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
- **Only leaf root tasks are movable.** `move-stack` takes a root-task id; the
  parser ignores nested tasks, which carry `rootTaskId=`, and since v0.6.0 also
  ignores any root that such a task names as its parent. See above.
- **Launchers are never moved.** `type=home`, `type=recents` and the
  `type=undefined` Bubbles/SplitRoot containers are excluded — moving a home
  task would strand that display. So are tasks belonging to any package that
  registers a launcher activity, which is what catches a dual-screen
  launcher's second-screen UI; see above.
- **Invisible tasks are not candidates.** If an app is backgrounded it is not
  what the user is looking at.

## Tests

```
bash tests/test_parse.sh    # 32 - the dumpsys parser and the swap planner
bash tests/test_module.sh   # 17 - packaging and the safety invariants
bash build.sh               # sync, then test, then package
```

Fixtures in `tests/fixtures/` are **real output** captured from the device, not
hand-written approximations: `dumpsys activity activities` from 2026-10-22 with
the stock launcher, `cocoon-both-screens.txt` from 2026-10-27 with CocoonFE on
both panels, and `launcher-query.txt`, the `cmd package query-activities` reply
the launcher filter is tested against. One of them exists specifically because dumpsys
lists the *focused* display first, so `Display #1` can appear before
`Display #0` — a parser that assumed id order returns the wrong task, and that
fixture is the regression guard.

## Install

```
build.sh                      # produces dist/thor-screenmove-v0.6.0.zip
```

Flash in Magisk. `customize.sh` refuses to install unless the device is a Thor,
a second internal display exists, and the build's activity manager actually
supports `display move-stack`.
