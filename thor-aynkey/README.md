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

The daemon also exposes `aynkeyd start|stop|status|device|log`. `device` prints
the resolved evdev node and is the supported way to find it — the index moves
between boots, so never hardcode one.

## This module does nothing on its own

It detects presses. It does not decide what they mean. With no action script
installed it only logs (`aynkeyd log`), which makes it safe to install
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

## The log

```sh
aynkeyd log          # the last 80 lines
aynkeyd log 500      # more
aynkeyd status       # size, and the most recent line
```

`/data/adb/thor-aynkey/aynkeyd.log`, capped at 128 KiB with one previous
generation kept, so the pair never exceeds 256 KiB. `aynkeyd log` prints both
in order, so a problem that straddles a rotation still reads as one stream.

**Read this rather than `logcat -s aynkeyd`.** The daemon logs to both, but
logcat's main buffer on this device retains **under six minutes** — measured
`Logspan 5:52`, because a boot pushes some 12 MB through a 256 KiB ring. The
daemon's own start lines were already gone ten minutes after boot. By the time
you notice the button is dead, logcat has nothing. Logcat is still the better
*live* view: `logcat -s aynkeyd` while you press the button.

One more reason to prefer the file: logcat's pid column does not belong to the
daemon. `log` is a separate process, so each line is credited to a transient
pid — a daemon at pid 7481 produced lines attributed to 7485 and 7491. The file
records the daemon's own pid.

### What it records

```
2026-11-03 14:43:57 8032 main: started (threshold 2000ms, key KEY_F24, log ...)
2026-11-03 14:43:57 8032 main: reading /dev/input/event0 (connection 1)
2026-11-03 14:44:07 8032 reader.1: short press -> /data/adb/thor-aynkey/action.sh
2026-11-03 14:44:07 8032 reader.1: short action exited rc=0
2026-11-03 14:44:10 8032 timer.2: long press -> /data/adb/thor-aynkey/action.sh
2026-11-03 14:44:29 8228 main: stop requested (pid 8032) by uid 0
2026-11-03 14:44:29 8032 main: exiting on SIGTERM
2026-11-03 14:44:29 8032 main: shell exiting (rc=143)
```

Date, time, the daemon's pid, which part spoke, then the message.

The stage matters because the daemon is a pipeline of three independently
mortal processes — `getevent | emit_events | while read` — plus one timer
process per press. Each names itself (`getevent.N`, `parser.N`, `reader.N`,
`timer.N`) and reports its own exit status, so a death says which part went.

### Diagnosing a dead button

| the log's last lines | what happened |
|---|---|
| `stop requested ... by uid N` | something asked it to stop; uid N asked |
| `exiting on SIGTERM` / `SIGINT` / `SIGHUP` / `SIGQUIT` | that signal arrived from outside |
| `shell exiting (rc=...)` with no signal line | the shell itself exited; `rc` is why |
| `getevent exited rc=...` then `reading ...` again | the input device went away and was reconnected; normal when a pad enumerates |
| nothing at all after a press | **SIGKILL, or the kernel.** Neither can be trapped, so the absence of an exit line *is* the finding — look for an OOM or an lmkd kill next |

Start it again with `aynkeyd start`, or tap the **AYN Panel** icon, which does
that for you and then opens the panel.

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
./tests/test_log.sh       21 cases - log format, size bound, signal traps
./tests/test_live.sh      19 cases - the real loop against a fake getevent
./tests/test_module.sh    38 cases - packaging, boot wait, no-kill guards
./build.sh                syncs, runs all four, then zips
```

`test_live.sh` runs the **real** daemon — real timer, real `mkdir` arbiter,
real dispatch — with no device, by putting a fake `getevent` on `PATH` and
pointing `AYNKEYD_STATE_DIR` at a temp directory. It takes about 16 seconds,
because a long press is 2.6 s of wall clock and there are several.

Those two environment variables, plus `AYNKEYD_LOG` and `AYNKEYD_LOG_MAX`,
exist for the tests. Nothing sets them in a real install.

`sendevent` drives real presses without a human:

```sh
sendevent /dev/input/event0 1 194 1; sendevent /dev/input/event0 0 0 0   # down
sendevent /dev/input/event0 1 194 0; sendevent /dev/input/event0 0 0 0   # up
```

(Resolve the node with `aynkeyd device` — it moves between boots.)

