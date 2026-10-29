# Third-party files

This repository is mostly original shell, Java and Magisk packaging, under
Apache-2.0. Five files come from elsewhere, and their terms are not ours to set. They are listed
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
| **Size / sha256** | 34 888 bytes — `50e7e79ba518ba596acb53259fbaa38137101969fbdddc6f0d8a5832af92e02f` |
| **Licence** | **GPL-2.0** — `exfatprogs` is free software |
| **Upstream source** | <https://github.com/exfatprogs/exfatprogs>, release `1.1.1` — tarball sha256 `2a1574110e0d9ba11524744cac39454fb4084277cfa8ae53406a066600b825c8` |
| **Origin** | **Built here** from that tarball by `thor-sdcard-fsck/build-fsck.sh`, cross-compiled with NDK r26 clang for `aarch64-linux-android24` |

**Corresponding source.** `exfatprogs` is GPL-2.0, so distributing this binary
carries an obligation to make the matching source available. The release above
*is* that source, and `build-fsck.sh` is the exact recipe: it fetches that
tarball, checks its sha256, and builds. Anyone can reproduce an equivalent
binary with one command.

The binary is **not bit-for-bit reproducible** — the NDK embeds a build id — so
rebuilding yields a working equivalent, not the same sha256. That is a property
of the toolchain, not a gap in the source offer.

Earlier versions of this module shipped **AYN's** binary, lifted from the stock
OTA (`Thor_V1.0.0.293_20251121`, 36 952 bytes, sha256
`97c42e882c40b03d0d696ac03973213ad78ecf874a4d301531e61367a2e2ddf3`). That was
replaced on 2026-10-29 because the source obligation could not honestly be met
for it: it was not built here, and AYN publishes no source. Another party's
non-compliance does not transfer rights — GPL compliance is per-distributor — so
the only clean fix was to build it ourselves.

**Verified equivalent** on the Thor's 461 GB / 161 073-file card, cold cache,
read-only (`-n`):

| | run 1 | run 2 |
|---|---|---|
| AYN's binary | 9.22 s | 8.79 s |
| this build | 8.91 s | 8.88 s |

Identical reports (`clean. directories 11574, files 161073`), both
`exfatprogs version : 1.1.1`. Verified end to end: with this binary in place at
`/system/bin/fsck.exfat` (context `u:object_r:fsck_exec:s0`), `vold` unmounted
and remounted the card successfully, with no SELinux denials.

The reason 1.1.1 is used at all is purely that it checks this card inside
`vold`'s hardcoded 45 s `SIGTERM`, where LineageOS's 1.4.3 does not.

## 3. `thor-display-match/tests/fixtures/vendor-display_settings.xml`

| | |
|---|---|
| **What** | LineageOS's own `/vendor/etc/display_settings.xml` for this device |
| **Licence** | **Apache-2.0** — SPDX headers intact in the file |
| **Origin** | The LineageOS 24 vendor image, used as a test fixture |

Kept as a fixture because `display-match` generates its overlay *from the ROM's
own file*, so the only honest test of that generator is the real file.

## 4. `thor-screenbright/stock/display_layout_configuration.xml`

| | |
|---|---|
| **What** | LineageOS's own `/vendor/etc/displayconfig/display_layout_configuration.xml` for this device |
| **Licence** | **Apache-2.0** — SPDX headers intact in the file |
| **Origin** | The LineageOS 24 vendor image, pulled before the module was installed |

Kept in the repo rather than beside the local evidence so the module's tests
run for anyone who clones it. Those tests assert the overlay differs from this
file by exactly one element, which is only meaningful against the real thing.

## 5. `thor-screenbright/magisk/system/vendor/etc/displayconfig/display_layout_configuration.xml`

| | |
|---|---|
| **What** | The file above, with one `<leadDisplayAddress>` element removed |
| **Licence** | **Apache-2.0** — a derivative of LineageOS's file; SPDX headers intact |
| **Origin** | Generated from entry 4; every other byte is unchanged |

This is the whole of `thor-screenbright`. It is a modified copy of a LineageOS
file rather than original work, which is why it is listed here.

## This repository's own terms

**Apache-2.0.** See [`LICENSE`](LICENSE). Chosen to match LineageOS, which this
project extends and from which entries 3-5 derive, so the whole tree is one
licence except the GPL-2.0 binary in §2 — which is a separate executable
invoked as a subprocess, i.e. mere aggregation, and constrains nothing here.

GPL-2.0 was the one option actually ruled out, and not by §2: entry 5 is a
modified LineageOS Apache-2.0 file, and Apache-2.0 is incompatible with GPLv2
because of its patent clause. Apache-2.0 code can be taken into GPLv3; it cannot
go into GPLv2.

The project builds on LineageOS, which is Apache-2.0, and targets a device whose
stock software is proprietary. Nothing extracted from AYN's stock ROM is
published here: the reference APKs, the decompiled classes and the stock images
used during research are deliberately kept outside this repository, and §2 is
now built from upstream source rather than taken from AYN.
