package com.thorfeatures.screenpanel;

/**
 * The two global tiles that are plain system settings rather than one of this
 * project's own CLIs: haptics, and clearing background apps.
 *
 * Stock's panel had both. Its vibration tile wrote `Settings.System`
 * `vibrate_on`, which **does not exist on this build** — it reads `null`.
 * `haptic_feedback_enabled` is the live key here and reads `1`, so that is
 * what this drives. Recorded because the stock key looks right and silently
 * does nothing.
 */
public final class SystemToggle {

    private SystemToggle() {}

    // --- haptics -------------------------------------------------------------

    public static final String HAPTICS_KEY = "haptic_feedback_enabled";

    public static String hapticsGetCommand() {
        return "settings get system " + HAPTICS_KEY;
    }

    public static String hapticsSetCommand(boolean on) {
        return "settings put system " + HAPTICS_KEY + " " + (on ? 1 : 0);
    }

    /**
     * Parse the haptics setting.
     *
     * Returns null for "could not tell" — an unset key reads the literal
     * string "null", and a failed call gives nothing. Neither is "off": a
     * tile showing off when it cannot read the value would invite a tap that
     * turns haptics on for someone who already had them on.
     */
    public static Boolean parseHaptics(String out) {
        if (out == null) return null;
        String t = out.trim();
        if (t.isEmpty() || t.equals("null")) return null;
        if (t.equals("1")) return Boolean.TRUE;
        if (t.equals("0")) return Boolean.FALSE;
        return null;
    }

    // --- clean memory --------------------------------------------------------

    /**
     * Kill background processes. Stock called this "Clean Memory" and toasted
     * how many it got; this reports nothing because the count is not worth a
     * second command.
     *
     * `kill-all` only ever takes background processes — the foreground app and
     * persistent services are not candidates — so there is nothing to confirm
     * before running it.
     */
    public static String cleanMemoryCommand() {
        return "cmd activity kill-all";
    }

    // --- volume --------------------------------------------------------------

    /**
     * Clamp a volume index into a stream's range.
     *
     * Volume is an integer index, not a fraction: this stream reports
     * `[0..15]`, so the slider has 16 positions and the index is the value,
     * not a percentage of it. Unlike brightness, 0 is a legitimate setting —
     * silence is a thing people want, where a black screen is not.
     */
    public static int clampVolume(int index, int max) {
        if (max < 0) max = 0;
        if (index < 0) return 0;
        if (index > max) return max;
        return index;
    }

    /** Volume as a percentage, for the slider fill. */
    public static int volumePercent(int index, int max) {
        if (max <= 0) return 0;
        return Math.round(clampVolume(index, max) * 100f / max);
    }
}
