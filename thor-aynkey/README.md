# thor-aynkey

Binds the AYN Thor's physical **AYN button** — the one below the 3.92" bottom
screen — to a short-press and a long-press action.

```
aynkeyd status      daemon, resolved input device, configured action
aynkeyd start       run it (service.sh does this at boot)
aynkeyd stop
aynkeyd device      print the resolved input node
```

| press | fires | 
|---|---|
| short | on release |
| long | **at 2000 ms, while still held** |

Both run `/data/adb/thor-aynkey/action.sh <short|long>`.

## Requires

- Magisk and root.
- **An action script at `/data/adb/thor-aynkey/action.sh`.** The daemon detects
  presses and decides nothing; with no action script it only logs. There is a
  test asserting it has no built-in action. `thor-screenpanel`'s `install.sh`
  places one, or write your own.

Nothing else. The module reads one input node and writes no hardware node and
no system property.

## Version compatibility

The seam is the action script, and it is a two-word contract: `aynkeyd` runs

```
action.sh short
action.sh long
```

`long` fires **mid-hold at 2000 ms**, not on release. A script written for an
on-release daemon will feel wrong rather than fail.

The daemon also exposes `aynkeyd start|stop|status|device`. `device` prints the
resolved evdev node and is the supported way to find it — the index moves
between boots, so never hardcode one.

## This module does nothing on its own

It detects presses. It does not decide what they mean. With no action script
installed it only logs (`logcat -s aynkeyd`), which makes it safe to install
alone. `thor-screenpanel`'s `install.sh` supplies the script that wires the
button to the panel and the screen toggle.

## Why a root daemon

The button is `KEY_F24` on `gpio-keys`, and **nothing in LineageOS binds it** —
no `.idc`, no global-key config. But an app only receives key events while it
holds focus, which is useless for a button whose job is to open something, and
global key bindings are a framework resource unreachable from a module. So the
daemon reads the evdev node directly. `getevent` opens it non-exclusively, so
the framework still delivers `KEYCODE_F24` to whatever is focused and no
existing input behaviour changes.

## Safety

Reads one input node. **Writes no hardware node and no system property.**
Stopping the daemon, or disabling the module and rebooting, returns the button
to being an unbound key. There is a test asserting the daemon writes nothing
under `/sys`.

## Threshold

One constant, `LONG_PRESS_MS`, currently `2000`. A host test derives it from
behaviour rather than reading the assignment, so changing it to 3000 needs the
constant and the test expectation and nothing else.

## Tests

```
./tests/test_parse.sh     16 cases - the press rules, on the host
./tests/test_module.sh    18 cases - packaging, boot wait, no-kill guards
./build.sh                syncs, runs both, then zips
```

`sendevent` drives real presses without a human:

```sh
sendevent /dev/input/event0 1 194 1; sendevent /dev/input/event0 0 0 0   # down
sendevent /dev/input/event0 1 194 0; sendevent /dev/input/event0 0 0 0   # up
```

(Resolve the node with `aynkeyd device` — it moves between boots.)

