#!/system/bin/sh
# Nothing to restore.
#
# This module only ever overlays one file. Removing the module unmounts the
# overlay and the ROM's own /vendor copy is what the framework reads again at
# the next boot. No hardware node is written and no state is kept.
#
# Any per-display brightness set while it was active lives in the framework's
# own brightness state, which reverts to following the main panel as soon as
# the lead-display relationship is back.
exit 0
