package com.thorfeatures.stickcal;

import java.util.Locale;

/**
 * The text the wizard shows. Pure, so the numbers can be asserted on the host.
 *
 * Every figure here is a percentage of full axis output, which is the unit the
 * whole diagnosis was conducted in: 100% is the threshold a game's "running"
 * check sits just under, and the measured fault was a worst direction of 94%
 * on the left stick and 89% on the right.
 */
public final class Report {

    private Report() {}

    /**
     * The per-angle headroom table, two rows of twelve.
     *
     * Same shape the prototype printed, deliberately: the numbers in the
     * finding can be compared against this screen column by column.
     */
    public static String headroomTable(GateSolver.Solution s) {
        StringBuilder sb = new StringBuilder();
        for (int half = 0; half < 2; half++) {
            sb.append(' ');
            for (int b = half * 12; b < half * 12 + 12; b++) {
                sb.append(String.format(Locale.US, "%5d", b * GateMap.BUCKET_DEGREES));
            }
            sb.append('\n').append(' ');
            for (int b = half * 12; b < half * 12 + 12; b++) {
                double h = s.headroom[b];
                sb.append(h <= 0 ? "    -" : String.format(Locale.US, "%5.0f", h * 100));
            }
            if (half == 0) sb.append('\n');
        }
        return sb.toString();
    }

    /** One line a user can act on: what it costs and what it buys. */
    public static String summary(GateSolver.Solution s) {
        if (s.degenerate) return "not enough of the gate was measured to solve";
        return String.format(Locale.US,
                "saturates at %.0f%% of travel · worst direction %.0f%% · best %.0f%% · gate %.0f%% round",
                s.travelUsed * 100, s.worst * 100, s.best * 100, s.roundness * 100);
    }

    /** The limits as they will be written, for the record. */
    public static String limits(GateSolver.Solution s) {
        return s.stick.label + "  x " + s.x + "   y " + s.y;
    }

    /**
     * What the hands-off phases found.
     *
     * Hysteresis is named explicitly because it is the surprising part: the
     * deadzone exists for the stick not returning to where it started, not
     * for noise, which measured exactly zero over 37 seconds on this unit.
     */
    public static String rest(RestAnalysis r) {
        return String.format(Locale.US,
                "centres %d, %d, %d, %d · hysteresis up to %d counts · noise %d · deadzone floor %d",
                r.center[0], r.center[1], r.center[2], r.center[3],
                max(r.hysteresis), max(r.noise), r.recommendedDeadzone());
    }

    /** Progress during a roll, naming what is still missing. */
    public static String coverage(GateMap g) {
        int ok = g.bucketsOk();
        if (ok == GateMap.BUCKETS) {
            long steady = g.steadyMs();
            if (steady < GateMap.STABLE_MS) {
                return "all 24 directions · still reaching further out";
            }
            return "all 24 directions · holding steady";
        }
        return (GateMap.BUCKETS - ok) + " of 24 directions still short — the marked sectors";
    }

    /** The verify phase's verdict, in the same percentages. */
    public static String verify(VerifyMap v) {
        int covered = v.bucketsCovered();
        if (covered < GateMap.BUCKETS) {
            return String.format(Locale.US,
                    "%d of 24 directions swept — keep rolling", covered);
        }
        if (v.bucketsUnder() > 0) {
            return String.format(Locale.US,
                    "%d directions below full scale · worst %.0f%%",
                    v.bucketsUnder(), v.worst() * 100);
        }
        return String.format(Locale.US,
                "all 24 directions saturate · worst %.0f%% · best %.0f%%",
                v.worst() * 100, v.best() * 100);
    }

    /**
     * What to say when the user leaves the check phase without a clean pass,
     * or null when both sticks passed.
     *
     * Two quite different things make {@code passed()} false, and saying the
     * wrong one is worse than saying nothing. A direction that was swept and
     * fell short is the original fault returning. A direction that was never
     * swept is just an unfinished check - and on the device it was the
     * common case, because the obvious thing to do on that screen is roll one
     * stick and move on. Telling someone their calibration is short when they
     * simply have not rolled the other stick sends them back to redo work
     * that was fine.
     */
    public static String verifyExitNote(VerifyMap left, VerifyMap right) {
        if (left.passed() && right.passed()) return null;

        int shortfalls = left.bucketsUnder() + right.bucketsUnder();
        int unswept = (GateMap.BUCKETS - left.bucketsCovered())
                + (GateMap.BUCKETS - right.bucketsCovered());

        if (shortfalls > 0 && unswept > 0) {
            return String.format(Locale.US,
                    "%d directions below full scale, and %d never swept"
                    + " — roll both sticks fully to see the rest",
                    shortfalls, unswept);
        }
        if (shortfalls > 0) {
            return String.format(Locale.US,
                    "%d directions below full scale — Back to the review to raise"
                    + " the margin, or roll again", shortfalls);
        }
        return String.format(Locale.US,
                "nothing fell short, but %d of 48 directions were never swept"
                + " — the check is incomplete, not failed", unswept);
    }

    private static int max(int[] v) {
        int m = 0;
        for (int x : v) m = Math.max(m, x);
        return m;
    }
}
