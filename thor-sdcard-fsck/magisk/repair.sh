#!/system/bin/sh
# Manual repair using exfatprogs 1.1.1 (~17s). Rarely needed - vold's own fsck
# now completes inside its budget, so a dirty volume is repaired on mount.
D="${1:-/dev/block/mmcblk1p1}"
R=/system/bin/fsck.exfat
echo "repairing $D with exfatprogs 1.1.1"
S=$(date +%s); "$R" -y "$D"; rc=$?; E=$(date +%s)
echo "exit=$rc elapsed=$((E-S))s"
echo -n "VolumeFlags now: "; dd if="$D" bs=1 skip=106 count=2 2>/dev/null | od -An -tx1
