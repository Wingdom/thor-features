# Thor WiFi on LineageOS 24 — RESOLVED 2026-10-21

**Root cause: a missing proprietary blob in the LineageOS vendor image.**

`/vendor/firmware` has load priority over `/vendor/firmware_mnt` (the modem
partition). AYN's stock ROM ships `/vendor/firmware/kiwi/`; LineageOS does not,
so the WLAN driver falls back to the modem partition's `amss20.bin`, which
breaks SAE on 6GHz.

    on device (firmware_mnt, used as fallback)
      /vendor/firmware_mnt/image/kiwi/amss20.bin
      6385728 bytes  sha256 429fc931cc13bef5468a2b48f3de89f997d01bcb81db001f638ea0e6e2587547

    correct blob (TheMuppets proprietary_vendor_ayn_qcs8550-common)
      proprietary/vendor/firmware/kiwi/amss20.bin
      6299712 bytes  sha256 a4ea7e02c6d5273183776323aec917665cf10205f41bb94aa15e5713341da4ff

## Status: superseded

**Use [`thor-wifi-kiwi-fw`](../thor-wifi-kiwi-fw/) instead.** That module fixes
6 GHz by supplying the correct firmware blob; this one disables the band
altogether. This is kept for the diagnosis and as a fallback if the firmware
overlay cannot be used.

Do not run both: there is no file conflict, but this module would disable the
band the other one repairs.

## Requires

Magisk and root. Nothing else.

## Symptom
Endless "Connecting… / Authentication problem" on any SSID with a 6GHz radio.
Driver discarded the 6GHz beacons and then failed to transmit the auth frame:

    __scm_handle_bcn_probe: Drop frame(128) with Invalid AKM suite 400 for 6GHz(6935)
    Auth TX: fail (3)
    lim_process_sae_auth_timeout: SAE auth timeout

After the fix, same AP, same band:

    Connecting to <ssid> <bssid> freq: 6935 akm 0x400
    Auth TX: success (0)

Connected on 6935MHz, WPA3-SAE, 2401Mbps max rate, 0% packet loss.

## The fix
Magisk module `thor-wifi-kiwi-fw` overlays the correct blob:

    /data/adb/modules/thor-wifi-kiwi-fw/
      module.prop
      system/vendor/firmware/kiwi/amss20.bin   root:root 0644
                                               u:object_r:vendor_firmware_file:s0

The SELinux context matters — it must match the other files in
`/vendor/firmware` (`vendor_firmware_file`), not the modem partition's
`firmware_file`.

Revert: `touch /data/adb/modules/thor-wifi-kiwi-fw/disable` and reboot.

## Superseded: the 6GHz scan workaround
`thor-wifi-no6g` set `scan_mode_6ghz=0` and `scan_policy_colocated_6ghz=0` in
`/vendor/etc/wifi/kiwi_v2/WCNSS_qcom_cfg.ini`, hiding the 6GHz radios entirely.
It worked, but cost the whole 6GHz band. **Now disabled and not needed.**
Stock ini values retained in `WCNSS_qcom_cfg.ini.orig`; the patched variant is
`WCNSS_qcom_cfg.ini.v2`.

## What was ruled out along the way
- Wrong passphrase, MAC filtering, router-side MAC lockout
- The WPA2/WPA3 auto-upgrade paired-profile bug (disabling the SAE
  SecurityParams persisted but was ignored by the framework)
- The modem partition: byte-identical (3188ed9b…e46c) on stock v1.0.0.293
  with working WiFi and on LineageOS with broken WiFi, and absent from AYN's
  OTA payload entirely (20 partitions, no modem)
