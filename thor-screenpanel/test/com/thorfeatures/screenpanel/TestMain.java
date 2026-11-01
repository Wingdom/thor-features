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

        // --- quick settings ---------------------------------------------------
        // The fan and joystick LEDs already have QS tiles; the panel offers the
        // same controls. The state lists come from the tiles' own enums rather
        // than being copied, so these tests also pin that the reuse is real.
        QuickSetting fan = QuickSettings.fan();
        QuickSetting stick = QuickSettings.stick();

        check("fan cycles the three tiers, in order",
              "[quiet, balanced, performance]", Arrays.toString(fan.wires));
        check("stick cycles off/charge/breathe",
              "[off, charge, breathe]", Arrays.toString(stick.wires));

        // USB is an alias of charge and is excluded from StickMode.choices();
        // offering both would be two buttons doing the same thing.
        check("stick does not offer the usb alias", -1, stick.indexOf("usb"));

        // Commands match each CLI's contract: `<binary> get` reads, bare wire sets.
        check("fan status command", "/system/bin/fanctl get", fan.statusCommand());
        check("fan set command", "/system/bin/fanctl balanced", fan.setCommand("balanced"));
        check("stick status command", "/system/bin/stickfx get", stick.statusCommand());

        // Real CLI output, captured from the device 2026-10-27.
        check("parses real `fanctl get` output", "Quiet", fan.labelFor("quiet"));
        check("parses real `stickfx get` output", "Charge", stick.labelFor("charge"));
        check("tolerates trailing newline and case", "Balanced", fan.labelFor("  BALANCED\n"));

        // --- cycling ----------------------------------------------------------
        check("fan quiet -> balanced", "balanced", fan.next("quiet"));
        check("fan balanced -> performance", "performance", fan.next("balanced"));
        check("fan performance wraps to quiet", "quiet", fan.next("performance"));

        // The state outside the cycle. `fanctl get` reports "default" when no
        // tier is applied and `stickfx get` reports "off". A tap must enter at
        // the FIRST entry: entering at the second would make the first
        // unreachable by tapping, which is the usual way a cycling control
        // goes wrong.
        check("fan enters the cycle at the first tier from default",
              "quiet", fan.next("default"));
        check("stick enters the cycle at its first entry from an unknown state",
              "off", stick.next("something-else"));
        check("an unreadable state still enters at the first entry",
              "quiet", fan.next(null));

        // An explicit "off" inside the cycle must not render as active. The
        // stick tile drew a lit icon beside the word Off until this was fixed.
        check("stick off does not look active", false, stick.isActive("off"));
        check("stick charge looks active", true, stick.isActive("charge"));
        check("stick breathe looks active", true, stick.isActive("breathe"));
        // The fan cycle has no off entry, so every tier is active.
        List<String> fanInactive = new ArrayList<>();
        for (String w : fan.wires) if (!fan.isActive(w)) fanInactive.add(w);
        check("every fan tier looks active", "[]", fanInactive.toString());
        check("a state outside the cycle is never active", false, fan.isActive("default"));

        check("default is shown as the inactive label", "Stock", fan.labelFor("default"));
        check("default does not look active", false, fan.isActive("default"));
        check("a tier looks active", true, fan.isActive("performance"));

        // Every entry must be reachable by repeated tapping from any start.
        // Guards against a cycle that skips or strands an option.
        for (QuickSetting q : new QuickSetting[] { fan, stick }) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            String w = q.next("completely-unknown");
            for (int i = 0; i < q.wires.length; i++) { seen.add(w); w = q.next(w); }
            check(q.kind + ": every state is reachable by tapping",
                  Arrays.toString(q.wires), seen.toString().replace("[", "[").replace("]", "]"));
        }

        // --- nothing injectable reaches a root command ------------------------
        List<String> qInjectable = new ArrayList<>();
        for (QuickSetting q : new QuickSetting[] { fan, stick }) {
            List<String> cmds = new ArrayList<>();
            cmds.add(q.statusCommand());
            for (String w : q.wires) cmds.add(q.setCommand(w));
            for (String c : cmds) {
                if (c.matches(".*[;&|$`><\\\\\'\"\\n].*")) qInjectable.add(c);
            }
        }
        check("no quick-setting command contains a shell metacharacter", "[]", qInjectable.toString());

        // Labels and wires must stay index-aligned, or the panel shows one
        // state and applies another.
        List<String> misaligned = new ArrayList<>();
        for (QuickSetting q : new QuickSetting[] { fan, stick }) {
            if (q.wires.length != q.labels.length) misaligned.add(q.kind + " length");
            for (int i = 0; i < q.wires.length; i++) {
                if (!q.labels[i].equals(q.labelFor(q.wires[i]))) misaligned.add(q.kind + " " + q.wires[i]);
            }
        }
        check("labels stay aligned with wires", "[]", misaligned.toString());

        // --- per-screen brightness --------------------------------------------
        // The framework scale is float [0,1]; the backlight node is 0-4095 and
        // the system setting is 0-255. Three scales on one device, so the
        // conversions are worth pinning.
        check("slider bottom is the usable minimum, not zero",
              0.01f, Brightness.fromSlider(0));
        check("slider top is full", 1.0f, Brightness.fromSlider(Brightness.STEPS));
        check("a value round-trips through the slider",
              50, Brightness.toSlider(Brightness.fromSlider(50)));

        // Every slider position must round-trip, or dragging would make the
        // thumb jump under the finger.
        List<String> rt = new ArrayList<>();
        for (int i = 0; i <= Brightness.STEPS; i++) {
            if (Brightness.toSlider(Brightness.fromSlider(i)) != i) rt.add(String.valueOf(i));
        }
        check("every slider position round-trips", "[]", rt.toString());

        // The minimum is deliberately not 0: at 0.0 the panel reads
        // brightness=1 and looks switched off rather than dim. Confirmed on
        // hardware 2026-10-28.
        check("clamp pulls zero up to the minimum", 0.01f, Brightness.clamp(0f));
        check("clamp pulls negatives up", 0.01f, Brightness.clamp(-5f));
        check("clamp pulls above-range down", 1.0f, Brightness.clamp(2f));
        check("clamp handles NaN", 0.01f, Brightness.clamp(Float.NaN));
        check("no slider position can command zero", true,
              Brightness.fromSlider(0) >= Brightness.MIN);

        // --- commands ---------------------------------------------------------
        check("get command targets the display",
              "cmd display get-brightness --id 1", Brightness.getCommand(1));
        check("set command formats the float and the id",
              "cmd display set-brightness 0.3000 --id 1", Brightness.setCommand(1, 0.3f));
        check("set command clamps before formatting",
              "cmd display set-brightness 0.0100 --id 1", Brightness.setCommand(1, 0f));

        // A comma decimal separator would split into two shell arguments.
        // Force a locale that uses one and assert the output is unchanged.
        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            check("set command is locale-independent",
                  "cmd display set-brightness 0.3500 --id 0", Brightness.setCommand(0, 0.35f));
        } finally {
            java.util.Locale.setDefault(saved);
        }

        List<String> bInject = new ArrayList<>();
        for (int id : new int[] { 0, 1, 7 }) {
            for (float v : new float[] { 0f, 0.01f, 0.5f, 1f }) {
                String c = Brightness.setCommand(id, v);
                if (c.matches(".*[;&|$`><\\\\\'\"\\n].*")) bInject.add(c);
            }
            if (Brightness.getCommand(id).matches(".*[;&|$`><\\\\\'\"\\n].*"))
                bInject.add(Brightness.getCommand(id));
        }
        check("no brightness command contains a shell metacharacter", "[]", bInject.toString());

        // --- reading it back --------------------------------------------------
        // Real output, captured from the device 2026-10-28.
        check("parses a real get-brightness reply", 0.38188976f, Brightness.parse("0.38188976"));
        check("tolerates a trailing newline", 0.6f, Brightness.parse("0.6\n"));

        // An unreadable value must be -1, not 0. A slider showing 0 because a
        // command failed invites dragging a control connected to nothing.
        check("an empty reply is unknown, not zero", -1f, Brightness.parse(""));
        check("a null reply is unknown", -1f, Brightness.parse(null));
        check("an error string is unknown", -1f, Brightness.parse("cmd: not found"));
        check("an out-of-range reply is unknown", -1f, Brightness.parse("4095"));
        check("a negative reply is unknown", -1f, Brightness.parse("-0.5"));

        // The icon sits at the right end of the track, so its colour has to
        // follow whether the fill has reached it. A fixed colour vanishes at
        // one end: a dark icon was invisible on the top slider at anything
        // below full brightness.
        check("icon is on the dark track at low brightness", false, Brightness.iconOnFill(0));
        check("icon is on the dark track mid-way", false, Brightness.iconOnFill(50));
        check("icon is on the fill at full brightness", true, Brightness.iconOnFill(100));
        check("the flip point is inside the track", true,
              Brightness.ICON_ON_FILL_FROM > 0 && Brightness.ICON_ON_FILL_FROM < 100);

        check("percent is reported for display", 60, Brightness.percent(0.6f));
        check("percent of the minimum is 1", 1, Brightness.percent(Brightness.MIN));

        // --- the persistent root session's framing ----------------------------
        // A long-lived `su` shell has no process exit to mark the end of a
        // reply, so every command is wrapped to emit a sentinel carrying $?.
        // Everything below is the part of that transport that is pure text.
        String sent = RootProtocol.newSentinel();

        check("a wrapped command reports its exit status", true,
              RootProtocol.wrap("true", sent).contains(sent + " $?"));
        // A subshell, so a `cd`, `set -e` or `exit` inside a command cannot
        // alter or kill the session shell.
        check("the command runs in a subshell", true,
              RootProtocol.wrap("exit 1", sent).startsWith("( exit 1 )"));
        // stdin closed, or a child that reads stdin eats the next queued
        // command - the same class of bug as adb swallowing stdin in a loop.
        check("the command cannot read the session's stdin", true,
              RootProtocol.wrap("cat", sent).contains("</dev/null"));
        check("stderr is folded into the reply", true,
              RootProtocol.wrap("x", sent).contains("2>&1"));

        check("a sentinel line is recognised", true,
              RootProtocol.isSentinel(sent + " 0", sent));
        check("ordinary output is not a sentinel", false,
              RootProtocol.isSentinel("quiet", sent));
        // A line that merely starts with the sentinel text but carries no
        // status is not a frame boundary.
        check("the sentinel needs its status field", false,
              RootProtocol.isSentinel(sent, sent));

        check("exit status is read off the sentinel", 0,
              RootProtocol.exitCodeOf(sent + " 0", sent));
        check("a non-zero status is read", 1,
              RootProtocol.exitCodeOf(sent + " 1", sent));
        // -1, never 0: callers treat 0 as success, so an unreadable status
        // must not be able to masquerade as one.
        check("an unreadable status is -1, not success", -1,
              RootProtocol.exitCodeOf(sent + " banana", sent));
        check("a non-sentinel line yields -1", -1,
              RootProtocol.exitCodeOf("some output", sent));
        check("null yields -1", -1, RootProtocol.exitCodeOf(null, sent));

        // --- the desync the random sentinel prevents --------------------------
        // A fixed sentinel could be printed by a command, truncating its own
        // reply and shifting every later reply onto the wrong command. Two
        // sessions must never share a marker.
        java.util.Set<String> sentinels = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 200; i++) sentinels.add(RootProtocol.newSentinel());
        check("every session gets a distinct sentinel", 200, sentinels.size());
        check("a sentinel is not guessable from the command text", false,
              RootProtocol.newSentinel().equals(RootProtocol.newSentinel()));

        // --- argv translation -------------------------------------------------
        // RootClient hands out `su -c <command>`; over a session the `su -c`
        // is implied and only the command travels.
        check("the command is taken out of a su argv", "/system/bin/fanctl get",
              RootProtocol.commandOf(PanelCommands.suArgv("/system/bin/fanctl get")));
        // Anything not of that exact shape must be refused so it falls back to
        // a real `su` rather than being silently reinterpreted.
        check("a non-su argv is refused", null,
              RootProtocol.commandOf(new String[] { "echo", "hi" }));
        check("a short argv is refused", null,
              RootProtocol.commandOf(new String[] { "su" }));
        check("a null argv is refused", null, RootProtocol.commandOf(null));

        // Every command the panel can build must survive the round trip
        // through the session transport unchanged.
        List<String> mangled = new ArrayList<>();
        List<String> all = new ArrayList<>();
        all.add(PanelCommands.SCREENBLANK + " status");
        all.add(PanelCommands.SCREENMOVE + " status");
        all.add(fan.statusCommand());
        all.add(stick.statusCommand());
        all.add(Brightness.getCommand(0));
        all.add(Brightness.setCommand(1, 0.5f));
        for (Mode m : modes) all.addAll(PanelCommands.commandsFor(
                ScreenMode.plan(m, false, false, BOTTOM)));
        for (String c : all) {
            if (!c.equals(RootProtocol.commandOf(PanelCommands.suArgv(c)))) mangled.add(c);
        }
        check("every panel command survives the transport unchanged", "[]", mangled.toString());

        // --- refresh rate -----------------------------------------------------
        // Two rates only. dumpsys lists fps=120.00001 and fps=60.000004 for
        // both panels and nothing else, so stock's 60/90/120 does not apply.
        check("exactly two rates are offered", "[60, 120]", Arrays.toString(RefreshRate.RATES));
        check("the reported rates map to 120", 120, RefreshRate.nearest(120.00001));
        check("the reported rates map to 60", 60, RefreshRate.nearest(60.000004));
        check("tapping the other half flips it", 120, RefreshRate.other(60));
        check("and back", 60, RefreshRate.other(120));

        // The width and height differ per screen and are read from the
        // Display at runtime, never hardcoded.
        check("set command carries the display's own size",
              "cmd display set-user-preferred-display-mode 1240 1080 60 1 true",
              RefreshRate.setCommand(1, 1240, 1080, 60));
        check("the main screen gets its own size",
              "cmd display set-user-preferred-display-mode 1920 1080 120 0 true",
              RefreshRate.setCommand(0, 1920, 1080, 120));
        check("get command targets the display",
              "cmd display get-user-preferred-display-mode 1", RefreshRate.getCommand(1));

        // Three replies seen on hardware, two of which mean "no preference".
        check("parses an explicit 60", 60,
              RefreshRate.parse("User preferred display mode: 1240 1080 60.0"));
        check("parses an explicit 120", 120,
              RefreshRate.parse("User preferred display mode: 1920 1080 120.0"));
        check("the never-set form reports the default", RefreshRate.DEFAULT,
              RefreshRate.parse("User preferred display mode: -1 -1 0.0"));
        check("the cleared form reports the default", RefreshRate.DEFAULT,
              RefreshRate.parse("User preferred display mode: null"));
        check("an unreadable reply reports the default", RefreshRate.DEFAULT,
              RefreshRate.parse(null));
        check("garbage reports the default", RefreshRate.DEFAULT,
              RefreshRate.parse("cmd: not found"));

        // Whatever comes back, the control must land on one of the two halves
        // — never on neither, which reads as broken rather than untouched.
        List<String> offScale = new ArrayList<>();
        String[] replies = {
            "User preferred display mode: 1240 1080 60.0",
            "User preferred display mode: -1 -1 0.0",
            "User preferred display mode: null", "", "  ", null, "nonsense",
            "User preferred display mode: 1240 1080 90.0"
        };
        for (String r : replies) {
            int v = RefreshRate.parse(r);
            if (v != RefreshRate.LOW && v != RefreshRate.HIGH)
                offScale.add(String.valueOf(r) + " -> " + v);
        }
        check("every reply resolves to one of the two rates", "[]", offScale.toString());

        List<String> rrInject = new ArrayList<>();
        for (int rate : RefreshRate.RATES) {
            String c = RefreshRate.setCommand(1, 1240, 1080, rate);
            if (c.matches(".*[;&|$`><\\\\\'\"\\n].*")) rrInject.add(c);
        }
        check("no refresh-rate command contains a shell metacharacter", "[]", rrInject.toString());

        // --- haptics ------------------------------------------------------------
        // Stock's vibrate_on reads null on this build; haptic_feedback_enabled
        // is the live key.
        check("haptics key is the one that exists here",
              "settings get system haptic_feedback_enabled", SystemToggle.hapticsGetCommand());
        check("haptics on", "settings put system haptic_feedback_enabled 1",
              SystemToggle.hapticsSetCommand(true));
        check("haptics off", "settings put system haptic_feedback_enabled 0",
              SystemToggle.hapticsSetCommand(false));
        check("parses 1 as on", Boolean.TRUE, SystemToggle.parseHaptics("1"));
        check("parses 0 as off", Boolean.FALSE, SystemToggle.parseHaptics("0\n"));

        // "Unset" and "unreadable" must NOT read as off: a tile showing off
        // invites a tap that turns haptics ON for someone who had them on.
        check("an unset key is unknown, not off", null, SystemToggle.parseHaptics("null"));
        check("an unreadable reply is unknown", null, SystemToggle.parseHaptics(null));
        check("an empty reply is unknown", null, SystemToggle.parseHaptics(""));

        // --- clean memory --------------------------------------------------------
        check("clean memory uses the verb that exists here",
              "cmd activity kill-all", SystemToggle.cleanMemoryCommand());

        // --- volume ---------------------------------------------------------------
        // An index in [0..max], not a fraction. Unlike brightness, 0 is a
        // legitimate setting.
        check("volume clamps below zero", 0, SystemToggle.clampVolume(-3, 15));
        check("volume clamps above max", 15, SystemToggle.clampVolume(99, 15));
        check("zero volume is allowed", 0, SystemToggle.clampVolume(0, 15));
        check("volume percent at half", 47, SystemToggle.volumePercent(7, 15));
        check("volume percent at max", 100, SystemToggle.volumePercent(15, 15));
        check("volume percent at zero", 0, SystemToggle.volumePercent(0, 15));
        // A stream reporting no range must not divide by zero.
        check("a zero-range stream reports zero percent", 0, SystemToggle.volumePercent(5, 0));
        check("a negative max clamps to zero", 0, SystemToggle.clampVolume(5, -1));

        // --- surfacing a CLI's refusal ------------------------------------------
        // The bug this exists for: stickfx refused with four clear lines and
        // the panel showed nothing, so a button that explains itself and a
        // dead button looked identical. Captured verbatim from the device
        // 2026-11-01.
        String stickRefusal =
            "! every stick LED colour is \"0 0 0\", so a charging trigger\n" +
            "! would drive an invisible LED.\n" +
            "!\n" +
            "! Either configure colours in Settings -> AYN -> RGB lighting\n" +
            "! (AynParts owns the colour channel), or pass a colour here:\n" +
            "!     stickfx charge --color 2BE0D8\n";
        check("the real refusal becomes one readable sentence",
              "Every stick LED colour is \"0 0 0\", so a charging trigger would drive an invisible LED.",
              CliMessage.summarize(stickRefusal));

        // The blank "!" line ends the summary: what follows is the long
        // how-to-fix detail, which belongs in the CLI's output, not a toast.
        check("the fix-it paragraph is left out", true,
              !CliMessage.summarize(stickRefusal).contains("Settings"));

        check("a plain one-line error is used as-is",
              "No second screen found", CliMessage.summarize("no second screen found"));
        check("the marker is stripped",
              "Refusing: the top screen is already off",
              CliMessage.summarize("! refusing: the top screen is already off"));

        // Null means "say something generic", never "say nothing".
        check("empty output has no message", null, CliMessage.summarize(""));
        check("null output has no message", null, CliMessage.summarize(null));
        check("whitespace only has no message", null, CliMessage.summarize("  \n \n"));
        check("bare markers have no message", null, CliMessage.summarize("!\n!\n"));

        // A toast cannot show a wall of text.
        StringBuilder longOut = new StringBuilder("! ");
        for (int i = 0; i < 80; i++) longOut.append("verylongwordhere ");
        String trimmed = CliMessage.summarize(longOut.toString());
        check("an overlong message is trimmed", true, trimmed.length() <= CliMessage.MAX + 1);
        check("and ends with an ellipsis rather than mid-word", true, trimmed.endsWith("\u2026"));
        check("and does not cut inside a word", true, !trimmed.contains("verylongwordher\u2026"));

        System.out.println();
        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }
}
