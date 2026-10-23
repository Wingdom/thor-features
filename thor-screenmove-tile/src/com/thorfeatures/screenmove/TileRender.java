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
