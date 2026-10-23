#!/system/bin/sh
# AYN button actions, run by aynkeyd with "short" or "long".
#
# short : open the mode panel on the bottom screen, the way stock's short
#         press did. The panel is bottom-screen only - it is never shown on
#         the top screen. If the bottom screen is off, turn it on first and
#         then show the panel there.
# long  : toggle the bottom screen. Stock's own binding, and a way back once
#         that screen is off - so it must not depend on the panel, the UI, or
#         anything that draws on a screen that may be dark.
PANEL=com.thorfeatures.screenpanel/.PanelActivity

# The bottom panel is the other INTERNAL display. Resolved rather than
# hardcoded to 1: display ids are assigned at boot. Same rule screenmove uses.
bottom_display() {
    /system/bin/dumpsys display 2>/dev/null \
      | tr ',' '\n' \
      | awk '
          /DisplayViewport\{type=INTERNAL/ { internal=1 }
          internal && /displayId=/ {
              if (match($0, /displayId=[0-9]+/)) {
                  id = substr($0, RSTART+10, RLENGTH-10)
                  if (id != 0) { print id; exit }
              }
              internal=0
          }'
}

case "$1" in
  long)
    # Confirm the threshold was reached, while the key is still held.
    #
    # Without this the only feedback that a long press fired is the screen
    # changing - and when the bottom screen is already off, the change is
    # subtle enough to be missed. The buzz is what makes the 2 s point
    # perceptible.
    #
    # `oneshot` and not `predefined`: tested by feel on 2026-10-22, the
    # predefined CLICK and HEAVY_CLICK effects produce NOTHING on this
    # device's vibrator HAL while still exiting 0. Only oneshot durations
    # actually fire. 150 ms was chosen over 60 ms by the user as the one that
    # reads clearly through a grip without startling.
    #
    # Backgrounded so the toggle is never delayed by the haptic call.
    /system/bin/cmd vibrator_manager synced -f oneshot 150 >/dev/null 2>&1 &

    # Normally this toggles the bottom screen, which is stock's binding.
    #
    # The exception is "Only bottom screen", where the TOP panel is the dark
    # one. Toggling the bottom screen there would be refused - screenblank
    # never allows both panels dark - so the button would have no way back
    # from the one mode you most need a way back from. Restore instead, which
    # is what ScreenMode.longPressTarget() says too: from BOTTOM_ONLY the long
    # press goes to DUAL, never to the mode that blanks what you are looking
    # at.
    if /system/bin/screenblank status | awk '$1 == "top" && $4 == "OFF" { f = 1 } END { exit !f }'; then
        /system/bin/screenblank restore
    else
        /system/bin/screenblank toggle bottom
    fi
    ;;
  short)
    # Unconditional, not conditional: `screenblank on bottom` is idempotent
    # (bl_power=0 and fts_irq=1 both are), so there is no state to check and
    # no window between checking and acting. It also makes the short press a
    # second way back from a dark bottom screen.
    /system/bin/screenblank on bottom >/dev/null 2>&1

    disp=$(bottom_display)
    # With no second display there is nothing this panel can usefully do, so
    # do nothing rather than put it on the main screen.
    [ -n "$disp" ] || exit 0
    /system/bin/am start --display "$disp" -n "$PANEL" >/dev/null 2>&1
    ;;
esac
