package com.thorfeatures.oledwash;

import java.util.Locale;

/**
 * The derived numbers, in words.
 *
 * <p>Shown under the pickers so the consequences of a choice are visible before
 * it runs. The one that matters is the lit time per row: it is what separates
 * this from a cure. Four sweeps at two and a half seconds gives each row about
 * two and a half seconds of drive in total, which breaks up static content and
 * gives each pixel a different duty history. Clearing retention you can already
 * see is a minutes-long job and this is not it.
 */
public final class Summary {

    private Summary() {}

    /** One line of derived figures for a plan. */
    public static String describe(WashPlan plan, int grain) {
        return String.format(Locale.US,
                "band %d px of %d  ·  %.0f px/s  ·  %.1f s total  ·  "
                        + "each row lit %.1f s  ·  grain %d",
                plan.bandHeight,
                plan.panelHeight,
                plan.pixelsPerSecond(),
                plan.totalMs() / 1000.0,
                plan.litMsPerRow() / 1000.0,
                grain);
    }

    /** Short progress label drawn over the wash itself. */
    public static String progress(WashPlan plan, long elapsedMs) {
        return String.format(Locale.US, "sweep %d of %d",
                plan.cycleAt(elapsedMs) + 1, plan.cycles);
    }

    /**
     * Frames per second from a mean frame interval in nanoseconds.
     * Returns 0 when there is nothing to report yet rather than infinity.
     */
    public static double fps(long meanFrameNanos) {
        if (meanFrameNanos <= 0L) return 0.0;
        return 1_000_000_000.0 / (double) meanFrameNanos;
    }
}
