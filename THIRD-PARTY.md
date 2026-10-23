# Third-party files

This repository is mostly original shell, Java and Magisk packaging. Three
files come from elsewhere, and their terms are not ours to set. They are listed
here so anyone redistributing this work knows exactly what they are handling.

## 1. `thor-wifi-kiwi-fw/system/vendor/firmware/kiwi/amss20.bin`

| | |
|---|---|
| **What** | QCA `kiwi_v2` WLAN firmware for the Qualcomm QCS8550 |
| **Size / sha256** | 6 299 712 bytes — `a4ea7e02c6d5273183776323aec917665cf10205f41bb94aa15e5713341da4ff` |
| **Licence** | Qualcomm proprietary. No licence is granted by this repository. |
| **Origin** | Supplied by a LineageOS device maintainer for `odin2thor`, who is considering shipping it in LineageOS by default — [LineageOS/issues#880](https://github.com/LineageOS/issues/issues/880) |
| **Also published at** | `TheMuppets/proprietary_vendor_ayn_qcs8550-common`, `proprietary/vendor/firmware/kiwi/amss20.bin` — the standard LineageOS vendor-blob repository |

It is included rather than stripped because the module's entire purpose is to
place this one file, and because the same blob is already publicly distributed
through TheMuppets for this exact device. If Qualcomm or AYN object, the
correct remedy is to remove the file and have users source it from TheMuppets
themselves; the module works unchanged either way.

## 2. `thor-sdcard-fsck/fsck.exfat`

| | |
|---|---|
| **What** | `exfatprogs` 1.1.1, `fsck.exfat`, arm64 |
| **Size** | 40 KB (36 952 bytes as shipped by AYN) |
| **Licence** | **GPL-2.0** — `exfatprogs` is free software |
| **Upstream source** | <https://github.com/exfatprogs/exfatprogs>, tag `1.1.1` |
| **Origin** | Extracted from the AYN Thor stock OTA (`Thor_V1.0.0.293_20251121`), `system` partition, `/system/bin/fsck.exfat` |

**Corresponding source.** `exfatprogs` is GPL-2.0, so a binary distribution
carries an obligation to make the matching source available. The upstream
`1.1.1` tag above is that source. This repository does not and cannot warrant
that AYN's build is byte-reproducible from it — the binary was taken from a
vendor image, not built here, and AYN may have applied changes. Anyone who
wants a binary with a clean provenance chain should build `1.1.1` from upstream
for arm64 and drop it in; see `thor-sdcard-fsck/README.md`.

The reason the stock binary is used at all is purely that 1.1.1 checks a
461 GB / 158k-entry card in 17 s where LineageOS's 1.4.3 needs 58 s, and
`vold` kills `fsck.exfat` at a hardcoded 45 s.

## 3. `thor-display-match/tests/fixtures/vendor-display_settings.xml`

| | |
|---|---|
| **What** | LineageOS's own `/vendor/etc/display_settings.xml` for this device |
| **Licence** | **Apache-2.0** — SPDX headers intact in the file |
| **Origin** | The LineageOS 24 vendor image, used as a test fixture |

Kept as a fixture because `display-match` generates its overlay *from the ROM's
own file*, so the only honest test of that generator is the real file.

## This repository's own terms

**No licence has been chosen yet.** Until one is added, all rights are reserved
and the code here is published for reference rather than reuse. The two
binaries above keep their own terms regardless of what licence is eventually
chosen for the original work — and the GPL-2.0 file above is a live constraint
on that choice.

The project also builds on LineageOS, which is Apache-2.0, and targets a device
whose stock software is proprietary. Nothing extracted from AYN's stock ROM is
published here beyond the one binary in §2: the reference APKs, the decompiled
classes and the stock images used during research are deliberately kept out of
this repository.
