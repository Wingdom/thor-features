package com.thorfeatures.screenpanel;

/**
 * Per-display refresh rate.
 *
 * This panel supports exactly two rates. `dumpsys display` lists, for both
 * screens, `fps=120.00001` and `fps=60.000004` and nothing else — so stock's
 * 60/90/120 control does not apply to the Thor, and this is a two-way switch.
 *
 * The lever is `cmd display set-user-preferred-display-mode`, which takes a
 * display id, so each screen is set independently. Verified on hardware
 * 2026-11-01 against display 1: the preference read back `1240 1080 60.0`,
 * then `1240 1080 120.0`, and `clear-user-preferred-display-mode` returned it
 * to unset.
 *
 * Width and height are passed in rather than hardcoded. They differ per
 * screen (1920x1080 and 1240x1080) and are read from the Display at runtime,
 * for the same reason every other id in this project is resolved rather than
 * assumed.
 */
public final class RefreshRate {

    public static final int LOW  = 60;
    public static final int HIGH = 120;

    /** The two rates, in the order the segmented control shows them. */
    public static final int[] RATES = { LOW, HIGH };

    /**
     * What a display shows when no preference has ever been set.
     *
     * Both panels report their 120 entry as `defaultModeId`, so an unset
     * preference means 120 here. The panel writes an explicit preference for
     * either choice rather than clearing, so this only describes a screen
     * nobody has touched yet.
     */
    public static final int DEFAULT = HIGH;

    private RefreshRate() {}

    /** Nearest supported rate. Anything unrecognised becomes the default. */
    public static int nearest(double fps) {
        if (Double.isNaN(fps) || fps <= 0) return DEFAULT;
        return Math.abs(fps - LOW) < Math.abs(fps - HIGH) ? LOW : HIGH;
    }

    /** The other rate — what a tap on the inactive half selects. */
    public static int other(int rate) { return rate == LOW ? HIGH : LOW; }

    /** The command that reads a display's preferred mode. */
    public static String getCommand(int displayId) {
        return "cmd display get-user-preferred-display-mode " + displayId;
    }

    /**
     * The command that sets a display's rate.
     *
     * STORE_MODE is true so the choice survives a reboot; the panel is a
     * settings surface, not a momentary override.
     */
    public static String setCommand(int displayId, int width, int height, int rate) {
        return "cmd display set-user-preferred-display-mode "
                + width + " " + height + " " + rate + " " + displayId + " true";
    }

    /**
     * Parse `get-user-preferred-display-mode`.
     *
     * Three replies seen on hardware, all meaning different things:
     *
     *   User preferred display mode: 1240 1080 60.0    an explicit choice
     *   User preferred display mode: -1 -1 0.0         never set
     *   User preferred display mode: null              cleared
     *
     * The last two both mean "no preference", and both report {@link #DEFAULT}.
     * Returning 0 for them instead would make the control show neither half
     * selected, which reads as broken rather than as untouched.
     */
    public static int parse(String out) {
        if (out == null) return DEFAULT;
        String t = out.trim();
        if (t.isEmpty() || t.endsWith("null")) return DEFAULT;

        // The rate is the last whitespace-separated token.
        int sp = t.lastIndexOf(' ');
        if (sp < 0) return DEFAULT;
        String last = t.substring(sp + 1);
        try {
            double fps = Double.parseDouble(last);
            if (fps <= 0) return DEFAULT;      // the -1 -1 0.0 form
            return nearest(fps);
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }

    /** Label for the segmented control. */
    public static String label(int rate) { return rate + " Hz"; }
}
