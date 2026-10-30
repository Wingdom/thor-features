package com.thorfeatures.fanctl;

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
 *
 * The second rule here is about *when* root is asked for, and was found the
 * same way. The tile used to call fanctl from onStartListening() to render its
 * label, which means it asked for root every time the Quick Settings shade
 * opened. Magisk's prompt cannot take focus over the open shade, so those
 * requests were rejected rather than prompted -- the tile reported "no root"
 * having never asked the user anything. Confirmed on device 2026-10-29: four
 * rejections in logcat, no prompt shown, and no policy row for the app.
 *
 * So shade-open rendering goes through cachedState() and names no su call at
 * all. The first su request happens on tap, which is a foreground action the
 * prompt can appear over.
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

    /**
     * The state to show when the shade opens, before any su request.
     *
     * @param cached the last mode observed under root, or null when this tile
     *               has never observed one -- a fresh install, or a cache
     *               cleared by a refused probe
     */
    public static int cachedState(FanMode cached) {
        // Never UNAVAILABLE. A fresh install has no cache, and an unavailable
        // tile receives no onClick() -- which is the only place root can now
        // be requested, so that would make root permanently unobtainable.
        if (cached == null) return INACTIVE;
        return cached.isActive() ? ACTIVE : INACTIVE;
    }

    /**
     * True when the tile has no cached mode and must invite a tap rather than
     * name a mode it does not know.
     */
    public static boolean needsSetup(FanMode cached) { return cached == null; }

    /**
     * What to cache after a tap-driven probe.
     *
     * Forgetting on failure is the point. A cache that kept its last value
     * would go on claiming a tier was applied after the Magisk module was
     * removed or Superuser access revoked, and the tile would look switched on
     * while fanctl was doing nothing.
     *
     * @param available the probe got a clean exit from fanctl
     * @param reported  what fanctl said, meaningful only when available
     * @return the mode to store, or null to forget
     */
    public static FanMode cacheAfterProbe(boolean available, FanMode reported) {
        return available ? reported : null;
    }

    /** True only for the transient "applying" state. */
    public static int busy() { return UNAVAILABLE; }
}
