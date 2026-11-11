package com.thorfeatures.stickcal;

/**
 * One stick's gate, measured by rolling it hard around its travel.
 *
 * Samples arrive centre-relative and are kept whole: a 60-second phase at
 * 20 Hz is 1200 points, so every figure here is recomputed from the samples
 * rather than accumulated incrementally. That costs nothing and means the
 * live display and the final solve are computed by the same code, which is
 * the only way the thing the user watched is the thing that got solved.
 *
 * <h3>Why 24 buckets of 15 degrees</h3>
 *
 * It is the resolution the gate was originally measured at, so the numbers
 * here are comparable with the maps recorded in
 * {@code thor/findings/controller-input.md}. It is also about as fine as a
 * hand-rolled sweep supports: finer buckets start to report gaps that are the
 * user's wrist, not the gate.
 *
 * <h3>The gate is not round, and that is the whole problem</h3>
 *
 * This unit measures 86.0% round on the left stick and 83.7% on the right,
 * and on both the <i>minimum</i> radius is at a cardinal. So coverage cannot
 * be judged against the largest radius seen - the 0-degree bucket would never
 * qualify, because it physically cannot reach what 165 degrees reaches. It is
 * judged against each bucket's neighbours instead, which is what catches the
 * real failure: a sector the user did not push all the way into.
 */
public final class GateMap {

    public static final int BUCKETS = 24;
    public static final int BUCKET_DEGREES = 360 / BUCKETS;

    // --- auto-advance criteria -------------------------------------------
    // Tunable in one place, and each one is here because of a specific way a
    // sweep goes wrong.

    /** Shortest a roll phase can be, however good the coverage looks. */
    public static final long MIN_PHASE_MS = 6_000;
    /** How long every bucket's radius must stop growing before advancing. */
    public static final long STABLE_MS = 2_000;
    /** Growth below this is noise, not the user still pushing further out. */
    private static final double GROWTH_RATIO = 1.005;
    /** A bucket needs this many samples before its radius means anything. */
    private static final int MIN_SAMPLES_PER_BUCKET = 3;
    /**
     * A bucket must reach this fraction of the mean of its two neighbours.
     * Catches an under-deflected sector, which shows up as a notch.
     */
    private static final double NOTCH_RATIO = 0.92;
    /**
     * And this fraction of the largest radius anywhere, which catches two
     * adjacent thin buckets - they would satisfy each other's notch test.
     * Safe at 0.5 because the gate itself is never worse than ~84% round.
     */
    private static final double FLOOR_RATIO = 0.50;
    /** Samples below this fraction of the maximum radius are travel, not gate. */
    public static final double GATE_RATIO = 0.60;

    private long[] t = new long[1024];
    private double[] xs = new double[1024];
    private double[] ys = new double[1024];
    private int n;

    /** Per-bucket maximum radius, and when it last grew. Derived, cached. */
    private final double[] bucketMax = new double[BUCKETS];
    private final int[] bucketCount = new int[BUCKETS];
    private long lastGrowthMs;
    private long firstMs = -1, lastMs = -1;
    private double maxRadius;

    /** Add one centre-relative sample. */
    public void add(long tMs, double x, double y) {
        if (n == xs.length) {
            long[] t2 = new long[n * 2];
            double[] x2 = new double[n * 2], y2 = new double[n * 2];
            System.arraycopy(t, 0, t2, 0, n);
            System.arraycopy(xs, 0, x2, 0, n);
            System.arraycopy(ys, 0, y2, 0, n);
            t = t2; xs = x2; ys = y2;
        }
        t[n] = tMs; xs[n] = x; ys[n] = y; n++;

        if (firstMs < 0) firstMs = tMs;
        lastMs = tMs;

        double r = Math.hypot(x, y);
        int b = bucketOf(x, y);
        bucketCount[b]++;
        if (r > bucketMax[b] * GROWTH_RATIO) lastGrowthMs = tMs;
        if (r > bucketMax[b]) bucketMax[b] = r;
        if (r > maxRadius) maxRadius = r;
    }

    public int size() { return n; }
    public double x(int i) { return xs[i]; }
    public double y(int i) { return ys[i]; }
    public double maxRadius() { return maxRadius; }
    public double[] bucketRadii() { return bucketMax.clone(); }

    /**
     * The same samples, translated by a correction to the centre.
     *
     * The rolls happen before the second hands-off phase, so they have to be
     * accumulated against a provisional centre - the cold rest position - and
     * the final centre is only known once the hysteresis range is. Rather
     * than keep a second copy of the samples in absolute counts, the gate is
     * re-based here once the real centre is in hand. The shift is at most
     * half the hysteresis, about 20 counts on this unit, but a gate measured
     * about the wrong origin would bias every limit in one direction.
     */
    public GateMap shifted(double dx, double dy) {
        GateMap m = new GateMap();
        for (int i = 0; i < n; i++) m.add(t[i], xs[i] - dx, ys[i] - dy);
        return m;
    }

    /** The 15-degree bucket a point falls in, counting anticlockwise from +x. */
    public static int bucketOf(double x, double y) {
        double a = Math.toDegrees(Math.atan2(y, x));
        if (a < 0) a += 360;
        int b = (int) (a / BUCKET_DEGREES);
        return b >= BUCKETS ? 0 : b;      // guards a == 360.0 exactly
    }

    /** Mid-angle of a bucket, in radians. For drawing. */
    public static double bucketAngle(int b) {
        return Math.toRadians(b * BUCKET_DEGREES + BUCKET_DEGREES / 2.0);
    }

    /**
     * How round the gate is: smallest bucket radius over largest.
     *
     * Reported rather than used. It is the number that explains to the user
     * why they are about to lose 15% of their travel, and it is directly
     * comparable with the 86.0% / 83.7% recorded for this unit.
     */
    public double roundness() {
        if (maxRadius <= 0) return 0;
        double min = Double.MAX_VALUE;
        for (double r : bucketMax) min = Math.min(min, r);
        return min / maxRadius;
    }

    /** True when bucket b has been swept far enough to trust. */
    public boolean bucketOk(int b) {
        if (bucketCount[b] < MIN_SAMPLES_PER_BUCKET) return false;
        if (bucketMax[b] < maxRadius * FLOOR_RATIO) return false;
        double left = bucketMax[(b + BUCKETS - 1) % BUCKETS];
        double right = bucketMax[(b + 1) % BUCKETS];
        return bucketMax[b] >= NOTCH_RATIO * (left + right) / 2.0;
    }

    public int bucketsOk() {
        int c = 0;
        for (int b = 0; b < BUCKETS; b++) if (bucketOk(b)) c++;
        return c;
    }

    public long elapsedMs() { return firstMs < 0 ? 0 : lastMs - firstMs; }

    /** Milliseconds since any bucket last grew. */
    public long steadyMs() { return lastMs < 0 ? 0 : lastMs - lastGrowthMs; }

    /**
     * Whether this phase can advance on its own.
     *
     * All three conditions, not any: a sweep can cover every angle while
     * still getting further out each lap, and advancing then would solve from
     * a gate the user had not finished finding.
     */
    public boolean complete() {
        return elapsedMs() >= MIN_PHASE_MS
                && bucketsOk() == BUCKETS
                && steadyMs() >= STABLE_MS;
    }

    /**
     * Furthest travel in one direction, over gate samples only.
     *
     * {@code P_x+}, {@code P_x-}, {@code P_y+}, {@code P_y-} in the solver's
     * notation. Interior samples are excluded because these feed the
     * denominators of the normalised magnitude: including a point from the
     * travel in and out would not change a maximum, but filtering here keeps
     * every figure in this class computed over the same point set.
     */
    public double travel(boolean axisX, boolean positive) {
        double best = 0;
        double cut = maxRadius * GATE_RATIO;
        for (int i = 0; i < n; i++) {
            if (Math.hypot(xs[i], ys[i]) < cut) continue;
            double v = axisX ? xs[i] : ys[i];
            if (!positive) v = -v;
            if (v > best) best = v;
        }
        return best;
    }

    /**
     * The point of maximum radius in each bucket, as {x, y}, or null for an
     * empty bucket.
     *
     * This is the sampled gate, reduced to 24 points. The solver takes its
     * worst case over exactly these rather than over every sample above a
     * radius threshold, which is the one place this implementation departs
     * from the hand process - see {@link GateSolver}.
     */
    public double[][] gatePoints() {
        double[][] out = new double[BUCKETS][];
        double[] best = new double[BUCKETS];
        for (int i = 0; i < n; i++) {
            double r = Math.hypot(xs[i], ys[i]);
            int b = bucketOf(xs[i], ys[i]);
            if (r > best[b]) { best[b] = r; out[b] = new double[] { xs[i], ys[i] }; }
        }
        return out;
    }
}
