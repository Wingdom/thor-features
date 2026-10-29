# thor-appprofiles

Per-app fan profiles for the AYN Thor: pick a fan tier for the apps you care
about, and it is applied automatically whenever you switch to one.

This app is the **only** user interface for the feature. There is no Quick
Settings tile and no entry in the AYN button panel, which is deliberate — a
per-app profile is set once and then forgotten, and the thing you *do* change
often, the global fan baseline, already has its own tile.

![the app list, each row with a fan tier]

## Requires

| | |
|---|---|
| `thor-appwatch` | the Magisk module that watches for app switches and applies the tier |
| `thor-fanctl` | the fan control everything is driven through |
| root | granted to this app in Magisk |

`thor-fan-tile` is optional, but it is the easiest way to set the baseline the
profiles sit on top of.

The app holds **no Android permissions at all** — not even
`QUERY_ALL_PACKAGES`. Seeing the installed apps uses a `<queries>` declaration
for launcher intents, which is narrower. Everything that touches the device
goes through `su` to the `appwatch` command.

## Version compatibility

The app calls exactly these:

```
appwatch status      (parses the `enabled:`, `daemon:` and `fanctl:` fields)
appwatch enable
appwatch disable
fanctl get           (presence check only)
```

and reads and writes `/data/adb/thor-appwatch/profiles` directly, so **the
table format is part of the contract**. Keep the app and the module at matching
versions.

## What it does

Each row is an installed app; tapping one offers **Default / Quiet / Balanced /
Performance**. Default means no override, so that app runs at whatever the
global baseline is.

Apps you have configured are pinned to the top, then games, then everything
else alphabetically — so once a handful are set, the list you came to check is
not buried among a hundred packages. There is a search box for the rest.

The switch at the top turns the whole system off without clearing your table.
Turning it off **restores the baseline immediately** rather than at the next
app switch, because a toggle that appears to do nothing reads as a bug.

## What it tells you

The banner names the most actionable problem, rather than stacking several for
one underlying cause:

| banner | meaning |
|---|---|
| Root required | Magisk has not granted this app access |
| Could not read the saved profiles | root works but the table is unreadable — **saving is refused** |
| The thor-fanctl module is missing | nothing to drive; profiles can be edited but will not apply |
| The thor-appwatch module is not installed | or Magisk has staged it and is waiting for a reboot |
| No fan tier is selected globally | per-app profiles will not engage until you pick one |

**The app still opens and shows your profiles when the module is missing.**
That is on purpose: Magisk stages module updates and swaps them at the next
boot, so there is a legitimate window on a correctly configured device where
the command is absent. Refusing to launch would look broken when nothing is
wrong.

## Two things it is careful about

**A failed read refuses to save.** "Could not read the table" and "the table is
empty" are kept distinct. The table format is shared with per-app features that
are not built yet, so overwriting a table that could not be read would silently
delete keys this app does not manage. For the same reason, saving edits the
table **in place**: unknown keys, comments, blank lines and even malformed
lines are carried through byte for byte, and only the one line being changed is
touched.

**Nothing you type reaches a shell.** Saving does not interpolate the table
into a command. The app writes the new table to its own private file and asks
root only to copy it into place, so the command line carries two fixed paths
and no package names at all. The staged file is deleted afterwards. The one
non-literal path that does reach a command is validated, and there are tests
rejecting semicolons, backticks, `$(...)`, quotes, spaces and path traversal.

## Build and install

```sh
./build.sh                                  # runs 60 host tests, then packages
adb install -r dist/thor-appprofiles-v*.apk
```

Then grant it root the first time it asks. If you miss the prompt, Magisk
records a **deny** and the app will show "Root required" until you change it in
Magisk → Superuser.

`targetSdk` is **37** (Android 17). Play Protect on this device blocks installs
targeting older releases with `INSTALL_FAILED_VERIFICATION_FAILURE`.

A plain platform APK — `aapt2` + `javac` + `d8`, no Gradle, no AppCompat, no
Material Components. Theming comes from the `@android:color/system_*` dynamic
palette, which is the real Material You palette, with a `values-night` variant.

## Tests

```
bash build.sh     # 60 host tests, then packages; refuses to package on failure
```

60 tests across `ProfileTable` (reading, editing, and the preservation rules
above), `AppwatchCommands` (command construction, path validation and status
parsing) and `AppEntry` (list order, including stable tie-breaks).

`ProfilesActivity` and `RootClient.EXEC` have no host tests: they are
deliberately thin over the pure classes, and what is left is Android plumbing
that only means anything on a device.

`FanMode` is compiled in from `thor-fan-tile` rather than copied. It defines
the exact words passed to `fanctl` and written into the table, and a second
copy would be a second source of truth.
