#!/system/bin/sh
# Nothing to restore.
#
# screenmove never writes a hardware node, a system property, a settings key or
# any persistent state. It issues one activity-manager call per invocation, and
# a reboot undoes any move. Removing the script is therefore a complete
# uninstall - unlike thor-fanctl, there is no recorded original to put back and
# no boot-time behaviour to unwind.
#
# Any app currently sitting on the "wrong" screen stays there until it is moved
# back or the device reboots. That is a task placement, not a modification.
exit 0
