package com.thorfeatures.screenmove;

/**
 * Parsed form of `screenmove status`.
 *
 * The CLI prints a small fixed block:
 *
 * <pre>
 *   top screen    : display 0
 *   bottom screen : display 1
 *   focused       : display 0
 *   movable on top    : 261
 *   movable on bottom : &lt;none, launcher only&gt;
 * </pre>
 *
 * Parsing lives here rather than in the TileService so it can be exercised on
 * the host against recorded output, with no device and no root.
 *
 * Deliberately tolerant: an unparsable or truncated block yields an "unknown"
 * status rather than throwing, because the tile must still render something
 * sensible when the module is missing, root is denied, or the CLI changes.
 */
public final class ScreenStatus {

    /** Sentinel for "no display id known" and "no movable task". */
    public static final int NONE = -1;

    public final int topDisplay;
    public final int bottomDisplay;
    public final int focusedDisplay;
    public final int movableOnTop;
    public final int movableOnBottom;

    public ScreenStatus(int topDisplay, int bottomDisplay, int focusedDisplay,
                        int movableOnTop, int movableOnBottom) {
        this.topDisplay      = topDisplay;
        this.bottomDisplay   = bottomDisplay;
        this.focusedDisplay  = focusedDisplay;
        this.movableOnTop    = movableOnTop;
        this.movableOnBottom = movableOnBottom;
    }

    /** Nothing known at all -- module missing, root denied, or unreadable output. */
    public static ScreenStatus unknown() {
        return new ScreenStatus(NONE, NONE, NONE, NONE, NONE);
    }

    /** True when both panels were identified; the tile needs this to act. */
    public boolean displaysKnown() {
        return topDisplay != NONE && bottomDisplay != NONE;
    }

    /** True when there is something the tile could move, on either screen. */
    public boolean anythingMovable() {
        return movableOnTop != NONE || movableOnBottom != NONE;
    }

    /**
     * Which screen the tap would act on.
     *
     * Mirrors the CLI's own `toggle` rule: prefer whatever the focused display
     * is showing, because that is the app the user is looking at; if the
     * focused display shows only its launcher, act on the other screen, so one
     * tile serves both directions. Returns NONE when there is nothing to move.
     */
    public int sourceDisplayForToggle() {
        if (focusedDisplay == topDisplay && movableOnTop != NONE)       return topDisplay;
        if (focusedDisplay == bottomDisplay && movableOnBottom != NONE) return bottomDisplay;
        if (movableOnTop != NONE)    return topDisplay;
        if (movableOnBottom != NONE) return bottomDisplay;
        return NONE;
    }

    /** Parse the CLI's status block. Never throws. */
    public static ScreenStatus parse(String out) {
        if (out == null) return unknown();

        int top = NONE, bottom = NONE, focused = NONE, mTop = NONE, mBottom = NONE;

        for (String raw : out.split("\n")) {
            String line = raw.trim();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();

            // Order matters: "movable on top" also starts with neither "top
            // screen" nor "bottom screen", but check the movable keys first so
            // a future rename cannot make them fall through to a display key.
            if (key.equals("movable on top"))         mTop    = parseTask(val);
            else if (key.equals("movable on bottom")) mBottom = parseTask(val);
            else if (key.equals("top screen"))        top     = parseDisplay(val);
            else if (key.equals("bottom screen"))     bottom  = parseDisplay(val);
            else if (key.equals("focused"))           focused = parseDisplay(val);
        }
        return new ScreenStatus(top, bottom, focused, mTop, mBottom);
    }

    /** "display 1" -> 1; "&lt;not found&gt;" / anything else -> NONE. */
    private static int parseDisplay(String val) {
        if (val.startsWith("display ")) return parseIntOr(val.substring(8).trim(), NONE);
        return NONE;
    }

    /** "261" -> 261; "&lt;none, launcher only&gt;" -> NONE. */
    private static int parseTask(String val) {
        if (val.startsWith("<")) return NONE;
        return parseIntOr(val, NONE);
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
