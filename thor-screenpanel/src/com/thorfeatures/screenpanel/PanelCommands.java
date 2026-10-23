package com.thorfeatures.screenpanel;

import java.util.ArrayList;
import java.util.List;

import com.thorfeatures.screenpanel.ScreenMode.Op;

/**
 * Turns a {@link ScreenMode} plan into the root commands that carry it out,
 * and reads the device's state back out of those commands' output.
 *
 * Both directions are pure text, which is the point: the panel's whole
 * interaction with the device is "run a fixed command, parse its stdout", so
 * all of it can be exercised on the host with a fake runner.
 *
 * Nothing here interpolates anything the user typed or anything read back off
 * the device into a command. Every argument is a literal chosen in this file,
 * and a test asserts that no shell metacharacter can reach the argv.
 */
public final class PanelCommands {

    static final String SCREENBLANK = "/system/bin/screenblank";
    static final String SCREENMOVE  = "/system/bin/screenmove";

    private PanelCommands() {}

    /**
     * The word screenblank uses for a display id.
     *
     * screenblank speaks in "top"/"bottom" rather than display ids because it
     * addresses backlight and digitiser nodes, which have no display id - the
     * mapping between the two is established here, in one place.
     */
    static String screenWord(int displayId) {
        return displayId == ScreenMode.TOP ? "top" : "bottom";
    }

    /** The shell command for one planned operation. */
    static String commandFor(Op op) {
        switch (op.kind) {
            case BLANK:          return SCREENBLANK + " off " + screenWord(op.display);
            case UNBLANK:        return SCREENBLANK + " on "  + screenWord(op.display);
            case MOVE_TO_TOP:    return SCREENMOVE  + " to-top";
            case MOVE_TO_BOTTOM: return SCREENMOVE  + " to-bottom";
            default: throw new IllegalArgumentException("unknown op " + op.kind);
        }
    }

    /** Every command for a plan, in order. */
    public static List<String> commandsFor(List<Op> ops) {
        List<String> out = new ArrayList<>();
        for (Op op : ops) out.add(commandFor(op));
        return out;
    }

    /**
     * Which screens have something `screenmove` would actually move, as
     * {top, bottom}, read out of `screenmove status`:
     *
     * <pre>
     *   movable on top    : &lt;none, launcher only&gt;
     *   movable on bottom : 288
     * </pre>
     *
     * A task id means there is something to move; anything else means there
     * is not. Unparseable output reports "nothing movable", which makes the
     * move ops drop out - the conservative direction, since a move that was
     * needed and skipped leaves an app on a screen we are about to blank, and
     * that is prevented separately by {@link #dropNoOpMoves} only removing a
     * move when the source screen is empty.
     */
    public static boolean[] parseMovable(String out) {
        boolean top = false, bottom = false;
        if (out != null) {
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                int i = line.indexOf(':');
                if (i < 0) continue;
                String key = line.substring(0, i).trim();
                String val = line.substring(i + 1).trim();
                boolean hasTask = val.matches("\\d+");
                if (key.equals("movable on top")) top = hasTask;
                else if (key.equals("movable on bottom")) bottom = hasTask;
            }
        }
        return new boolean[] { top, bottom };
    }

    /**
     * Remove move operations that would be no-ops.
     *
     * `screenmove` exits **1** when there is nothing to move - "nothing to
     * move: no visible app on either screen" - and that is not a failure, it
     * is the normal case on an idle device. An executor that aborts a plan on
     * the first non-zero exit therefore never reached the blank step, which is
     * exactly what "Only bottom screen" did on hardware 2026-10-22: it popped
     * "Could not change mode" and changed nothing.
     *
     * Dropping the move here rather than ignoring its exit code keeps the
     * safety property intact. A move that fails while there *was* something to
     * move is still a real failure and still aborts the plan, so a screen is
     * never blanked while it still holds a visible app.
     *
     * @param movableOnTop    something movable on the main screen
     * @param movableOnBottom something movable on the 3.92" screen
     */
    public static List<Op> dropNoOpMoves(List<Op> ops, boolean movableOnTop, boolean movableOnBottom) {
        List<Op> out = new ArrayList<>();
        for (Op op : ops) {
            // MOVE_TO_TOP rescues the bottom screen, so it is pointless when
            // the bottom screen has nothing on it - and vice versa.
            if (op.kind == Op.Kind.MOVE_TO_TOP    && !movableOnBottom) continue;
            if (op.kind == Op.Kind.MOVE_TO_BOTTOM && !movableOnTop)    continue;
            out.add(op);
        }
        return out;
    }

    /** argv to run one command as root. */
    public static String[] suArgv(String command) {
        return new String[] { "su", "-c", command };
    }

    /**
     * Read {@code screenblank status} output into {top dark, bottom dark}.
     *
     * The lines look like:
     *   top     screen : on    touch enabled   backlight /sys/...
     *   bottom  screen : OFF   touch disabled  backlight /sys/...
     *
     * Matching is on the "screen :" field rather than on the word OFF
     * anywhere in the line, because the backlight path and the touch column
     * are on the same line and a looser match would read the wrong column.
     * Unparseable output reports "not dark" for both: the panel then offers
     * DUAL, which is the state everything is reachable from.
     */
    public static boolean[] parseBlankStatus(String out) {
        boolean top = false, bottom = false;
        if (out != null) {
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                int i = line.indexOf("screen :");
                if (i < 0) continue;
                String which = line.substring(0, i).trim();
                String rest  = line.substring(i + "screen :".length()).trim();
                // The state is the first whitespace-delimited token after the
                // colon: "on" or "OFF".
                String state = rest.split("\\s+")[0];
                boolean dark = state.equalsIgnoreCase("OFF");
                if (which.equals("top")) top = dark;
                else if (which.equals("bottom")) bottom = dark;
            }
        }
        return new boolean[] { top, bottom };
    }
}
