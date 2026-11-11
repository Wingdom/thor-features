package com.thorfeatures.stickcal;

/**
 * Solves one stick's per-axis limits so that every direction saturates.
 *
 * Derived and validated on hardware 2026-11-10; the evidence, the measured
 * gate maps and the before/after figures are in
 * {@code thor/findings/controller-input.md}. Nothing here is re-derived.
 *
 * <h3>The idea</h3>
 *
 * {@link Transfer} has no radial term, so a gate that is not round cannot
 * reach full scale on every cardinal <i>and</i> every diagonal. Something has
 * to give, and the choice is which: AynParts' wizard gives up the diagonals,
 * by setting each axis's limit to that axis's measured extreme. This gives up
 * a little travel instead, by shrinking all four limits uniformly until the
 * <i>worst</i> direction saturates with margin to spare. Everything else then
 * saturates early, which costs nothing - the output is clamped anyway.
 *
 * <h3>Why it is closed form</h3>
 *
 * For a sampled gate point with per-direction travel {@code P_d}:
 *
 * <pre>
 *   g = hypot( (|vx| - dz)/(Px - dz), (|vy| - dz)/(Py - dz) )
 * </pre>
 *
 * {@code g} is computed from the measured travel, so it does not depend on
 * the limits being solved for. Under a uniform shrink {@code k} - meaning
 * {@code lim_d = dz + k*(P_d - dz)} - the magnitude at that point is exactly
 * {@code g/k}. So requiring the worst point to reach a target margin
 * {@code m} gives
 *
 * <pre>
 *   k     = min(g) / m
 *   lim_d = dz + k * (P_d - dz)
 * </pre>
 *
 * with no search and no iteration. {@code k} is also directly meaningful: it
 * is the fraction of physical travel at which the stick now saturates.
 *
 * <h3>One deliberate departure from the hand process</h3>
 *
 * The prototype took {@code min(g)} over every sample whose radius exceeded
 * 45% of the maximum. That is sound only while the capture is almost entirely
 * gate: {@code min} over a point set is decided by a single member, so one
 * sample caught halfway out on the way in would have {@code g} near 0.5 and
 * would halve every limit. It did not happen in the capture that produced the
 * shipped values, but it is one unlucky sample away.
 *
 * Here {@code min(g)} is taken over the 24 per-bucket gate extremes instead -
 * the furthest point reached in each 15-degree sector. That is what "the
 * worst direction on the gate" means, it cannot be dragged down by travel,
 * and on a clean capture the two agree because the worst gate point is a
 * bucket extreme by construction.
 */
public final class GateSolver {

    /**
     * Shipped deadzone, in raw counts.
     *
     * 60 against a measured drift floor of 20 on the worst axis. Three times
     * the floor sounds generous until you compare it with the 200 AynParts
     * defaults to - 60 counts is under 5% of this gate's travel.
     */
    public static final int DEFAULT_DEADZONE = 60;

    /**
     * Shipped target margin: the worst direction must reach 112% of full
     * scale.
     *
     * Not 100%. A margin is what makes the result robust to the gate being
     * measured slightly short in one sector, to the stick wearing, and to the
     * capture and real use being different presses - the validation pass found
     * up to 4% disagreement between two sweeps of the same stick. 1.12 cost
     * ~15-18% of travel here and put every one of the 48 measured buckets over
     * 100%.
     */
    public static final double DEFAULT_TARGET = 1.12;

    /** Bounds offered in the UI. Below 1.0 would not saturate at all. */
    public static final double MIN_TARGET = 1.00, MAX_TARGET = 1.40;
    public static final int MIN_DEADZONE = 0, MAX_DEADZONE = 300;

    /** One stick's solved limits, plus everything needed to judge them. */
    public static final class Solution {
        public final Stick stick;
        public final Calibration.Axis x, y;
        /** {@code k}: fraction of physical travel at which output saturates. */
        public final double travelUsed;
        /** Unclamped magnitude per 15-degree bucket, 1.0 = full scale. */
        public final double[] headroom;
        public final double worst, best;
        /** Gate roundness, min bucket radius over max. */
        public final double roundness;
        public final int deadzone;
        public final double target;
        /** True when the gate was too incomplete or too small to solve from. */
        public final boolean degenerate;

        Solution(Stick stick, Calibration.Axis x, Calibration.Axis y, double travelUsed,
                 double[] headroom, double roundness, int deadzone, double target,
                 boolean degenerate) {
            this.stick = stick; this.x = x; this.y = y;
            this.travelUsed = travelUsed; this.headroom = headroom;
            this.roundness = roundness; this.deadzone = deadzone; this.target = target;
            this.degenerate = degenerate;
            double w = Double.MAX_VALUE, b = 0;
            for (double h : headroom) {
                if (h <= 0) continue;            // empty bucket, not a failure
                w = Math.min(w, h); b = Math.max(b, h);
            }
            this.worst = w == Double.MAX_VALUE ? 0 : w;
            this.best = b;
        }

        /** Buckets that fail to reach full scale. Zero is the pass condition. */
        public int bucketsUnderFullScale() {
            int c = 0;
            for (double h : headroom) if (h > 0 && h < 1.0) c++;
            return c;
        }

        /**
         * Where the output saturates along one angle, in raw counts from
         * centre. Drawn as the "required radius" contour against the measured
         * gate.
         *
         * Solving {@code hypot((r*a - dz)/Lx, (r*b - dz)/Ly) = 1} for r, with
         * {@code a = |cos|}, {@code b = |sin|} and {@code L} the limit minus
         * the deadzone - an ordinary quadratic. Returns 0 when the angle is
         * degenerate rather than a negative radius.
         */
        public double saturationRadius(double angleRad) {
            double ca = Math.cos(angleRad), sa = Math.sin(angleRad);
            double a = Math.abs(ca), b = Math.abs(sa);
            double lx = (ca >= 0 ? x.limit(true) : x.limit(false)) - deadzone;
            double ly = (sa >= 0 ? y.limit(true) : y.limit(false)) - deadzone;
            if (lx <= 0 || ly <= 0) return 0;

            // Near a cardinal, one axis never leaves its deadzone, and that
            // axis contributes nothing rather than contributing a negative
            // numerator. Taking the quadratic unconditionally gets this
            // wrong precisely at the cardinals - which is where this gate is
            // narrowest and where the contour is most worth drawing.
            if (a > 0) {
                double r = (lx + deadzone) / a;
                if (r * b <= deadzone) return r;
            }
            if (b > 0) {
                double r = (ly + deadzone) / b;
                if (r * a <= deadzone) return r;
            }

            // Both axes outside the deadzone: an ordinary quadratic in r,
            // from ((r a - dz)/lx)^2 + ((r b - dz)/ly)^2 = 1.
            double A = a * a / (lx * lx) + b * b / (ly * ly);
            double B = -2 * deadzone * (a / (lx * lx) + b / (ly * ly));
            double C = deadzone * deadzone * (1 / (lx * lx) + 1 / (ly * ly)) - 1;
            if (A <= 0) return 0;
            double disc = B * B - 4 * A * C;
            if (disc < 0) return 0;
            double r = (-B + Math.sqrt(disc)) / (2 * A);
            return r > 0 ? r : 0;
        }
    }

    private GateSolver() {}

    /**
     * Solve one stick.
     *
     * @param gate    its measured gate, in counts relative to the centre
     * @param centerX centre on the x axis, in absolute counts
     * @param centerY centre on the y axis
     * @param deadzone raw counts; must clear {@link RestAnalysis#requiredDeadzone}
     * @param target  worst-case magnitude to aim for, 1.0 = full scale
     */
    public static Solution solve(Stick stick, GateMap gate, int centerX, int centerY,
                                 int deadzone, double target) {
        double[][] pts = gate.gatePoints();
        double px = gate.travel(true, true), nx = gate.travel(true, false);
        double py = gate.travel(false, true), ny = gate.travel(false, false);

        boolean degenerate = px <= deadzone || nx <= deadzone
                || py <= deadzone || ny <= deadzone;

        double minG = Double.MAX_VALUE;
        if (!degenerate) {
            for (double[] p : pts) {
                if (p == null) continue;
                double g = Transfer.headroom(p[0], p[1], px, nx, py, ny, deadzone);
                if (g > 0) minG = Math.min(minG, g);
            }
        }
        if (minG == Double.MAX_VALUE) degenerate = true;

        if (degenerate) {
            // Return something inert rather than a wrong calibration. The
            // caller shows the gate as unsolvable; nothing gets written.
            Calibration.Axis ax = new Calibration.Axis(centerX, centerX, centerX, deadzone);
            Calibration.Axis ay = new Calibration.Axis(centerY, centerY, centerY, deadzone);
            return new Solution(stick, ax, ay, 0, new double[GateMap.BUCKETS],
                    gate.roundness(), deadzone, target, true);
        }

        double k = minG / target;
        double lxp = deadzone + k * (px - deadzone);
        double lxn = deadzone + k * (nx - deadzone);
        double lyp = deadzone + k * (py - deadzone);
        double lyn = deadzone + k * (ny - deadzone);

        Calibration.Axis ax = new Calibration.Axis(
                (int) Math.round(centerX - lxn), (int) Math.round(centerX + lxp),
                centerX, deadzone);
        Calibration.Axis ay = new Calibration.Axis(
                (int) Math.round(centerY - lyn), (int) Math.round(centerY + lyp),
                centerY, deadzone);

        // Headroom is recomputed from the rounded limits that will actually be
        // written, not from the unrounded ones the solve produced. A rounding
        // of half a count cannot matter, but computing it the other way would
        // mean the table shown to the user described a calibration subtly
        // different from the one on the device.
        double[] headroom = new double[GateMap.BUCKETS];
        for (int b = 0; b < GateMap.BUCKETS; b++) {
            double[] p = pts[b];
            if (p == null) continue;
            headroom[b] = Transfer.headroom(p[0], p[1],
                    ax.limit(true), ax.limit(false),
                    ay.limit(true), ay.limit(false), deadzone);
        }

        return new Solution(stick, ax, ay, k, headroom, gate.roundness(),
                deadzone, target, false);
    }

    /**
     * Fold two solutions into a full calibration, keeping the triggers from
     * {@code base}.
     *
     * {@code base} is whatever the node currently holds. Both L2 and R2 pass
     * through untouched - this tool has no measurement of them and so has no
     * business writing them.
     */
    public static Calibration apply(Calibration base, Solution left, Solution right) {
        return base
                .withAxis(Stick.LEFT.xAxis, left.x)
                .withAxis(Stick.LEFT.yAxis, left.y)
                .withAxis(Stick.RIGHT.xAxis, right.x)
                .withAxis(Stick.RIGHT.yAxis, right.y);
    }
}
