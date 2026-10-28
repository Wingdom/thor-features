# thor-screenbright

Lets the AYN Thor's two internal screens have **independent brightness**.

LineageOS ties them together: the bottom panel is declared a brightness
*follower* of the main one, so both always sit at the same level. Stock had a
slider per screen. This module removes that link.

## What it does

It overlays one vendor file,
`/vendor/etc/displayconfig/display_layout_configuration.xml`, with a single
element removed:

```diff
   <display enabled="true">
     <address>4630946482288158084</address>
-    <leadDisplayAddress>4630946441858561667</leadDisplayAddress>
   </display>
```

Nothing else in the file changes, and there is a test asserting that — it also
governs which panel is enabled in each display state, and a mistake there boots
the device with a screen disabled.

## Using it

The sliders live in **`thor-screenpanel`**, the AYN button panel. Press the AYN
button and drag. This module on its own has no UI; it only makes per-screen
brightness possible.

From a shell, either screen can be set directly:

```sh
su -c 'cmd display set-brightness 0.4 --id 1'   # bottom screen to 40%
su -c 'cmd display get-brightness --id 0'       # read the main screen
```

Display ids are assigned at boot; `0` is always the main panel.

## Why it is needed at all

`cmd display set-brightness --id 1` exists on a stock ROM and appears to work —
it accepts the value and reports it back. It just never reaches the hardware.
Measured on 2026-10-22: with display 1 set to `0.1`, its backlight node stayed
pinned at `4095` because the follower relationship drives it from display 0.

With the link removed, the same command maps linearly onto the panel:
`0.01 → 41`, `0.3 → 1229`, `1.0 → 4095`.

## Safety

- Writes no hardware node and keeps no state.
- Changes exactly one element of one file; the installer **refuses to install**
  if the ROM's copy is not the one the overlay was built against, so a system
  update cannot silently have its changes overwritten.
- Disable the module and reboot to restore stock behaviour exactly. The two
  panels go back to sharing one brightness.
- Takes effect at boot, so a reboot is needed after installing or disabling.

## Requires

Magisk, and a reboot. No other module. `thor-screenpanel` provides the UI.
