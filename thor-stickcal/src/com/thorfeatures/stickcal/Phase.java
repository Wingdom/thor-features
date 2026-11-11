package com.thorfeatures.stickcal;

/**
 * The wizard's order of business.
 *
 * Deliberately holds no user-visible text, so the whole flow - including
 * which phases sample, which auto-advance and what each one feeds - can be
 * asserted on the host. The strings live in resources and are chosen by the
 * activity.
 *
 * <h3>Why the hands-off phase happens twice</h3>
 *
 * Because a stick does not return to where it started. The before and after
 * rest positions differ by ~40 counts here, and the centre is placed at the
 * midpoint of the two. One hands-off phase could only measure one end of
 * that, and whichever end it measured would leave the stick sitting off
 * centre half the time.
 *
 * The order also matters: REST_BEFORE must come before any deflection in the
 * session, or it is not a cold rest position at all.
 */
public enum Phase {

    /** Explains what is about to happen and what it will cost in travel. */
    INTRO(false, false, null),

    /** Hands off, cold. Feeds the centre and the noise floor. */
    REST_BEFORE(true, true, null),

    /** Roll the left stick hard around its gate. */
    ROLL_LEFT(true, true, Stick.LEFT),

    /** Roll the right stick hard around its gate. */
    ROLL_RIGHT(true, true, Stick.RIGHT),

    /** Hands off again. The other end of the hysteresis range. */
    REST_AFTER(true, true, null),

    /** The solved limits, the headroom table, and the advanced controls. */
    REVIEW(false, false, null),

    /**
     * Roll both sticks again and watch what the framework reports.
     *
     * The one phase that reads nothing from sysfs. It takes MotionEvent axis
     * values, which is the same number a game sees - the only check that
     * proves the calibration is in effect rather than merely stored. A node
     * that reads back correctly can still not be applied: these thermal-style
     * sysfs surprises are why nothing here trusts a readback.
     */
    VERIFY(false, false, null),

    DONE(false, false, null);

    /** Whether this phase consumes samples from the raw stream. */
    public final boolean samples;
    /** Whether it moves on by itself once its criteria are met. */
    public final boolean autoAdvance;
    /** The stick being rolled, or null when both or neither are involved. */
    public final Stick stick;

    Phase(boolean samples, boolean autoAdvance, Stick stick) {
        this.samples = samples; this.autoAdvance = autoAdvance; this.stick = stick;
    }

    public Phase next() {
        Phase[] all = values();
        return this == DONE ? DONE : all[ordinal() + 1];
    }

    public Phase previous() {
        return this == INTRO ? INTRO : values()[ordinal() - 1];
    }

    /** Whether the raw stream needs to be running. */
    public boolean needsStream() { return samples; }

    /** Position in the capture run, for a progress indicator. 0 outside it. */
    public int captureStep() {
        switch (this) {
            case REST_BEFORE: return 1;
            case ROLL_LEFT:   return 2;
            case ROLL_RIGHT:  return 3;
            case REST_AFTER:  return 4;
            default:          return 0;
        }
    }

    public static final int CAPTURE_STEPS = 4;
}
