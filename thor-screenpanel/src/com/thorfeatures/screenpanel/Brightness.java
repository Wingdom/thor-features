package com.thorfeatures.screenpanel;

/**
 * Per-screen brightness: the conversions between a slider position and the
 * framework's brightness scale, and the commands that read and write it.
 *
 * Why this is a framework call and not a sysfs write:
 *
 *   Both backlight nodes are root-writable, but writing them does nothing
 *   useful on a stock ROM: LineageOS declares the bottom panel a brightness
 *   *follower* of the main one, so the framework drives panel1's backlight
 *   from display 0 and overwrites anything else. Measured 2026-10-28 -
 *   `cmd display set-brightness --id 1` stored a value the hardware never
 *   received, with panel1 pinned at 4095 the whole time.
 *
 *   thor-screenbright removes that follower relationship, after which
 *   `cmd display set-brightness --id N` drives each panel independently. The
 *   mapping is exactly linear against the node: 0.01 -> 41, 0.3 -> 1229,
 *   1.0 -> 4095 on a max_brightness of 4095.
 *
 * The scale is the framework's float [0,1], not the 0-4095 node value and not
 * the 0-255 settings value. Converting here once keeps the three scales this
 * device uses from being mixed up at a call site.
 */
public final class Brightness {

    /** Display id of the main panel. */
    public static final int TOP = 0;

    /**
     * The lowest the slider will go.
     *
     * Not 0. At 0.0 the panel reads `brightness = 1` and looks switched off
     * rather than dim - the user's words on seeing it were "looks like it had
     * its brightness set to 0". A slider whose bottom end is indistinguishable
     * from a broken screen is a bad control, and "off" already has its own
     * dedicated place in the Mode row.
     */
    public static final float MIN = 0.01f;
    public static final float MAX = 1.0f;

    /** Slider resolution. 100 steps, so one step is one percent. */
    public static final int STEPS = 100;

    private Brightness() {}

    /** Clamp to the usable range. NaN and out-of-range inputs are pulled in. */
    public static float clamp(float v) {
        if (Float.isNaN(v)) return MIN;
        if (v < MIN) return MIN;
        if (v > MAX) return MAX;
        return v;
    }

    /** Slider position (0..STEPS) for a brightness value. */
    public static int toSlider(float v) {
        float c = clamp(v);
        return Math.round((c - MIN) / (MAX - MIN) * STEPS);
    }

    /** Brightness value for a slider position. */
    public static float fromSlider(int pos) {
        if (pos < 0) pos = 0;
        if (pos > STEPS) pos = STEPS;
        return clamp(MIN + (MAX - MIN) * pos / (float) STEPS);
    }

    /** Percentage to show beside the slider. */
    public static int percent(float v) {
        return Math.round(clamp(v) * 100f);
    }

    /**
     * Slider position at or above which the track's fill has reached the
     * brightness icon sitting at the right-hand end.
     *
     * The icon is a fixed inset from the right edge, so this is a position,
     * not a brightness: above it the icon sits on the light accent fill and
     * must be drawn dark; below it the icon sits on the dark unfilled track
     * and must be drawn light. A single fixed colour disappears at one end or
     * the other - with a dark icon the top slider's icon was invisible at
     * anything under full brightness.
     */
    public static final int ICON_ON_FILL_FROM = 88;

    /** True when the icon is sitting on the filled part of the track. */
    public static boolean iconOnFill(int sliderPos) {
        return sliderPos >= ICON_ON_FILL_FROM;
    }

    /** The command that reads a display's brightness. */
    public static String getCommand(int displayId) {
        return "cmd display get-brightness --id " + displayId;
    }

    /**
     * The command that sets a display's brightness.
     *
     * The value is formatted with a fixed locale-independent pattern. A locale
     * that writes decimals with a comma would produce "0,35", which the shell
     * splits into two arguments and the framework rejects - a bug that only
     * appears for some users.
     */
    public static String setCommand(int displayId, float v) {
        return "cmd display set-brightness "
                + String.format(java.util.Locale.ROOT, "%.4f", clamp(v))
                + " --id " + displayId;
    }

    /**
     * Parse `cmd display get-brightness` output.
     *
     * Returns -1 when it cannot be read, which callers must treat as "unknown"
     * rather than as a brightness - showing a slider at 0 because a command
     * failed would invite the user to drag a control that is not connected to
     * anything.
     */
    public static float parse(String out) {
        if (out == null) return -1f;
        String t = out.trim();
        if (t.isEmpty()) return -1f;
        // Take the first token: the command prints a bare float, but be
        // tolerant of anything appended.
        int sp = t.indexOf(' ');
        if (sp > 0) t = t.substring(0, sp);
        try {
            float v = Float.parseFloat(t);
            if (Float.isNaN(v) || v < 0f || v > 1f) return -1f;
            return v;
        } catch (NumberFormatException e) {
            return -1f;
        }
    }
}
