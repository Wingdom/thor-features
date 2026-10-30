package com.thorfeatures.fanctl;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The last fan mode this tile observed under root.
 *
 * It exists so onStartListening() can put a label on the tile without calling
 * su. Asking for root as the shade opens is the defect this cache fixes:
 * Magisk's prompt cannot take focus over the open Quick Settings panel, so the
 * request is rejected rather than prompted and the user never sees a dialog --
 * the tile just reports "no root" having asked them nothing. The first su
 * request has to come from a tap, which is a foreground action the prompt can
 * appear over. Confirmed on device 2026-10-29: four rejections logged, no
 * prompt shown.
 *
 * The cost is a label that can be stale. thor-screenpanel drives the same
 * /system/bin/fanctl, so changing the mode from the AYN panel leaves this copy
 * behind until the tile is next tapped -- and a tap re-reads under root and
 * corrects it. One wrong label for one shade open is a far smaller defect than
 * a tile that can never obtain root at all.
 *
 * There is no logic here to unit-test: every decision lives in TileRender,
 * which is host-tested. This class only stores and returns a string.
 */
final class ModeStore {

    private static final String PREFS = "tile";
    private static final String KEY_MODE = "mode";

    private ModeStore() {}

    /** The cached mode, or null when none has ever been observed. */
    static FanMode load(Context ctx) {
        String wire = prefs(ctx).getString(KEY_MODE, null);
        return wire == null ? null : FanMode.fromString(wire);
    }

    /**
     * Store an observed mode, or null to forget one.
     *
     * Forgetting is what keeps the cache honest. Pass the result of
     * TileRender.cacheAfterProbe() so a refused su clears the label instead of
     * leaving it claiming a tier that is no longer applied.
     */
    static void save(Context ctx, FanMode mode) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (mode == null) e.remove(KEY_MODE);
        else e.putString(KEY_MODE, mode.wire());
        e.apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
