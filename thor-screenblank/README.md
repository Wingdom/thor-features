# thor-screenblank

Turns either of the AYN Thor's two internal panels **genuinely off** — dark
with no glow, and not accepting touch.

```
screenblank status
screenblank off    <top|bottom>
screenblank on     <top|bottom>
screenblank toggle <top|bottom>
screenblank restore      kill switch: both screens on, touch enabled
```

## Requires

Magisk and root. Nothing else — no APK, no other module, no boot service.

## Version compatibility

`thor-screenpanel` drives this CLI and depends on exactly these:

```
screenblank status
screenblank off <top|bottom>
screenblank on  <top|bottom>
```

`screenblank restore` is the kill switch and should stay callable by anything.

A `litscreen` subcommand existed briefly during development and **was
removed** — nothing should depend on it. If you find a reference to it, the
reference is stale, not the binary.

## Off is two writes

| half | node | effect |
|---|---|---|
| darkness | `<panel>-backlight/bl_power` = `1` | panel genuinely off |
| touch | `<digitiser>/fts_irq` = `0` | stops reporting entirely |

`bl_power` alone is not enough: with the bottom panel dark, **24 touch
down/up pairs** were recorded on its digitiser — the user pulled the
notification shade down on a screen they could not see. With `fts_irq=0` the
same test logged **zero** events.

## Not stock's mechanism

Stock covered the screen with an opaque `type = 2021` window and set
`sys.temp.disabled.touch`. `2021` is `TYPE_BOOT_PROGRESS` (signature-level, and
why stock's cover sat above the system bars), and the property has no consumer
anywhere on LineageOS. The unprivileged overlay substitute was built and
rejected: it cannot darken a panel, only paint it black, and cannot outrank the
status bar. `bl_power` is the lever stock itself used on panel0 for HDMI-out.

## Safety

- **Never both panels dark.** `off` refuses if the other one already is.
- **Touch dies before the light, and returns after it** — so there is no
  moment where a dark panel accepts input.
- **`restore`** is unconditional and consults no recorded state.
- **`uninstall.sh` relights both panels by hand**, without calling the binary,
  because by then the binary may be gone. Tested against a fake sysfs tree,
  including the hardware-absent case.
- No thermal, battery or charging node is touched — there is a test for that.
- A reboot clears both writes.

## `brightness` is deliberately never restored

It read `205` one day and `3117` an hour later: the framework owns that node
and moves it with auto-brightness. Only `bl_power` is ours, so only `bl_power`
is put back. An implementation restoring a remembered `205` would pin the panel
to a brightness the user did not choose.

## Everything resolves by name

Input indices move between boots — the bottom digitiser was `input28` and then
`input26` across one reboot. There is a test asserting no literal input index
appears anywhere in the module, and that `fts_ts` never resolves to
`fts_ts_secondary`.

## Tests

```
./tests/test_parse.sh     10 cases - name resolution
./tests/test_module.sh    17 cases - packaging + the revert path
```

## Known gap

**`screenblank off top` has never been run on hardware.** It is implemented and
guarded, but the AYN button's long press only toggles the *bottom* screen, so
recovery from a failure there needs adb. Test it deliberately, with adb
attached.

