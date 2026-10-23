package com.thorfeatures.stickfx;

/**
 * Which Quick Settings state the tile should show.
 *
 * Pure logic so it can be unit-tested on the host -- and it needs testing,
 * because the obvious choice is wrong.
 *
 * When root is unavailable the tile must stay **clickable**. SystemUI does not
 * deliver onClick() to a tile in STATE_UNAVAILABLE, so rendering that state
 * leaves the user with a dimmed tile that does nothing and never explains why:
 * the "root required" dialog becomes unreachable. Verified on device
 * 2026-10-22 -- tapping an unavailable tile produced no su request at all.
 *
 * STATE_UNAVAILABLE is still right for the brief moment while a change is
 * being applied, which is transient and deliberately not clickable.
 */
public final class TileRender {

    public static final int ACTIVE = 1;
    public static final int INACTIVE = 2;
    public static final int UNAVAILABLE = 3;

    private TileRender() {}

    /**
     * @param available  the CLI responded, i.e. module installed and root granted
     * @param modeActive a mode/tier is applied
     */
    public static int state(boolean available, boolean modeActive) {
        if (!available) return INACTIVE;      // clickable, so the dialog is reachable
        return modeActive ? ACTIVE : INACTIVE;
    }

    /** True only for the transient "applying" state. */
    public static int busy() { return UNAVAILABLE; }
}
