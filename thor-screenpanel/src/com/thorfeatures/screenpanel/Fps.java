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
 * <h3>Status: verified on the device 2026-11-04</h3>
 *
 * All three of the unknowns this file was written around have been answered,
 * and two of them differently than expected.
 *
 * <ol>
 * <li><b>{@code --latency} still answers on Android 17.</b> It returns the
 *     same shape it always did - a refresh period in nanoseconds, then rows of
 *     three tab-separated timestamps. Against Dusklight it gave 65 frames
 *     about 8.43 ms apart, which is ~119 fps on a 120 Hz panel.</li>
 * <li><b>The panel's root context may call it.</b> It is a binder call to a
 *     service rather than a sysfs read, and it is allowed where
 *     {@code /sys/class/kgsl} was not.</li>
 * <li><b>It costs 35 ms.</b> Measured over five calls. On top of the 27 ms
 *     telemetry tick that is about 6% duty at 1 Hz, which is affordable - and
 *     it is why the layer name is cached rather than re-resolved per tick.</li>
 * </ol>
 *
 * <h3>What was wrong: the layer names</h3>
 *
 * The format this file originally parsed - {@code SurfaceView[pkg/act](BLAST)#0}
 * on its own line - is not what Android 17 prints. Every layer now arrives
 * wrapped:
 *
 * <pre>
 *   RequestedLayerState{f276fb1 SurfaceView[pkg/act](BLAST)#867 parentId=866}
 *   RequestedLayerState{ActivityRecord{1234 u0 pkg/.Act t80}#854 parentId=853}
 * </pre>
 *
 * Note the second line: the name itself contains braces, so the wrapper has to
 * be stripped by finding the <em>last</em> closing brace, not the first. The
 * trailing {@code key=value} attributes are then removed, and what is left -
 * spaces, brackets and all - is the name {@code --latency} wants.
 *
 * <h3>Only one layer actually carries frames</h3>
 *
 * A focused app owns eight or so layers and seven of them return the refresh
 * period and nothing else. For Dusklight only the {@code (BLAST)} child of the
 * SurfaceView had timestamps; its parent SurfaceView and the {@code VRI-}
 * layer were both empty. Rather than encode that as a rule,
 * {@link #pickLayers} returns candidates in preference order and the caller
 * takes the first that actually yields frames - which is self-correcting if a
 * future release reshuffles them, and costs the extra calls only when the
 * layer is resolved rather than on every tick.
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

    /** Layers that mention a package but can never carry its frames. */
    private static final String[] NEVER = {
        "ActivityRecordInputSink", "Background for", "Bounds for",
        "ActivityRecord{", "Dim Layer", "ScreenDecor", "InputMethod", "Splash",
    };

    /**
     * The layer name out of one {@code --list} line, or null.
     *
     * The wrapper is stripped by finding the last closing brace rather than
     * the first, because a layer name may itself contain braces - an
     * {@code ActivityRecord{...}} layer does - and matching the first would
     * truncate it mid-name. The trailing {@code key=value} attributes
     * ({@code parentId}, {@code z}, {@code relativeParentId}) are then
     * dropped; everything before them is the name, spaces included.
     */
    static String layerNameOf(String line) {
        if (line == null) return null;
        int open = line.indexOf('{');
        int close = line.lastIndexOf('}');
        if (open < 0 || close <= open) return null;
        String[] parts = line.substring(open + 1, close).trim().split(" ");
        int end = parts.length;
        while (end > 0 && parts[end - 1].matches("[A-Za-z][A-Za-z0-9_]*=[^ ]+")) end--;
        if (end == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        String name = sb.toString().trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * Candidate layers for a package's frames, best first.
     *
     * A ranking rather than a single answer, because only one of a focused
     * app's layers actually has timestamps and which one is not something to
     * assert from one device. The caller walks the list and keeps the first
     * that yields frames, so a release that reshuffles them costs a couple of
     * extra calls at resolve time instead of a silently dead counter.
     *
     * The order: a {@code (BLAST)} SurfaceView child first, since that is what
     * carried Dusklight's frames; then any SurfaceView, because a game renders
     * into its own surface rather than the activity's view hierarchy; then the
     * {@code VRI-} view-root layer, which is where an ordinary UI app's frames
     * would be; then anything else that named the package.
     */
    public static List<String> pickLayers(String listOut, String pkg) {
        List<String> blast = new ArrayList<>(), surface = new ArrayList<>(),
                     vri = new ArrayList<>(), rest = new ArrayList<>();
        if (listOut == null || pkg == null || pkg.isEmpty()) return blast;
        for (String raw : listOut.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || !line.contains(pkg)) continue;
            boolean skip = false;
            for (String n : NEVER) if (line.contains(n)) { skip = true; break; }
            if (skip) continue;
            String name = layerNameOf(line);
            if (name == null || !isSafeLayer(name)) continue;
            if (name.contains("SurfaceView") && name.contains("(BLAST)")) blast.add(name);
            else if (name.contains("SurfaceView")) surface.add(name);
            else if (name.startsWith("VRI-")) vri.add(name);
            else rest.add(name);
        }
        blast.addAll(surface);
        blast.addAll(vri);
        blast.addAll(rest);
        return blast;
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
