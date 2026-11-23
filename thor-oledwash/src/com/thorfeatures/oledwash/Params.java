package com.thorfeatures.oledwash;

/**
 * The choices the draw-only build offers, and the defaults behind them.
 *
 * <p>These are pickers rather than a fixed configuration because the first
 * build exists to be judged by eye. Scroll speed in particular cannot be chosen
 * from first principles: the arithmetic says it does not change the dose at all
 * (see {@link WashPlan}), so it is purely a question of what reads as
 * deliberate rather than alarming, and that is a question for a person looking
 * at the device.
 */
public final class Params {

    private Params() {}

    /** Milliseconds for one full sweep, slowest-feeling last. */
    public static final long[] CYCLE_MS = { 1000L, 1500L, 2500L, 4000L, 6000L };

    /** Band height as a fraction of the panel. */
    public static final double[] BAND = { 0.20, 0.33, 0.50 };

    /** How many sweeps before the wash ends on its own. */
    public static final int[] CYCLES = { 1, 2, 3, 4, 6 };

    /**
     * Screen pixels per noise pixel. 1 is true per-pixel noise; 2 means each
     * noise pixel covers a 2x2 block, which is four times cheaper to generate
     * and upload. Whether 1 holds 120 Hz on this hardware is exactly what the
     * on-screen frame-rate readout is there to answer.
     */
    public static final int[] GRAIN = { 1, 2, 4 };

    /**
     * Lowest channel level in the band. See {@link Noise} for why it is not 0.
     * Fixed rather than offered: it is a brightness decision, and the finding
     * is explicit that this feature does not touch brightness without evidence.
     */
    public static final int NOISE_FLOOR = 160;

    public static final long DEFAULT_CYCLE_MS = 2500L;
    /** 0.33, from the photograph measurement -- 32 % on the less distorted panel. */
    public static final double DEFAULT_BAND = 0.33;
    public static final int DEFAULT_CYCLES = 4;
    public static final int DEFAULT_GRAIN = 2;

    /** Which panels to wash. */
    public static final int TARGET_ALL = 0;
    public static final int TARGET_PRIMARY = 1;
    public static final int TARGET_SECONDARY = 2;
    public static final int DEFAULT_TARGET = TARGET_ALL;

    /** Index of v in a, or -1. Used to light the selected button. */
    public static int indexOf(long[] a, long v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    public static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    /** Doubles compared on a tolerance, because these came from a resource. */
    public static int indexOf(double[] a, double v) {
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - v) < 1e-6) return i;
        return -1;
    }

    /** Snap an arbitrary value onto the nearest offered one. */
    public static long nearest(long[] a, long v) {
        long best = a[0];
        for (long c : a) if (Math.abs(c - v) < Math.abs(best - v)) best = c;
        return best;
    }

    public static int nearest(int[] a, int v) {
        int best = a[0];
        for (int c : a) if (Math.abs(c - v) < Math.abs(best - v)) best = c;
        return best;
    }

    public static double nearest(double[] a, double v) {
        double best = a[0];
        for (double c : a) if (Math.abs(c - v) < Math.abs(best - v)) best = c;
        return best;
    }
}
