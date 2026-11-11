package com.thorfeatures.stickcal;

/**
 * The moorechip driver's transfer function, from raw counts to evdev output.
 *
 * Inferred from the node's behaviour and then validated against an
 * independent evdev sweep on 2026-11-10: predicted against measured magnitude
 * extremes agreed within 1% at the maxima and 4% at the minima, the residual
 * being that the two sweeps were separate presses.
 *
 * <pre>
 *   v   = raw - center
 *   lim = (max - center)  for v >= 0,   (center - min)  for v < 0
 *   out = clamp( (|v| - deadzone) / (lim - deadzone), 0, 1 ) * 32767
 * </pre>
 *
 * Three properties of this follow from the formula and matter to everything
 * else in this package:
 *
 * <ol>
 *   <li><b>It is per axis.</b> There is no radial term anywhere, which is why
 *       AynParts' wizard cannot make a non-round gate saturate diagonally -
 *       and why the limits have to be solved for instead.</li>
 *   <li><b>The deadzone is subtracted from both the numerator and the
 *       denominator</b>, so it shortens travel rather than just muting the
 *       centre. A large deadzone costs magnitude on both axes at once, which
 *       is most of why AynParts' 200-count default hurt the diagonals.</li>
 *   <li><b>Each axis clamps independently at 1.0.</b> So a cardinal always
 *       reads about 100% - one axis at the clamp, the other near zero - and a
 *       diagonal can legitimately read up to 141%. A cardinal reading 103% in
 *       a sweep is that clamp, not headroom.</li>
 * </ol>
 */
public final class Transfer {

    /** Full-scale evdev value, both signs. The framework normalises to +/-1. */
    public static final int FULL_SCALE = 32767;

    private Transfer() {}

    /**
     * One axis, as a fraction of full scale in -1..1.
     *
     * @param raw the value the node reports
     * @param a   that axis's calibration
     */
    public static double axis(int raw, Calibration.Axis a) {
        int v = raw - a.center;
        double lim = a.limit(v >= 0) - a.deadzone;
        if (lim <= 0) return 0;              // degenerate calibration: no travel
        double out = (Math.abs(v) - a.deadzone) / lim;
        if (out <= 0) return 0;
        if (out > 1) out = 1;
        return v >= 0 ? out : -out;
    }

    /** One axis in evdev units, as {@code getevent -l} would show it. */
    public static int evdev(int raw, Calibration.Axis a) {
        return (int) Math.round(axis(raw, a) * FULL_SCALE);
    }

    /**
     * The magnitude a game sees, as a fraction of full scale.
     *
     * This is the number the whole tool exists to push above 1.0 in every
     * direction: Lime3DS, and any other consumer that thresholds on stick
     * magnitude, compares exactly this against its run threshold.
     */
    public static double magnitude(int rawX, int rawY,
                                   Calibration.Axis ax, Calibration.Axis ay) {
        return Math.hypot(axis(rawX, ax), axis(rawY, ay));
    }

    /**
     * Magnitude with the per-axis clamp removed.
     *
     * Needed to judge a calibration rather than to predict output. Clamped
     * magnitude saturates at 1.0 on a cardinal and hides whether a direction
     * has 1% or 30% to spare, so the solver's headroom figures are all
     * unclamped. Clamped is what the device does; unclamped is what tells you
     * by how much it is doing it.
     */
    public static double headroom(double vx, double vy,
                                  double limXplus, double limXminus,
                                  double limYplus, double limYminus,
                                  double deadzone) {
        double lx = (vx >= 0 ? limXplus : limXminus) - deadzone;
        double ly = (vy >= 0 ? limYplus : limYminus) - deadzone;
        if (lx <= 0 || ly <= 0) return 0;
        double ux = Math.max(0, Math.abs(vx) - deadzone) / lx;
        double uy = Math.max(0, Math.abs(vy) - deadzone) / ly;
        return Math.hypot(ux, uy);
    }
}
