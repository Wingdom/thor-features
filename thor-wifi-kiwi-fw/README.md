# thor-wifi-kiwi-fw

**Status: working.** Fixes 6 GHz WiFi on the AYN Thor under LineageOS by
supplying the correct `kiwi` WLAN firmware blob.

This module is a pure `/system`-overlay: it has no scripts, no boot service and
no CLI. Its entire payload is one file.

## The bug

LineageOS ships no `kiwi` directory under `/vendor/firmware`. Because
`/vendor/firmware` has load priority over `/vendor/firmware_mnt` (the modem
partition), the driver falls back to the modem's copy of `amss20.bin` — which
is a different, older build that breaks WPA3-SAE on 6 GHz.

The 6 GHz radios are SAE-only. The driver rejects SAE on 6 GHz but still
selects those BSSIDs, so authentication fails in a loop:

```
__scm_handle_bcn_probe: Drop frame(128) with Invalid AKM suite 400 for 6GHz(6935)
Auth TX: fail (3)
lim_process_sae_auth_timeout: SAE auth timeout
```

The symptom is endless "Connecting… / Authentication problem" on any SSID with
a 6 GHz radio.

## The fix

Overlay `/vendor/firmware/kiwi/amss20.bin` with the known-working blob.

| | size | sha256 |
|---|---|---|
| **correct** (this module) | 6 299 712 | `a4ea7e02c6d5273183776323aec917665cf10205f41bb94aa15e5713341da4ff` |
| broken (modem fallback) | 6 385 728 | `429fc931cc13bef5468a2b48f3de89f997d01bcb81db001f638ea0e6e2587547` |

After the fix, same AP and same band: `Auth TX: success (0)`, connected on
6935 MHz, WPA3-SAE, 2401 Mbps max rate, 0% packet loss.

## Provenance of the blob

The firmware is Qualcomm proprietary. It is included here because:

- it was **supplied by a LineageOS device maintainer**, who is considering
  shipping it in LineageOS by default —
  [LineageOS/issues#880](https://github.com/LineageOS/issues/issues/880); and
- it is already publicly distributed in the standard LineageOS vendor-blob
  repository, **`TheMuppets/proprietary_vendor_ayn_qcs8550-common`**, at
  `proprietary/vendor/firmware/kiwi/amss20.bin`.

Verify what you have before trusting it:

```sh
sha256sum system/vendor/firmware/kiwi/amss20.bin
# expect a4ea7e02c6d5273183776323aec917665cf10205f41bb94aa15e5713341da4ff
```

See [`THIRD-PARTY.md`](../THIRD-PARTY.md) for the licensing position on this
file.

## Diagnosis: what this cost to find

The symptom was endless "Connecting… / Authentication problem" on any SSID
with a 6 GHz radio, and the driver log said why:

```
__scm_handle_bcn_probe: Drop frame(128) with Invalid AKM suite 400 for 6GHz(6935)
Auth TX: fail (3)
lim_process_sae_auth_timeout: SAE auth timeout
```

The 6 GHz radios are SAE-only. The driver rejected SAE on 6 GHz yet still
selected those BSSIDs, so authentication failed in a loop.

### Ruled out along the way

- Wrong passphrase, MAC filtering, router-side MAC lockout.
- The WPA2/WPA3 auto-upgrade paired-profile bug — disabling the SAE
  `SecurityParams` persisted but was ignored by the framework.
- **The modem partition itself.** Byte-identical on stock v1.0.0.293 with
  working WiFi and on LineageOS with broken WiFi, and absent from AYN's OTA
  payload entirely (20 partitions, no modem). This is what pointed at
  `/vendor/firmware` load priority as the real difference.

### The earlier workaround, and why it is gone

Before the firmware was available, the band was simply hidden: setting
`scan_mode_6ghz=0` and `scan_policy_colocated_6ghz=0` in
`/vendor/etc/wifi/kiwi_v2/WCNSS_qcom_cfg.ini` made the 6 GHz radios invisible
to the scanner. It worked and cost the entire 6 GHz band.

That lived in a separate `thor-wifi-no6g` module, **removed on 2026-10-30**.
Keeping a module that disables the band this one repairs was a footgun with no
upside, and it had no payload in the repository anyway. The stock and patched
ini values are kept outside the repository with the rest of the reference
material, at `../../thor/reference/wifi/`.

## Requires

- Magisk (any version that supports `/system` overlay modules).
- Nothing else. No other module, no APK, no CLI.

## Install

```sh
./build.sh                       # runs the payload tests, then packages
```

Flash `dist/thor-wifi-kiwi-fw-v1.0.zip` in Magisk and reboot.

Then verify the running system actually picked it up, rather than trusting the
reboot — Magisk stages module updates and swaps them at the next boot:

```sh
adb shell su -c 'sha256sum /vendor/firmware/kiwi/amss20.bin'
# expect a4ea7e02c6d5273183776323aec917665cf10205f41bb94aa15e5713341da4ff
```

The SELinux context matters: the file must end up labelled
`vendor_firmware_file`, matching the rest of `/vendor/firmware`, rather than
the modem partition's `firmware_file`. Magisk derives the label from the
target path, so flashing the zip gets this right; hand-placing the file into
`/data/adb/modules/` does not.

## Disable / remove

```sh
adb shell "su -c 'touch /data/adb/modules/thor-wifi-kiwi-fw/disable'"   # then reboot
adb shell "su -c 'rm -rf /data/adb/modules/thor-wifi-kiwi-fw'"          # then reboot
```

Removing it returns the driver to the modem fallback blob, and 6 GHz breaks
again.

## Tests

```sh
./build.sh      # runs 10 host tests, then packages; refuses to package on failure
```

There is no logic to test — the payload is one file at one path — so the tests
assert the two things that are easy to get wrong and silent when wrong:

- the blob is the **known-working** firmware by sha256, and specifically **not**
  the broken modem-partition fallback, which is a plausible 6 MB file with the
  same name;
- the overlay targets `/vendor/firmware/kiwi` exactly, since
  `/vendor/firmware` is the only path with load priority over
  `/vendor/firmware_mnt`. A typo here installs cleanly and changes nothing.

They also assert the module stays a pure overlay with no scripts, and that the
blob is not duplicated at the component root where it would drift out of sync
with the copy that actually gets installed.

The remaining check can only be done on a device: the `sha256sum` against the
live `/vendor` after a reboot, above.
