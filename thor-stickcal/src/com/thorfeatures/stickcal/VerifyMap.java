package com.thorfeatures.stickcal;

/**
 * The verify phase: what the framework reports, not what sysfs holds.
 *
 * Fed from MotionEvent axis values, which are the per-axis output of
 * {@link Transfer} normalised to -1..1 - the same numbers a game reads. A
 * calibration that is stored but not in effect fails here and passes every
 * readback, which is why this phase exists.
 *
 * <h3>Reading the numbers</h3>
 *
 * Magnitude is {@code hypot} of two independently clamped axes, so:
 *
 * <ul>
 *   <li>a <b>cardinal</b> reads about 1.00-1.03, because one axis is at the
 *       clamp and the other is near zero. That is the clamp, not headroom,
 *       and cardinals were never the failing case;</li>
 *   <li>a <b>diagonal</b> can read up to 1.41, and is the number that
 *       matters. Before this calibration the worst diagonal read 0.94 on the
 *       left stick and 0.89 on the right, which is what made a character
 *       sneak instead of run.</li>
 * </ul>
 *
 * The pass condition is therefore the same in every direction - every bucket
 * at or above 1.00 - even though a pass means something slightly different at
 * a cardinal than at a diagonal.
 */
public final class VerifyMap {

    /** Full scale. A bucket at or above this saturates. */
    public static final double PASS = 1.0;
    private static final double EPS = 1e-6;
    /** Samples needed before a bucket's reading is taken seriously. */
    private static final int MIN_SAMPLES = 3;
    /** Below this the stick is on its way out, not at the gate. */
    private static final double GATE_FLOOR = 0.5;

    private final double[] max = new double[GateMap.BUCKETS];
    private final int[] count = new int[GateMap.BUCKETS];

    /** One framework reading, both axes in -1..1. */
    public void add(double x, double y) {
        double m = Math.hypot(x, y);
        if (m < GATE_FLOOR) return;
        int b = GateMap.bucketOf(x, y);
        count[b]++;
        if (m > max[b]) max[b] = m;
    }

    public double[] magnitudes() { return max.clone(); }

    public boolean covered(int b) { return count[b] >= MIN_SAMPLES; }

    public int bucketsCovered() {
        int c = 0;
        for (int b = 0; b < GateMap.BUCKETS; b++) if (covered(b)) c++;
        return c;
    }

    /** Covered buckets that do not reach full scale. The failure list. */
    public int bucketsUnder() {
        int c = 0;
        for (int b = 0; b < GateMap.BUCKETS; b++) {
            if (covered(b) && max[b] < PASS - EPS) c++;
        }
        return c;
    }

    public double worst() {
        double w = Double.MAX_VALUE;
        for (int b = 0; b < GateMap.BUCKETS; b++) if (covered(b)) w = Math.min(w, max[b]);
        return w == Double.MAX_VALUE ? 0 : w;
    }

    public double best() {
        double b = 0;
        for (int i = 0; i < GateMap.BUCKETS; i++) if (covered(i)) b = Math.max(b, max[i]);
        return b;
    }

    /**
     * A pass needs the whole circle swept, not just the part that passed.
     *
     * Reporting success on a partial sweep is the failure mode that matters
     * here: a user who rolled three quarters of the gate would be told the
     * diagonals are fixed without the quarter that might not be.
     */
    public boolean passed() {
        return bucketsCovered() == GateMap.BUCKETS && bucketsUnder() == 0;
    }
}
