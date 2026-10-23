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

## Relationship to `thor-wifi-no6g`

`thor-wifi-no6g` was the earlier workaround: it disables the 6 GHz band
outright, leaving 2.4/5 GHz working. **This module supersedes it** — it fixes
6 GHz rather than giving it up. Do not run both: there is no conflict in the
files they touch, but `no6g` would disable the band this module repairs.

## Requires

- Magisk (any version that supports `/system` overlay modules).
- Nothing else. No other module, no APK, no CLI.

## Install

```sh
adb push thor-wifi-kiwi-fw /data/local/tmp/
adb shell "su -c 'M=/data/adb/modules/thor-wifi-kiwi-fw
  mkdir -p \$M/system/vendor/firmware/kiwi
  cat /data/local/tmp/thor-wifi-kiwi-fw/system/vendor/firmware/kiwi/amss20.bin \
    > \$M/system/vendor/firmware/kiwi/amss20.bin
  cat /data/local/tmp/thor-wifi-kiwi-fw/module.prop > \$M/module.prop
  chown -R 0:0 \$M'"
adb reboot
```

Then verify the running system actually picked it up, rather than trusting the
reboot:

```sh
adb shell su -c 'sha256sum /vendor/firmware/kiwi/amss20.bin'
```

## Disable / remove

```sh
adb shell "su -c 'touch /data/adb/modules/thor-wifi-kiwi-fw/disable'"   # then reboot
adb shell "su -c 'rm -rf /data/adb/modules/thor-wifi-kiwi-fw'"          # then reboot
```

Removing it returns the driver to the modem fallback blob, and 6 GHz breaks
again.

## Tests

None. There is no logic to test — the module is a single file placed at a
single path. The only meaningful check is the `sha256sum` above, run against
the live system after a reboot.
