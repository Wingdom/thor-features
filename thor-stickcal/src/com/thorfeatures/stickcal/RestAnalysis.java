package com.thorfeatures.stickcal;

/**
 * What the two hands-off phases say about centres and the deadzone floor.
 *
 * Kept apart from {@link RestMeasure} because this is where the two phases
 * are combined, and combining them is a decision rather than a measurement:
 * the centre is placed at the <b>midpoint</b> of the hysteresis range so the
 * stick is equally far inside the deadzone whichever side it last came from.
 * Centring on the before-position would leave it 40 counts out after every
 * push; centring on the after-position would leave it 40 counts out at every
 * cold start.
 */
public final class RestAnalysis {

    /** Safety factor over the bare drift floor, for the recommended deadzone. */
    private static final double RECOMMENDED_MARGIN = 1.5;
    /** Never recommend less than this, whatever a very clean unit measures. */
    private static final int RECOMMENDED_MIN = 20;

    public final int[] center = new int[4];
    /** How far each axis's rest position moved between the two phases. */
    public final int[] hysteresis = new int[4];
    /** Peak-to-peak noise at rest, worse of the two phases. Zero on this unit. */
    public final int[] noise = new int[4];

    public RestAnalysis(RestMeasure before, RestMeasure after) {
        for (int a = 0; a < 4; a++) {
            int b = before.median(a), f = after.median(a);
            center[a] = (int) Math.round((b + f) / 2.0);
            hysteresis[a] = Math.abs(f - b);
            noise[a] = Math.max(before.span(a), after.span(a));
        }
    }

    /**
     * The smallest deadzone that still holds this axis at zero when idle.
     *
     * Half the hysteresis, because the centre sits at its midpoint, plus half
     * the peak-to-peak noise, which is its amplitude about that. Below this a
     * stick that has just been released reports movement while sitting still,
     * which is the drift this calibration must not reintroduce.
     */
    public int requiredDeadzone(int axis) {
        return (int) Math.ceil(hysteresis[axis] / 2.0 + noise[axis] / 2.0);
    }

    /** The worst axis of a stick, which is what that stick must satisfy. */
    public int requiredDeadzone(Stick s) {
        return Math.max(requiredDeadzone(s.xAxis), requiredDeadzone(s.yAxis));
    }

    /**
     * What to actually use: the floor with margin, since a floor reached
     * exactly is a stick that drifts on a warm day.
     */
    public int recommendedDeadzone(Stick s) {
        return Math.max(RECOMMENDED_MIN,
                (int) Math.ceil(requiredDeadzone(s) * RECOMMENDED_MARGIN));
    }

    /** Across both sticks - what a single shipped default has to clear. */
    public int recommendedDeadzone() {
        return Math.max(recommendedDeadzone(Stick.LEFT), recommendedDeadzone(Stick.RIGHT));
    }
}
