package com.thorfeatures.stickcal;

/**
 * A hands-off phase: where the sticks sit when nobody is touching them.
 *
 * Two of these are taken, one before any deflection and one after both
 * sticks have been rolled, because the two disagree. On this unit LX rests at
 * 111 before and 151 after - 40 counts of mechanical hysteresis on a ~1200
 * count travel. That difference, not electrical noise, is what sets the floor
 * under a usable deadzone.
 *
 * <h3>Noise measured exactly zero</h3>
 *
 * The node returned {@code 111:-26:138:67} unchanged for 37 seconds. The MCU
 * already filters, so there is no noise to reject and AynParts' 200-count
 * default deadzone - about 16% of travel, costing magnitude on both axes at
 * once - was buying nothing. It is still measured here rather than assumed,
 * because a unit whose MCU filters differently would need a different floor
 * and would otherwise get this one.
 */
public final class RestMeasure {

    /** A reading must hold this still, for this long, before the phase ends. */
    public static final long SETTLE_MS = 4_000;
    public static final long MIN_PHASE_MS = 5_000;
    /**
     * Counts of movement tolerated inside the settle window.
     *
     * Generously above the zero noise measured here: a hand resting on the
     * device transmits a little, and failing to advance is a worse outcome
     * than a slightly wider centre estimate.
     */
    public static final int SETTLE_TOLERANCE = 25;

    private long[] t = new long[512];
    private int[][] v = new int[4][512];
    private int n;

    public void add(long tMs, RawSample s) {
        if (n == t.length) {
            long[] t2 = new long[n * 2];
            System.arraycopy(t, 0, t2, 0, n);
            t = t2;
            for (int a = 0; a < 4; a++) {
                int[] n2 = new int[n * 2];
                System.arraycopy(v[a], 0, n2, 0, n);
                v[a] = n2;
            }
        }
        t[n] = tMs;
        for (int a = 0; a < 4; a++) v[a][n] = s.axis(a);
        n++;
    }

    public int size() { return n; }

    public long elapsedMs() { return n == 0 ? 0 : t[n - 1] - t[0]; }

    /**
     * Start of the settle window: the stretch this phase actually measures.
     *
     * A hands-off phase begins the moment the previous one ends, so its first
     * samples are the stick still travelling home from a roll - a movement of
     * well over a thousand counts. Everything this class reports is therefore
     * measured over the trailing window only, which is the part where the
     * user's hands are genuinely off.
     *
     * Found on the device 2026-11-11: measuring over the whole phase reported
     * "noise 1358" on a unit whose noise is zero, and told the user their
     * deadzone was below the 1019 counts the unit needed. The calibration was
     * unaffected - the solver takes the deadzone the user chose, and the
     * centre is a median, which shrugs off a few approach samples - but the
     * figure shown was nonsense and the warning was false.
     */
    private long windowStart() {
        return n == 0 ? Long.MIN_VALUE : t[n - 1] - SETTLE_MS;
    }

    /**
     * The axis's resting value, over the settle window.
     *
     * Median, not mean: a single sample taken while the user was still
     * letting go would pull a mean, and the centre it feeds is the one number
     * a drifting stick is most sensitive to.
     */
    public int median(int axis) {
        if (n == 0) return 0;
        long since = windowStart();
        int count = 0;
        for (int i = 0; i < n; i++) if (t[i] >= since) count++;
        if (count == 0) return 0;
        int[] c = new int[count];
        int k = 0;
        for (int i = 0; i < n; i++) if (t[i] >= since) c[k++] = v[axis][i];
        java.util.Arrays.sort(c);
        return c[count / 2];
    }

    /**
     * Peak-to-peak movement while the stick was at rest. Zero on this unit.
     *
     * Over the settle window, not the whole phase - see {@link #windowStart}.
     */
    public int span(int axis) { return spanSince(axis, windowStart()); }

    private int spanSince(int axis, long sinceMs) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (t[i] < sinceMs) continue;
            lo = Math.min(lo, v[axis][i]);
            hi = Math.max(hi, v[axis][i]);
        }
        return hi == Integer.MIN_VALUE ? 0 : hi - lo;
    }

    /** True when every axis has held still for {@link #SETTLE_MS}. */
    public boolean settled() {
        if (n == 0 || elapsedMs() < MIN_PHASE_MS) return false;
        long since = t[n - 1] - SETTLE_MS;
        if (t[0] > since) return false;          // window not full yet
        for (int a = 0; a < 4; a++) {
            if (spanSince(a, since) > SETTLE_TOLERANCE) return false;
        }
        return true;
    }

    /** Largest movement on any axis inside the current settle window. For the UI. */
    public int worstSpanInWindow() {
        if (n == 0) return 0;
        long since = t[n - 1] - SETTLE_MS;
        int worst = 0;
        for (int a = 0; a < 4; a++) worst = Math.max(worst, spanSince(a, since));
        return worst;
    }
}
