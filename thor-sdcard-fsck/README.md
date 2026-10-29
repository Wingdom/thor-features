# thor-sdcard-fsck — Magisk module

**Status: working, validated 2026-10-21.** exFAT SD card mounts on LineageOS 24
with a full genuine filesystem check, exactly as on AYN stock.

## Requires

Magisk and root. Nothing else — no APK, no other module, no CLI of its own.

The module ships `fsck.exfat` (`exfatprogs` 1.1.1, arm64), **built from
upstream source** by `build-fsck.sh` — it fetches the 1.1.1 release, verifies
its sha256, and cross-compiles with the NDK. Earlier versions shipped AYN's
binary from the stock OTA; it was replaced on 2026-10-29 so the GPL-2.0 source
obligation could actually be met. Measured a dead heat with AYN's build on the
461 GB card (8.9 s vs 9.0 s, cold cache). See
[`../THIRD-PARTY.md`](../THIRD-PARTY.md) for its provenance and the upstream
source, and build `1.1.1` from upstream yourself if you want a clean
provenance chain.

## Root cause

vold runs `fsck.exfat -y <dev>` before mounting a public volume and SIGTERMs it
after **45 s** (hardcoded in vold's ForkTimeout — no system property controls it).

Measured on the same 461 GB card (158,199 entries), same session:

| binary | version | size | time |
|---|---|---|---|
| AYN stock (Android 13) | exfatprogs **1.1.1** | 36,952 B | **17 s** |
| LineageOS 24 | exfatprogs **1.4.3** | 77,624 B | **58 s** |

**3.4x slower.** That is the entire bug. Nothing was ever wrong with the
filesystem — every completed check reports `clean`. Stock Android worked purely
because it shipped the older, faster binary.

The card worked when freshly formatted (43 ms, ~20 entries) and broke once the
library was restored, which made it look intermittent.

## The fix

Ship stock's `exfatprogs 1.1.1` as `/system/bin/fsck.exfat`, extracted from
`Thor_V1.0.0.293_20251121` stock OTA. A real check and repair runs on every
mount, inside vold's budget, with ~28 s of headroom.

## Things ruled out during diagnosis

- **Not corruption** — completed checks always report `clean`, exit 0
- **Not the SELinux sysfs denial** (`fsck_untrusted` reading sysfs). A manual run
  as `u:r:magisk:s0` with no denial still took 58 s
- **Not fixable by clearing the dirty bit** — vold's own `fsck -y` sets it, then
  gets killed, re-dirtying it every attempt
- **Not fixable by reformatting** — time scales with entry count, not freshness
- **ext4 works** (`e2fsck` 274 ms) but loses exFAT cross-platform hot-swap

## Approaches tried and rejected

1. **Skip-the-scan wrapper** (v1.0): a script reading the exFAT VolumeDirty bit,
   exiting 0 when clean. Worked, but skipped checking entirely and refused dirty
   volumes until a manual repair.
2. **Wrapper that hands off to the stock binary**: blocked by SELinux —

       avc: denied { execute_no_trans } path="/system/bin/fsck.exfat.stock"
            scontext=u:r:fsck_untrusted:s0 tcontext=u:object_r:fsck_exec:s0

   `fsck_untrusted` cannot exec any second binary. Would need a `sepolicy.rule`.
3. **Plain binary swap** (v2.0, current): simplest and strictly best. No script,
   no exec, no policy change, real checking every mount.

## Install

    M=/data/adb/modules/thor-sdcard-fsck
    adb push fsck.exfat repair.sh module.prop /data/local/tmp/
    adb shell "su -c 'mkdir -p $M/system/bin \
      && cat /data/local/tmp/fsck.exfat > $M/system/bin/fsck.exfat \
      && cat /data/local/tmp/repair.sh  > $M/repair.sh \
      && cat /data/local/tmp/module.prop > $M/module.prop \
      && chmod 755 $M/system/bin/fsck.exfat $M/repair.sh \
      && chown -R 0:0 $M \
      && chcon u:object_r:fsck_exec:s0 $M/system/bin/fsck.exfat'"
    adb reboot

`chcon` matters: `file_contexts` matches `/system/bin/fsck\.exfat` exactly, and a
wrongly-labelled file will not be executable by vold.

## Disable / remove

    adb shell "su -c 'touch /data/adb/modules/thor-sdcard-fsck/disable'"   # then reboot
    adb shell "su -c 'rm -rf /data/adb/modules/thor-sdcard-fsck'"          # then reboot

Removing it restores Lineage's 1.4.3 and the card becomes unmountable again.

## Headroom

17 s against a 45 s budget. Check time scales with entry count (~3,000/s), so
the card can grow from 158k entries to roughly 400k before this becomes tight.

## Unrelated gotcha

vold defers scanning removable storage until the device is unlocked after boot:

    vold: Found disk at .../mmcblk1 but delaying scan due to secure keyguard

The card won't appear until you unlock the screen. Not a module issue — it cost
us several confused minutes.

## How the binary was obtained

`Thor_20251120_ota.zip` is an A/B payload OTA. Extracted with a minimal
stdlib-only Python parser (no protobuf dependency): parse the `CrAU` header,
walk the manifest for the `system` partition's 477 install operations
(REPLACE / REPLACE_BZ / REPLACE_XZ / ZERO), reassemble `system.img`, then
`debugfs -R "dump /system/bin/fsck.exfat ..."` to pull the file out.
