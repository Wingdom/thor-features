package com.thorfeatures.screenpanel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.thorfeatures.screenpanel.ScreenMode.Mode;
import com.thorfeatures.screenpanel.ScreenMode.Op;
import com.thorfeatures.screenpanel.ScreenMode.Op.Kind;

/**
 * Host tests for thor-screenpanel's pure logic. No device, no Android.
 *
 * The sequencing in ScreenMode is the part that can lose the user an app -
 * blanking a screen that still holds the foreground task leaves a running,
 * focused app on a panel that is powered down and no longer takes touch. So
 * the ordering assertions here are the point of this file, not incidental.
 */
public final class TestMain {

    private static int pass = 0, fail = 0;

    private static void check(String label, Object expected, Object actual) {
        String e = String.valueOf(expected), a = String.valueOf(actual);
        if (e.equals(a)) {
            pass++;
            System.out.println("  ok    " + label);
        } else {
            fail++;
            System.out.println("  FAIL  " + label);
            System.out.println("       expected [" + e + "] got [" + a + "]");
        }
    }

    /** The bottom display is 1 on this unit, but never assumed - pass it in. */
    private static final int BOTTOM = 1;

    private static String planOf(Mode target, boolean top, boolean bottom) {
        return ScreenMode.plan(target, top, bottom, BOTTOM).toString();
    }

    public static void main(String[] args) {
        System.out.println("thor-screenpanel logic tests");

        // --- reading the current mode back out of the blanked state ------------
        check("current: nothing dark is DUAL",     Mode.DUAL,        ScreenMode.current(false, false));
        check("current: bottom dark is TOP_ONLY",  Mode.TOP_ONLY,    ScreenMode.current(false, true));
        check("current: top dark is BOTTOM_ONLY",  Mode.BOTTOM_ONLY, ScreenMode.current(true, false));
        // Both dark is not a mode the UI can select. It would mean a black
        // device. Report DUAL so that selecting any mode uncovers out of it
        // rather than leaving the user stuck.
        check("current: both dark heals to DUAL",  Mode.DUAL,        ScreenMode.current(true, true));

        // --- no-op transitions ------------------------------------------------
        // Selecting the mode you are already in must do nothing at all. In
        // particular it must not re-issue a move, which would front a
        // backgrounded app (move-stack is not transparent).
        check("DUAL -> DUAL is empty",               "[]", planOf(Mode.DUAL, false, false));
        check("TOP_ONLY -> TOP_ONLY is empty",       "[]", planOf(Mode.TOP_ONLY, false, true));
        check("BOTTOM_ONLY -> BOTTOM_ONLY is empty", "[]", planOf(Mode.BOTTOM_ONLY, true, false));

        // --- the orderings that matter ---------------------------------------
        // Going to TOP_ONLY: rescue the bottom screen's app first, then black
        // it out. A plan that dark first would hide a live app.
        check("DUAL -> TOP_ONLY moves before covering",
              "[MOVE_TO_TOP, BLANK:1]", planOf(Mode.TOP_ONLY, false, false));

        check("DUAL -> BOTTOM_ONLY moves before covering",
              "[MOVE_TO_BOTTOM, BLANK:0]", planOf(Mode.BOTTOM_ONLY, false, false));

        // Coming back: uncover before anything else, so the screen is visible
        // before it is asked to host something.
        check("TOP_ONLY -> DUAL just uncovers the bottom",
              "[UNBLANK:1]", planOf(Mode.DUAL, false, true));

        check("BOTTOM_ONLY -> DUAL just uncovers the top",
              "[UNBLANK:0]", planOf(Mode.DUAL, true, false));

        // The cross transition is the hard one: both an uncover and a cover,
        // with a move in between, and the uncover has to come first.
        check("TOP_ONLY -> BOTTOM_ONLY uncovers bottom, moves, covers top",
              "[UNBLANK:1, MOVE_TO_BOTTOM, BLANK:0]", planOf(Mode.BOTTOM_ONLY, false, true));

        check("BOTTOM_ONLY -> TOP_ONLY uncovers top, moves, covers bottom",
              "[UNBLANK:0, MOVE_TO_TOP, BLANK:1]", planOf(Mode.TOP_ONLY, true, false));

        // Healing out of the nonsense both-dark state must leave nothing
        // dark, whichever mode is chosen.
        check("both dark -> DUAL uncovers both",
              "[UNBLANK:1, UNBLANK:0]", planOf(Mode.DUAL, true, true));

        // --- invariants, asserted over every transition ----------------------
        // 1. No plan ever ends with a display dark that the target mode
        //    says should be visible, and none leaves a required cover off.
        //    This is the real contract; the string assertions above are just
        //    readable instances of it.
        List<Mode> modes = Arrays.asList(Mode.DUAL, Mode.TOP_ONLY, Mode.BOTTOM_ONLY);
        List<String> badEnd = new ArrayList<>();
        List<String> badOrder = new ArrayList<>();
        List<String> badIdempotence = new ArrayList<>();
        for (Mode target : modes) {
            for (boolean top : new boolean[] { false, true }) {
                for (boolean bot : new boolean[] { false, true }) {
                    List<Op> ops = ScreenMode.plan(target, top, bot, BOTTOM);

                    // Simulate the plan against the starting state.
                    boolean t = top, b = bot;
                    int firstCover = -1, lastUncover = -1, firstMove = -1, lastCover = -1;
                    for (int i = 0; i < ops.size(); i++) {
                        Op op = ops.get(i);
                        switch (op.kind) {
                            case BLANK:
                                if (op.display == ScreenMode.TOP) t = true; else b = true;
                                if (firstCover < 0) firstCover = i;
                                lastCover = i;
                                break;
                            case UNBLANK:
                                if (op.display == ScreenMode.TOP) t = false; else b = false;
                                lastUncover = i;
                                break;
                            default:
                                if (firstMove < 0) firstMove = i;
                                break;
                        }
                    }

                    boolean[] want = ScreenMode.darkFor(target);
                    String ctx = target + " from(top=" + top + ",bot=" + bot + ")";
                    if (t != want[0] || b != want[1]) badEnd.add(ctx + " ended top=" + t + ",bot=" + b);

                    // 2. Every uncover precedes every move, and every move
                    //    precedes every cover.
                    if (lastUncover >= 0 && firstMove >= 0 && lastUncover > firstMove)
                        badOrder.add(ctx + " uncover after move");
                    if (firstMove >= 0 && firstCover >= 0 && firstMove > firstCover)
                        badOrder.add(ctx + " move after cover");
                    if (lastUncover >= 0 && lastCover >= 0 && lastUncover > lastCover)
                        badOrder.add(ctx + " uncover after cover");

                    // 3. Re-planning from the state the plan produced yields
                    //    an empty plan. Applying a mode twice must not do work
                    //    twice - the drift bug thor-fanctl had.
                    if (!ScreenMode.plan(target, t, b, BOTTOM).isEmpty())
                        badIdempotence.add(ctx);
                }
            }
        }
        check("every transition reaches the target blanked state", "[]", badEnd.toString());
        check("every plan orders uncover -> move -> cover",        "[]", badOrder.toString());
        check("every mode is idempotent on re-application",       "[]", badIdempotence.toString());

        // 4. No plan ever covers both screens at once, at any intermediate
        //    step. A moment with both panels black is a device that looks
        //    dead, even if it recovers.
        List<String> bothBlack = new ArrayList<>();
        for (Mode target : modes) {
            for (boolean top : new boolean[] { false, true }) {
                for (boolean bot : new boolean[] { false, true }) {
                    if (top && bot) continue;   // already black before we start
                    boolean t = top, b = bot;
                    for (Op op : ScreenMode.plan(target, top, bot, BOTTOM)) {
                        if (op.kind == Kind.BLANK) {
                            if (op.display == ScreenMode.TOP) t = true; else b = true;
                        } else if (op.kind == Kind.UNBLANK) {
                            if (op.display == ScreenMode.TOP) t = false; else b = false;
                        }
                        if (t && b) bothBlack.add(target + " from(top=" + top + ",bot=" + bot + ")");
                    }
                }
            }
        }
        check("no plan ever blacks both screens mid-transition", "[]", bothBlack.toString());

        // --- the long press --------------------------------------------------
        // Stock's long press toggled the bottom screen, and it is also the way
        // back when the bottom screen is dark.
        check("long press from DUAL turns the bottom off",   Mode.TOP_ONLY, ScreenMode.longPressTarget(Mode.DUAL));
        check("long press from TOP_ONLY turns it back on",   Mode.DUAL,     ScreenMode.longPressTarget(Mode.TOP_ONLY));
        // From BOTTOM_ONLY the "toggle the bottom" reading would blank the
        // only lit screen. Go to DUAL instead.
        check("long press from BOTTOM_ONLY goes to DUAL",    Mode.DUAL,     ScreenMode.longPressTarget(Mode.BOTTOM_ONLY));

        // The long press must always be an escape: from any mode, repeatedly
        // long-pressing has to reach DUAL, because that is the state in which
        // both screens are usable. Guards against a cycle that could strand
        // the user on a dark screen.
        List<String> stranded = new ArrayList<>();
        for (Mode from : modes) {
            Mode m = from;
            boolean reached = false;
            for (int i = 0; i < 4; i++) {
                m = ScreenMode.longPressTarget(m);
                if (m == Mode.DUAL) { reached = true; break; }
            }
            if (!reached) stranded.add(from.toString());
        }
        check("long press always reaches DUAL within 4 presses", "[]", stranded.toString());

        // --- resolved display ids are honoured -------------------------------
        // The bottom display is 1 today but was resolved, not assumed. A plan
        // built with a different id must reference that id.
        check("bottom display id is not hardcoded",
              "[MOVE_TO_TOP, BLANK:7]",
              ScreenMode.plan(Mode.TOP_ONLY, false, false, 7).toString());

        // --- the command layer ----------------------------------------------
        // A plan is only correct if it turns into the right commands. These
        // pin the mapping, including the display-id-to-word translation that
        // exists because screenblank addresses hardware nodes, not displays.
        check("blank bottom becomes screenblank off bottom",
              "[/system/bin/screenmove to-top, /system/bin/screenblank off bottom]",
              PanelCommands.commandsFor(ScreenMode.plan(Mode.TOP_ONLY, false, false, BOTTOM)).toString());

        check("blank top becomes screenblank off top",
              "[/system/bin/screenmove to-bottom, /system/bin/screenblank off top]",
              PanelCommands.commandsFor(ScreenMode.plan(Mode.BOTTOM_ONLY, false, false, BOTTOM)).toString());

        check("returning to dual unblanks",
              "[/system/bin/screenblank on bottom]",
              PanelCommands.commandsFor(ScreenMode.plan(Mode.DUAL, false, true, BOTTOM)).toString());

        // Display 0 is the only "top". Any other id is the bottom panel,
        // whatever number it was assigned at boot.
        check("display 0 is top", "top", PanelCommands.screenWord(0));
        check("display 1 is bottom", "bottom", PanelCommands.screenWord(1));
        check("a renumbered second display is still bottom", "bottom", PanelCommands.screenWord(7));

        // --- nothing injectable reaches the argv ------------------------------
        // Every command is built from literals in PanelCommands. Assert that
        // directly over every op the planner can produce, so that adding a
        // user-supplied field later fails here rather than silently shipping.
        List<String> injectable = new ArrayList<>();
        for (Mode target : modes) {
            for (boolean t : new boolean[] { false, true }) {
                for (boolean b : new boolean[] { false, true }) {
                    for (String cmd : PanelCommands.commandsFor(ScreenMode.plan(target, t, b, BOTTOM))) {
                        if (cmd.matches(".*[;&|$`><\\\\\'\"\\n].*")) injectable.add(cmd);
                    }
                }
            }
        }
        check("no command contains a shell metacharacter", "[]", injectable.toString());

        check("su argv is exactly three words",
              "[su, -c, /system/bin/screenblank off bottom]",
              Arrays.toString(PanelCommands.suArgv("/system/bin/screenblank off bottom")));

        // --- reading state back ----------------------------------------------
        // Real screenblank output, captured from the device 2026-10-22.
        String lit =
            "top     screen : on    touch enabled   backlight /sys/class/backlight/panel0-backlight\n" +
            "        touch  : /sys/class/input/input29/device\n" +
            "bottom  screen : on    touch enabled   backlight /sys/class/backlight/panel1-backlight\n" +
            "        touch  : /sys/class/input/input28/device\n";
        String bottomOff =
            "top     screen : on    touch enabled   backlight /sys/class/backlight/panel0-backlight\n" +
            "        touch  : /sys/class/input/input29/device\n" +
            "bottom  screen : OFF   touch disabled  backlight /sys/class/backlight/panel1-backlight\n" +
            "        touch  : /sys/class/input/input28/device\n";

        check("status: both lit parses to neither dark",
              "[false, false]", Arrays.toString(PanelCommands.parseBlankStatus(lit)));
        check("status: bottom off parses to bottom dark",
              "[false, true]", Arrays.toString(PanelCommands.parseBlankStatus(bottomOff)));
        check("status: bottom off reads as TOP_ONLY",
              Mode.TOP_ONLY,
              ScreenMode.current(PanelCommands.parseBlankStatus(bottomOff)[0],
                                 PanelCommands.parseBlankStatus(bottomOff)[1]));

        // The backlight path on every line ends in "panel0-backlight" /
        // "panel1-backlight", and the touch column says "enabled"/"disabled".
        // A parser matching "off" or "on" anywhere in the line would trip on
        // those. This is the regression guard for reading the wrong column.
        check("status: the backlight path does not confuse the parser",
              "[false, false]", Arrays.toString(PanelCommands.parseBlankStatus(lit)));

        // Garbage in must not report a screen as dark - that would make the
        // panel offer a mode that leaves a panel powered down.
        check("status: unparseable output reports nothing dark",
              "[false, false]", Arrays.toString(PanelCommands.parseBlankStatus("su: not found")));
        check("status: empty output reports nothing dark",
              "[false, false]", Arrays.toString(PanelCommands.parseBlankStatus("")));
        check("status: null output reports nothing dark",
              "[false, false]", Arrays.toString(PanelCommands.parseBlankStatus(null)));

        // --- the no-op move bug ----------------------------------------------
        // `screenmove` exits 1 when there is nothing to move, which is the
        // normal case on an idle device. The executor aborts a plan on the
        // first non-zero exit, so an unconditional move meant "Only bottom
        // screen" aborted before blanking anything and reported failure.
        // Reported from hardware 2026-10-22.
        String idle =
            "top screen    : display 0\n" +
            "bottom screen : display 1\n" +
            "focused       : display 1\n" +
            "movable on top    : <none, launcher only>\n" +
            "movable on bottom : <none, launcher only>\n";
        String bottomBusy =
            "top screen    : display 0\n" +
            "bottom screen : display 1\n" +
            "focused       : display 1\n" +
            "movable on top    : <none, launcher only>\n" +
            "movable on bottom : 288\n";

        check("movable: idle device has nothing on either screen",
              "[false, false]", Arrays.toString(PanelCommands.parseMovable(idle)));
        check("movable: a task id on the bottom is detected",
              "[false, true]", Arrays.toString(PanelCommands.parseMovable(bottomBusy)));
        check("movable: unparseable output reports nothing movable",
              "[false, false]", Arrays.toString(PanelCommands.parseMovable("su: not found")));

        // On an idle device every mode must be reachable with no move at all.
        check("idle: BOTTOM_ONLY needs only the blank",
              "[/system/bin/screenblank off top]",
              PanelCommands.commandsFor(PanelCommands.dropNoOpMoves(
                      ScreenMode.plan(Mode.BOTTOM_ONLY, false, false, BOTTOM), false, false)).toString());
        check("idle: TOP_ONLY needs only the blank",
              "[/system/bin/screenblank off bottom]",
              PanelCommands.commandsFor(PanelCommands.dropNoOpMoves(
                      ScreenMode.plan(Mode.TOP_ONLY, false, false, BOTTOM), false, false)).toString());

        // But when there IS something to rescue, the move must survive - that
        // is the whole safety property, and dropping it would blank a screen
        // holding a visible app.
        check("busy bottom: TOP_ONLY keeps the rescue move",
              "[/system/bin/screenmove to-top, /system/bin/screenblank off bottom]",
              PanelCommands.commandsFor(PanelCommands.dropNoOpMoves(
                      ScreenMode.plan(Mode.TOP_ONLY, false, false, BOTTOM), false, true)).toString());
        check("busy top: BOTTOM_ONLY keeps the rescue move",
              "[/system/bin/screenmove to-bottom, /system/bin/screenblank off top]",
              PanelCommands.commandsFor(PanelCommands.dropNoOpMoves(
                      ScreenMode.plan(Mode.BOTTOM_ONLY, false, false, BOTTOM), true, false)).toString());

        // A move is only ever dropped because its SOURCE screen is empty.
        // Swept over every transition and every movable combination: if a
        // plan contained a move and the screen it rescues was occupied, that
        // move must still be there afterwards.
        List<String> lostRescue = new ArrayList<>();
        for (Mode target : modes) {
            for (boolean t : new boolean[] { false, true }) {
                for (boolean b : new boolean[] { false, true }) {
                    for (boolean mt : new boolean[] { false, true }) {
                        for (boolean mb : new boolean[] { false, true }) {
                            List<Op> full = ScreenMode.plan(target, t, b, BOTTOM);
                            List<Op> kept = PanelCommands.dropNoOpMoves(full, mt, mb);
                            for (Op op : full) {
                                boolean sourceOccupied =
                                        (op.kind == Kind.MOVE_TO_TOP && mb)
                                     || (op.kind == Kind.MOVE_TO_BOTTOM && mt);
                                if (sourceOccupied && !kept.contains(op))
                                    lostRescue.add(target + " dropped " + op);
                            }
                            // Blanks and unblanks are never touched.
                            for (Op op : full) {
                                if ((op.kind == Kind.BLANK || op.kind == Kind.UNBLANK)
                                        && !kept.contains(op))
                                    lostRescue.add(target + " dropped " + op);
                            }
                        }
                    }
                }
            }
        }
        check("a move is dropped only when its source screen is empty", "[]", lostRescue.toString());

        System.out.println();
        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }
}
