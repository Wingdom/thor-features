package com.thorfeatures.screenmove;

/**
 * What the Quick Settings tile should show. Pure logic, unit-tested on the host.
 *
 * The important rule here is inherited from thor-fan-tile, where it was found
 * the hard way: when the tile cannot act, it must still be **clickable**.
 * SystemUI does not deliver onClick() to a tile in STATE_UNAVAILABLE, so
 * rendering that state leaves a dimmed tile that does nothing and never
 * explains itself -- the "root required" dialog becomes unreachable. Verified
 * on device 2026-10-22: tapping an unavailable tile produced no su request at
 * all.
 *
 * So STATE_UNAVAILABLE is reserved for the transient "moving..." moment, where
 * being unclickable is the point.
 *
 * The second rule is about *when* root is asked for, and was found the same
 * way. The tile used to call screenmove from onStartListening() to render its
 * subtitle, which means it asked for root every time the Quick Settings shade
 * opened. Magisk's prompt cannot take focus over the open shade, so those
 * requests were rejected rather than prompted -- the tile reported "Root
 * required" having never asked the user anything. Confirmed on device
 * 2026-10-29 across all three thor tiles: rejections in logcat, no prompt
 * shown, no policy row for the apps.
 *
 * This tile has nothing it can cache its way around, unlike thor-fan-tile and
 * thor-stickfx-tile. Its subtitle names a *direction*, and the direction
 * depends on which display is focused and what each one is showing right now.
 * A remembered "Send to bottom" would be wrong as often as right, and wrong in
 * a way that names a specific action. So before a tap it shows idleLabel() --
 * the verb, with no direction claimed -- and the tap reads the real state
 * under the root it can actually obtain.
 */
public final class TileRender {

    public static final int ACTIVE = 1;
    public static final int INACTIVE = 2;
    public static final int UNAVAILABLE = 3;

    private TileRender() {}

    /**
     * @param available the CLI responded -- module installed and root granted
     * @param status    parsed `screenmove status`
     */
    public static int state(boolean available, ScreenStatus status) {
        if (!available) return INACTIVE;                 // clickable: dialog reachable
        if (status == null) return INACTIVE;
        if (!status.displaysKnown()) return INACTIVE;    // clickable: explain no 2nd screen
        if (!status.anythingMovable()) return INACTIVE;  // clickable: explain nothing to move
        return ACTIVE;
    }

    /** The transient state while a move is in flight. */
    public static int busy() { return UNAVAILABLE; }

    /**
     * The state to show when the shade opens, before any su request.
     *
     * Never UNAVAILABLE: an unavailable tile receives no onClick(), which is
     * now the only place root is requested, so that would make root
     * permanently unobtainable.
     */
    public static int idleState() { return INACTIVE; }

    /**
     * The subtitle to show when the shade opens, before any su request.
     *
     * It names the verb rather than a direction. Which way the tap will move
     * an app cannot be known without reading the display state under root, and
     * asking for root here is the request Magisk rejects rather than prompts.
     */
    public static String idleLabel() { return "Switch screens"; }

    /**
     * The tile subtitle. It names the action the tap will take, rather than
     * describing state, because the tile's whole job is one verb.
     */
    public static String label(boolean available, ScreenStatus status) {
        if (!available)                       return "Root required";
        if (status == null)                   return "Unavailable";
        if (!status.displaysKnown())          return "No second screen";

        int src = status.sourceDisplayForToggle();
        if (src == ScreenStatus.NONE)         return "Nothing to move";
        if (src == status.topDisplay)         return "Send to bottom";
        return "Send to top";
    }
}
