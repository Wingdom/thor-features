package com.thorfeatures.screenpanel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 2: a frame rate for the game on the <em>other</em> screen.
 *
 * <h3>Why this is not a Choreographer</h3>
 *
 * The obvious counter is wrong here. {@code Choreographer.postFrameCallback}
 * measures the frame rate of the process that registered it, so in the panel
 * it measures the panel - a static card on the 3.92" screen, which will
 * happily report a steady 60 while the game on the main screen stutters at
 * 24. It would be a gauge that is always plausible and never right, which is
 * worse than no gauge: this project has already shipped one control that
 * looked alive and did nothing, and that cost three rounds of "did it dim?".
 *
 * A real counter has to read the compositor, because SurfaceFlinger is the
 * only process that sees every application's frames.
 *
 * <h3>Status: UNVERIFIED</h3>
 *
 * Nothing in this file has run against a device. It is the parsing and
 * layer-selection logic for the probe described in
 * {@code NOTES.local.md}, written so that the probe is one command away and
 * its output has somewhere to land. Three specific things are unknown:
 *
 * <ol>
 * <li><b>Whether {@code --latency} still answers.</b> It is a long-standing
 *     SurfaceFlinger debug interface, but it predates the current frame
 *     timeline and has been reported empty or zero-filled on newer releases.
 *     Android 17 on this device is untested.</li>
 * <li><b>Whether the panel's root context may call it.</b> The GPU nodes are
 *     {@code Permission denied} under SELinux despite uid 0, in the context a
 *     command string runs in. {@code dumpsys} is a different route - a binder
 *     call to a service rather than a sysfs read - so it may well be allowed
 *     where kgsl is not, but that is a hypothesis.</li>
 * <li><b>Whether it is cheap enough.</b> A counter that costs frames to
 *     measure frames is self-defeating. {@code screenmove status} spends about
 *     120 ms in three {@code dumpsys} services, so one {@code dumpsys} per
 *     tick is plausibly in that order - acceptable at 1 Hz, not at 10.</li>
 * </ol>
 *
 * If the probe says no, the honest outcome is to ship phase 1 without this
 * and record why, rather than leave a gauge reading {@code --}.
 *
 * <h3>Prior art</h3>
 *
 * {@code stormpanda/better-dual-screen-assistant} does this on the stock ROM
 * with a native SurfaceFlinger readout. Worth reading for the approach, but
 * it targets stock Android 13 with AYN's framework and several of its levers
 * are dead here, so it is a reference and not a recipe.
 */
public final class Fps {

    /** No number this tick. Same sentinel as the rest of the telemetry. */
    public static final long ABSENT = Telemetry.ABSENT;

    /**
     * {@code --latency} marks a frame it has no timestamp for with this.
     *
     * It is {@code INT64_MAX}, not a plausible time. Leaving these in would
     * put a timestamp 292 years in the future into the window and compute a
     * frame rate of approximately zero from a perfectly healthy game, so
     * filtering them is not tidying - it is the difference between a reading
     * and nonsense.
     */
    static final long PENDING = Long.MAX_VALUE;

    private Fps() {}

    // --- which layer -------------------------------------------------------

    /** Ask the compositor what layers exist. */
    public static String listCommand() {
        return "dumpsys SurfaceFlinger --list 2>/dev/null";
    }

    /**
     * Ask the window manager about focus, per display.
     *
     * <b>The whole dump, not a grep.</b> That is deliberate and it is the
     * difference between this working and silently measuring the wrong thing.
     * The panel is a focused window itself while it is open - it is on the
     * bottom screen taking taps - so a flat {@code grep mCurrentFocus} finds
     * the panel first and a frame counter built on it would measure the
     * panel's own static card. The answer needed is "what has focus on
     * display 0", which only exists as a line *inside* a display's section of
     * the dump, so the section structure has to survive to the parser.
     *
     * This repository already documents the same trap from the other end:
     * {@code screenmove status} has to read the full {@code dumpsys window}
     * because the focused display id appears nowhere else, and a flat grep of
     * the activity dump "reports an activity on both displays at once".
     */
    public static String focusedPackageCommand() {
        return "dumpsys window displays 2>/dev/null";
    }

    /**
     * The package focused on one display, out of {@code dumpsys window
     * displays}.
     *
     * The dump is sectioned per display:
     * <pre>
     *   Display: mDisplayId=0
     *     ...
     *     mCurrentFocus=Window{a1 u0 com.foo.game/com.foo.game.MainActivity}
     *   Display: mDisplayId=1
     *     ...
     *     mCurrentFocus=Window{b2 u0 com.thorfeatures.screenpanel/...}
     * </pre>
     *
     * Parsed as a state machine over the section headers rather than by
     * matching a window line directly, because the only thing tying a focus
     * line to a display is which header it follows. Lines before any header
     * are ignored: a focus line outside a section belongs to no display, and
     * guessing that it means display 0 is how the panel's own window would
     * get picked up again.
     *
     * @return the package name, or null when that display has no focus.
     */
    public static String parseFocusedPackageOnDisplay(String out, int displayId) {
        if (out == null) return null;
        boolean inTarget = false;
        boolean seenHeader = false;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            int d = line.indexOf("mDisplayId=");
            if (d >= 0) {
                seenHeader = true;
                String tail = line.substring(d + "mDisplayId=".length());
                int end = 0;
                while (end < tail.length() && Character.isDigit(tail.charAt(end))) end++;
                long id = Telemetry.parseLong(tail.substring(0, end));
                inTarget = (id != Telemetry.ABSENT && id == displayId);
                continue;
            }
            if (!seenHeader || !inTarget) continue;
            if (!line.startsWith("mCurrentFocus")) continue;
            String pkg = parseFocusedPackage(line);
            if (pkg != null) return pkg;
        }
        return null;
    }

    /**
     * The package out of a focus line.
     *
     * The lines look like:
     * <pre>
     *   mCurrentFocus=Window{a1b2c3 u0 com.foo.game/com.foo.game.MainActivity}
     *   mFocusedApp=ActivityRecord{d4e5f6 u0 com.foo.game/.MainActivity t42}
     * </pre>
     * Both end in a {@code package/activity} token, so the rule is the same
     * for either: the first token containing a slash that looks like a
     * package name.
     */
    public static String parseFocusedPackage(String out) {
        if (out == null) return null;
        for (String raw : out.split("\n")) {
            for (String tok : raw.trim().split("[\\s{}]+")) {
                int slash = tok.indexOf('/');
                if (slash <= 0) continue;
                String pkg = tok.substring(0, slash);
                if (pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return pkg;
            }
        }
        return null;
    }

    /**
     * Pick the layer that carries a package's rendered frames.
     *
     * A focused app owns several layers and only one of them is the one being
     * drawn into at the game's rate. A {@code SurfaceView} layer is preferred
     * because that is what a game renders into - its own surface, composited
     * beneath the activity's view hierarchy - and the activity's own layer
     * would then be a mostly-static overlay reporting the UI's rate instead
     * of the game's.
     *
     * Blast layers ({@code SurfaceView[pkg/act](BLAST)#0}) are the current
     * naming and are matched by substring rather than by exact shape, so a
     * rename does not silently fall through to the wrong layer.
     *
     * @return the layer name, or null when the package has no layer listed.
     */
    public static String pickLayer(String listOut, String pkg) {
        if (listOut == null || pkg == null || pkg.isEmpty()) return null;
        String best = null;
        for (String raw : listOut.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || !line.contains(pkg)) continue;
            // Skip the compositor's own bookkeeping layers, which contain a
            // package name but never a game's frames.
            if (line.startsWith("Dim Layer") || line.contains("ScreenDecor")
                    || line.contains("InputMethod") || line.contains("Splash")) continue;
            if (line.contains("SurfaceView")) return line;   // best possible match
            if (best == null) best = line;                   // fall back to the activity layer
        }
        return best;
    }

    // --- reading it --------------------------------------------------------

    /**
     * Single-quote a string for the shell.
     *
     * This is the one command in the panel built from text the device chose:
     * layer names contain brackets, hashes, slashes and spaces, all of which
     * a bare shell word would split or glob. The panel's standing invariant
     * is that no shell metacharacter reaches an argv, and quoting is how that
     * is kept for a value that cannot be a literal.
     *
     * An embedded single quote is closed, escaped and reopened - the standard
     * {@code '\''} dance - so there is no input that escapes the quoting.
     */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Whether a layer name is one we are willing to put in a command.
     *
     * Quoting handles metacharacters, so this is about newlines and control
     * characters, which quoting cannot contain: a newline inside a quoted
     * word would end the command and start another one. A name like that is
     * not a layer we understand, so it is dropped.
     */
    public static boolean isSafeLayer(String layer) {
        if (layer == null || layer.isEmpty() || layer.length() > 512) return false;
        for (int i = 0; i < layer.length(); i++) {
            char c = layer.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0' || c < 0x20) return false;
        }
        return true;
    }

    /** Frame timestamps for one layer, or null when the name is unusable. */
    public static String latencyCommand(String layer) {
        if (!isSafeLayer(layer)) return null;
        return "dumpsys SurfaceFlinger --latency " + shellQuote(layer) + " 2>/dev/null";
    }

    /** What one {@code --latency} dump says. */
    public static final class Sample {
        /** Frames per second over the window, or {@link #ABSENT}. */
        public final double fps;
        /** The display's own rate in Hz from the dump's first line, or -1. */
        public final double refreshHz;
        /** How many usable present timestamps the window held. */
        public final int frames;

        Sample(double fps, double refreshHz, int frames) {
            this.fps = fps; this.refreshHz = refreshHz; this.frames = frames;
        }
        @Override public String toString() {
            return String.format(Locale.ROOT, "fps=%.1f refresh=%.1f frames=%d",
                    fps, refreshHz, frames);
        }
    }

    /** A sample carrying no reading. */
    public static final Sample NO_SAMPLE = new Sample(ABSENT, -1, 0);

    /**
     * Turn a {@code --latency} dump into a frame rate.
     *
     * The format is a refresh period in nanoseconds on the first line, then up
     * to 128 rows of three timestamps:
     * <pre>
     *   16666667
     *   &lt;desired present&gt;  &lt;actual present&gt;  &lt;frame ready&gt;
     * </pre>
     *
     * The rate comes from the <b>actual present</b> column, the middle one.
     * That is when the compositor put the frame on the glass, which is the
     * only column that is a frame rate - "desired" is the app's intent and
     * "ready" is when its buffer was finished, and a game that renders early
     * and waits for vsync has a "ready" rate far above what anybody sees.
     *
     * The divisor is the number of <em>intervals</em>, not of frames: n
     * timestamps bound n-1 frame periods. Using n would under-report by
     * 1/128th at a full window and by a fifth on a short one, and short
     * windows are exactly the case that matters - a game that just launched.
     */
    public static Sample parseLatency(String out) {
        if (out == null) return NO_SAMPLE;
        String[] lines = out.split("\n");
        if (lines.length == 0) return NO_SAMPLE;

        double refreshHz = -1;
        long period = Telemetry.parseLong(lines[0].trim());
        if (period != Telemetry.ABSENT && period > 0) refreshHz = 1e9 / period;

        List<Long> present = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] col = lines[i].trim().split("\\s+");
            if (col.length < 3) continue;
            long actual = Telemetry.parseLong(col[1]);
            if (actual == Telemetry.ABSENT || actual <= 0 || actual == PENDING) continue;
            present.add(actual);
        }
        if (present.size() < 2) return new Sample(ABSENT, refreshHz, present.size());

        long first = present.get(0);
        long last  = present.get(present.size() - 1);
        double seconds = (last - first) / 1e9;
        if (seconds <= 0) return new Sample(ABSENT, refreshHz, present.size());

        double fps = (present.size() - 1) / seconds;
        return new Sample(fps, refreshHz, present.size());
    }

    /**
     * "58 fps", or an em dash.
     *
     * Rounded to whole frames. A gauge reading "57.8" invites reading a
     * tenth of a frame as meaningful when the window is 128 frames of a
     * compositor queue - the honest precision here is one frame.
     */
    public static String format(Sample s) {
        if (s == null || s.fps == ABSENT) return Telemetry.NONE;
        return Math.round(s.fps) + " fps";
    }

    /**
     * The bar, scaled to the display's own refresh rate.
     *
     * Against the panel's rate rather than a fixed 60, because this device
     * runs the main screen at 120 Hz and a 120 fps game would peg a 60-scaled
     * bar and look identical to one at exactly 60. Falls back to 60 only when
     * the dump gave no period.
     */
    public static int percent(Sample s) {
        if (s == null || s.fps == ABSENT) return -1;
        double top = s.refreshHz > 0 ? s.refreshHz : 60.0;
        return (int) Math.max(0, Math.min(100, Math.round(s.fps * 100.0 / top)));
    }
}
