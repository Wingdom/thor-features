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

        // --- Recovery: the launcher icon --------------------------------
        // The way back when aynkeyd has died. With the daemon gone the AYN
        // button does nothing at all, including the long press that is
        // normally the way back from a dark bottom screen, so this is the
        // only route that does not need a host and adb.
        //
        // Exercised through a fake runner that records the commands and
        // replies to them, which is the same seam the rest of this file uses.

        // It must run the daemon's OWN action script, not a second
        // "open the panel" path of its own. A divergent copy of what a press
        // means is the mistake this project already regretted once, when the
        // panel grew its own copy of screenmove's rules.
        check("the open command is the action script, with the press kind",
              "/data/adb/thor-aynkey/action.sh short", Recovery.OPEN_COMMAND);
        check("the start command is idempotent aynkeyd start",
              "/system/bin/aynkeyd start", Recovery.START_COMMAND);

        // `aynkeyd start` reports which of the two things it did, and the
        // difference is the whole point: "the daemon had died" is a fact the
        // user wants, and claiming it falsely would be worse than silence.
        check("a fresh start is reported as a restart", true,
              Recovery.restarted("aynkeyd: started (pid 8032)"));
        check("an existing daemon is not", false,
              Recovery.restarted("aynkeyd: already running (pid 8032)"));
        check("unrecognised output does not claim a restart", false,
              Recovery.restarted("something else entirely"));
        check("null output does not claim a restart", false, Recovery.restarted(null));
        check("empty output does not claim a restart", false, Recovery.restarted(""));

        // The order matters and is not interchangeable: starting the daemon
        // first is what makes the tap work when the daemon is the thing that
        // is broken.
        final java.util.List<String> ran = new java.util.ArrayList<>();
        RootClient.Runner recorder = argv -> {
            String cmd = argv[argv.length - 1];
            ran.add(cmd);
            if (cmd.equals(Recovery.START_COMMAND)) {
                return new RootClient.Result(0, "aynkeyd: already running (pid 8032)");
            }
            return new RootClient.Result(0, "");
        };
        Recovery.Outcome outcome = Recovery.tap(new RootClient(recorder));
        check("a healthy daemon reports OPENED", Recovery.Outcome.OPENED, outcome);
        check("two commands run, daemon first",
              "[/system/bin/aynkeyd start, /data/adb/thor-aynkey/action.sh short]",
              ran.toString());

        // A dead daemon: restarted, then the panel opened. The user is told,
        // because a daemon that died once has died for a reason.
        ran.clear();
        RootClient.Runner deadThenUp = argv -> {
            String cmd = argv[argv.length - 1];
            ran.add(cmd);
            if (cmd.equals(Recovery.START_COMMAND)) {
                return new RootClient.Result(0, "aynkeyd: started (pid 9001)");
            }
            return new RootClient.Result(0, "");
        };
        check("a dead daemon is restarted and reported",
              Recovery.Outcome.RESTARTED_AND_OPENED,
              Recovery.tap(new RootClient(deadThenUp)));

        // No root, or no module: the first command cannot run. The action
        // script must NOT then be attempted - there is nothing to open the
        // panel with, and a second failing su call is a second Magisk prompt.
        ran.clear();
        RootClient.Runner noRoot = argv -> {
            ran.add(argv[argv.length - 1]);
            return new RootClient.Result(1, "su: permission denied");
        };
        check("a failed start reports DAEMON_FAILED", Recovery.Outcome.DAEMON_FAILED,
              Recovery.tap(new RootClient(noRoot)));
        check("and does not go on to run the action script",
              "[/system/bin/aynkeyd start]", ran.toString());

        // The daemon is fine but the action script is missing - the case where
        // thor-aynkey is installed and install.sh was never run.
        RootClient.Runner noAction = argv -> {
            String cmd = argv[argv.length - 1];
            if (cmd.equals(Recovery.START_COMMAND)) {
                return new RootClient.Result(0, "aynkeyd: already running (pid 8032)");
            }
            return new RootClient.Result(127, "sh: action.sh: not found");
        };
        check("a missing action script reports PANEL_FAILED", Recovery.Outcome.PANEL_FAILED,
              Recovery.tap(new RootClient(noAction)));

        // Which outcomes are worth a toast. A plain success is not: the panel
        // appearing on the bottom screen is its own feedback, and a toast on
        // the TOP screen announcing it would be noise. The other three are
        // indistinguishable from "nothing happened" without one.
        check("a plain success says nothing", false,
              Recovery.needsMessage(Recovery.Outcome.OPENED));
        check("a restart is announced", true,
              Recovery.needsMessage(Recovery.Outcome.RESTARTED_AND_OPENED));
        check("a daemon failure is announced", true,
              Recovery.needsMessage(Recovery.Outcome.DAEMON_FAILED));
        check("a panel failure is announced", true,
              Recovery.needsMessage(Recovery.Outcome.PANEL_FAILED));

        // Nothing user-supplied or device-read reaches the argv, same property
        // the rest of the panel's commands have.
        for (String cmd : new String[] { Recovery.START_COMMAND, Recovery.OPEN_COMMAND }) {
            check("no shell metacharacter in [" + cmd + "]", true,
                  !cmd.matches(".*[;&|`$<>(){}\\\\\"'\n].*"));
        }

        telemetryTests();
        fpsTests();

        System.out.println();
        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }

    /**
     * A discovery reply shaped like this device's.
     *
     * The values are the ones actually read off the Thor on 2026-11-03, not
     * invented: three cpufreq policies rather than eight cores, the fan at
     * cooling_device34, and hwmon0 exposing pwm1 and nothing else. The GPU
     * candidates are absent from this fixture because every one of them
     * answered "Permission denied" under SELinux despite uid 0 - which is the
     * case the gauge list has to survive, so it is the default fixture rather
     * than an edge case.
     */
    private static final String DISCOVERY =
        "/sys/class/thermal/thermal_zone0/type:aoss-0\n"
      + "/sys/class/thermal/thermal_zone1/type:cpu-0-0-usr\n"
      + "/sys/class/thermal/thermal_zone2/type:cpu-1-0-usr\n"
      + "/sys/class/thermal/thermal_zone3/type:gpuss-0-usr\n"
      + "/sys/class/thermal/thermal_zone4/type:quiet-therm\n"
      + "/sys/class/thermal/thermal_zone5/type:usb-therm\n"
      + "/sys/class/thermal/thermal_zone6/type:sys-therm-batt\n"
      + "/sys/class/thermal/cooling_device7/type:thermal-cpufreq-0\n"
      + "/sys/class/thermal/cooling_device34/type:pwm-fan\n"
      + "/sys/class/thermal/cooling_device40/type:thermal-devfreq-0\n"
      + "/sys/devices/system/cpu/cpufreq/policy0/affected_cpus:0 1 2\n"
      + "/sys/devices/system/cpu/cpufreq/policy3/affected_cpus:3 4 5 6\n"
      + "/sys/devices/system/cpu/cpufreq/policy7/affected_cpus:7\n"
      + "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq:2016000\n"
      + "/sys/devices/system/cpu/cpufreq/policy3/cpuinfo_max_freq:2803200\n"
      + "/sys/devices/system/cpu/cpufreq/policy7/cpuinfo_max_freq:3187200\n";

    private static void telemetryTests() {
        System.out.println();
        System.out.println("telemetry");

        // --- what may enter a command ---------------------------------------
        check("a sysfs path is safe", true,
              Telemetry.isSafePath("/sys/class/thermal/thermal_zone0/temp"));
        check("a path with a comma is safe (devfreq names have them)", true,
              Telemetry.isSafePath("/sys/class/devfreq/3d00000.qcom,kgsl-3d0/cur_freq"));
        // A colon would make the reply format ambiguous: the parser splits
        // path from value on the first colon, so a path containing one would
        // be truncated and its value silently misread.
        check("a path with a colon is refused", false,
              Telemetry.isSafePath("/sys/class/foo:bar/temp"));
        check("a path with a space is refused", false,
              Telemetry.isSafePath("/sys/class/foo bar/temp"));
        check("a path with a semicolon is refused", false,
              Telemetry.isSafePath("/sys/x;reboot/temp"));
        check("a path with a backtick is refused", false,
              Telemetry.isSafePath("/sys/x`id`/temp"));
        check("traversal is refused", false,
              Telemetry.isSafePath("/sys/../etc/passwd"));
        check("a path outside sys and proc is refused", false,
              Telemetry.isSafePath("/data/local/tmp/x"));
        check("null is refused", false, Telemetry.isSafePath(null));

        // --- grouping thermal zones -----------------------------------------
        check("cpu-0-0-usr is a CPU zone",   "CPU",     Telemetry.groupFor("cpu-0-0-usr"));
        check("cpuss-0 is a CPU zone",       "CPU",     Telemetry.groupFor("cpuss-0"));
        check("gpuss-0-usr is a GPU zone",   "GPU",     Telemetry.groupFor("gpuss-0-usr"));
        check("quiet-therm is the skin",     "Skin",    Telemetry.groupFor("quiet-therm"));
        check("usb-therm is its own gauge",  "USB",     Telemetry.groupFor("usb-therm"));
        check("sys-therm-batt is battery",   "Battery", Telemetry.groupFor("sys-therm-batt"));
        check("aoss-0 is not shown",         null,      Telemetry.groupFor("aoss-0"));
        check("null type is not shown",      null,      Telemetry.groupFor(null));

        // --- discovery -------------------------------------------------------
        // -H must be explicit. Without it, a glob matching exactly one file
        // makes grep omit the path prefix, and every line then parses as a
        // value belonging to nothing.
        check("discovery forces the filename prefix", true,
              Telemetry.discoverCommand().contains("grep -H "));
        check("discovery probes the GPU candidates", true,
              Telemetry.discoverCommand().contains(Telemetry.GPU_FREQ_CANDIDATES[0]));

        Telemetry.Layout l = Telemetry.parseDiscovery(DISCOVERY);
        check("three policies, not eight cores", 3, l.policies.size());
        check("policy0 covers the little cluster", "0 1 2", l.policies.get(0).cpus);
        check("policy7 reads its own current freq",
              "/sys/devices/system/cpu/cpufreq/policy7/scaling_cur_freq",
              l.policies.get(2).curPath);
        // The ceiling is the fastest cluster's, so the bar does not peg when
        // the prime core boosts past the others' maxima.
        check("the CPU ceiling is the prime core's", 3187200L, l.cpuMaxKhzCeiling);

        // The fan is found by its driver's name. It was cooling_device34 on
        // this boot and 35 on another, so an index would have been wrong once.
        check("the fan is resolved by type, not index",
              "/sys/class/thermal/cooling_device34/cur_state", l.fanStatePath);
        check("the fan is present", true, l.hasFan());
        // The other cooling devices must not be mistaken for the fan. One is
        // listed before it and one after, deliberately: an earlier version of
        // this fixture had only the earlier one, and because the parser
        // overwrites as it goes, the fan still came out right even with the
        // type check deleted. The test passed on broken code. The device after
        // the fan is what makes the assertion mean something.
        check("a cooling device before the fan is ignored", true,
              !l.fanStatePath.contains("cooling_device7"));
        check("a cooling device after the fan is ignored", true,
              !l.fanStatePath.contains("cooling_device40"));

        check("two CPU zones were grouped", 2, l.zones.get("CPU").size());
        check("the GPU zone was grouped", 1, l.zones.get("GPU").size());
        check("gauges are ordered, not zone-ordered", "[CPU, GPU, Skin, USB, Battery]",
              l.zones.keySet().toString());
        // Every GPU frequency candidate was denied on this build.
        check("no GPU frequency node was readable", false, l.hasGpuFreq());
        check("a layout with sensors is not empty", false, l.isEmpty());
        check("root refused means an empty layout", true,
              Telemetry.parseDiscovery("").isEmpty());

        // --- the tick command ------------------------------------------------
        String tickCmd = Telemetry.tickCommand(l);
        check("the tick reads policy frequencies", true,
              tickCmd.contains("/sys/devices/system/cpu/cpufreq/policy3/scaling_cur_freq"));
        check("the tick reads the fan duty", true,
              tickCmd.contains("/sys/class/hwmon/hwmon0/pwm1"));
        check("the tick reads battery current", true,
              tickCmd.contains("/sys/class/power_supply/battery/current_now"));
        check("the tick narrows meminfo rather than dumping it", true,
              tickCmd.contains("^(MemTotal|MemAvailable):"));
        // One exchange over the session, two forks. The session wraps the whole
        // string in one subshell, so a semicolon costs no extra round trip.
        check("meminfo shares one exchange", 1,
              tickCmd.split(";", -1).length - 1);
        check("the tick asks for no GPU node it cannot read", false,
              tickCmd.contains("kgsl"));

        // The same invariant the rest of the panel's commands have. Device-read
        // directory names reach this string, so it is the one place that could
        // lose it.
        check("no shell metacharacter in the tick command", true,
              !tickCmd.replace("2>/dev/null", "").replace("^(MemTotal|MemAvailable):", "")
                      .matches(".*[`$<>\\\\\"].*"));

        // A path discovery should never have produced must not reach a command.
        Telemetry.Layout poisoned = Telemetry.parseDiscovery(
              "/sys/class/thermal/thermal_zone9;id/type:cpu-9-0-usr\n");
        check("an unsafe discovered path is dropped", 0, poisoned.zones.size());

        // --- parsing a tick --------------------------------------------------
        String tickOut =
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq:2016000\n"
          + "/sys/devices/system/cpu/cpufreq/policy3/scaling_cur_freq:2707200\n"
          + "/sys/devices/system/cpu/cpufreq/policy7/scaling_cur_freq:864000\n"
          + "/sys/class/thermal/thermal_zone1/temp:42000\n"
          + "/sys/class/thermal/thermal_zone2/temp:47500\n"
          + "/sys/class/thermal/thermal_zone3/temp:39000\n"
          + "/sys/class/thermal/thermal_zone4/temp:35000\n"
          + "/sys/class/thermal/thermal_zone5/temp:41000\n"
          + "/sys/class/thermal/thermal_zone6/temp:33000\n"
          + "/sys/class/hwmon/hwmon0/pwm1:96\n"
          + "/sys/class/thermal/cooling_device34/cur_state:3\n"
          + "/sys/class/thermal/cooling_device34/max_state:8\n"
          + "/sys/class/power_supply/battery/current_now:-1250000\n"
          + "/sys/class/power_supply/battery/voltage_now:3912000\n"
          + "/sys/class/power_supply/battery/status:Discharging\n"
          + "/sys/class/power_supply/battery/capacity:74\n"
          + "/proc/meminfo:MemTotal:       16303292 kB\n"
          + "/proc/meminfo:MemAvailable:    9804112 kB\n";

        Telemetry.Readings r = Telemetry.parseTick(tickOut, l);
        check("three cluster frequencies", 3, r.cpuKhz.size());
        // The gauge shows the fastest cluster, which is what "CPU speed" means
        // when three clusters run at three different clocks.
        check("the CPU gauge takes the fastest cluster", 2707200L, r.cpuMaxKhz());
        // Of two CPU zones at 42.0 and 47.5, the hotter is the one that matters.
        check("a zone group reports its hottest", 47500L, (long) r.tempMilliC.get("CPU"));
        check("the skin zone was read", 35000L, (long) r.tempMilliC.get("Skin"));
        check("fan duty was read", 96L, r.fanPwm);
        check("fan state was read", 3L, r.fanState);
        check("battery status is a word, not a sign", "Discharging", r.batStatus);
        check("meminfo total survived its second colon", 16303292L, r.memTotalKb);
        check("meminfo available survived too", 9804112L, r.memAvailKb);
        check("an absent GPU stays absent", Telemetry.ABSENT, r.gpuHz);

        Telemetry.Readings empty = Telemetry.parseTick("", l);
        check("an empty reply yields no CPU reading", Telemetry.ABSENT, empty.cpuMaxKhz());
        check("an empty reply yields no fan reading", Telemetry.ABSENT, empty.fanPwm);
        check("an empty reply still lists the zone groups", 5, empty.tempMilliC.size());
        check("an empty reply's zone reads absent", Telemetry.ABSENT,
              (long) empty.tempMilliC.get("CPU"));

        // A line for a path the layout never asked for cannot land in a gauge.
        Telemetry.Readings spoofed = Telemetry.parseTick(
              "/sys/class/thermal/thermal_zone99/temp:99000\n", l);
        check("an unrequested path is ignored", Telemetry.ABSENT,
              (long) spoofed.tempMilliC.get("CPU"));

        // --- units ------------------------------------------------------------
        // Milli-degrees and whole degrees are indistinguishable from the number
        // alone, and getting it wrong is silent: 45 would render as 0 C, which
        // reads as a cold phone rather than as a bug.
        check("milli-degrees pass through",  45000L, Telemetry.normalizeTempMilliC(45000));
        check("whole degrees are scaled up", 45000L, Telemetry.normalizeTempMilliC(45));
        check("zero stays zero",                 0L, Telemetry.normalizeTempMilliC(0));
        check("1000 is already milli",        1000L, Telemetry.normalizeTempMilliC(1000));
        check("a negative whole degree scales", -5000L, Telemetry.normalizeTempMilliC(-5));

        check("busy percent drops its unit", 42L, Telemetry.parseBusyPercent("42 %"));
        check("busy percent without a space", 42L, Telemetry.parseBusyPercent("42%"));
        check("busy percent of nothing", Telemetry.ABSENT, Telemetry.parseBusyPercent(""));

        // --- formatting -------------------------------------------------------
        check("cpu freq in GHz",  "2.02 GHz", Telemetry.formatCpuFreq(2016000));
        check("cpu freq in MHz",  "864 MHz",  Telemetry.formatCpuFreq(864000));
        check("cpu freq absent",  Telemetry.NONE, Telemetry.formatCpuFreq(Telemetry.ABSENT));
        // devfreq reports Hz where cpufreq reports kHz - a thousand-fold
        // difference between two nodes that look alike.
        check("gpu freq is Hz, not kHz", "680 MHz", Telemetry.formatGpuFreq(680000000L));
        check("gpu freq absent", Telemetry.NONE, Telemetry.formatGpuFreq(Telemetry.ABSENT));
        check("temperature rounds to a degree", "48\u00b0C", Telemetry.formatTemp(47500));
        check("temperature absent", Telemetry.NONE, Telemetry.formatTemp(Telemetry.ABSENT));

        check("fan shows duty and state", "38%  3/8", Telemetry.formatFan(96, 3, 8));
        // "off" rather than "0%": a stopped fan is a state, and 0% reads like a
        // fan that is running at nothing.
        check("a stopped fan says off", "off  0/8", Telemetry.formatFan(0, 0, 8));
        check("fan absent", Telemetry.NONE,
              Telemetry.formatFan(Telemetry.ABSENT, Telemetry.ABSENT, Telemetry.ABSENT));

        // 1.25 A at 3.912 V = 4.89 W. The sign comes from status, never from
        // the sign of current_now, whose convention on this device is unverified.
        check("watts from current and voltage", "\u22124.89 W",
              Telemetry.formatWatts(-1250000L, 3912000L, "Discharging"));
        check("charging is positive regardless of the current's sign", "+4.89 W",
              Telemetry.formatWatts(-1250000L, 3912000L, "Charging"));
        check("a positive current discharging is still negative", "\u22124.89 W",
              Telemetry.formatWatts(1250000L, 3912000L, "Discharging"));
        check("an unknown status gets no sign", "4.89 W",
              Telemetry.formatWatts(1250000L, 3912000L, "Not charging"));
        check("watts absent", Telemetry.NONE,
              Telemetry.formatWatts(Telemetry.ABSENT, 3912000L, "Discharging"));

        check("ram used of total", "6.2 / 15.5 GiB", Telemetry.formatRam(16303292L, 9804112L));
        check("ram absent", Telemetry.NONE, Telemetry.formatRam(Telemetry.ABSENT, 1L));

        // --- bars --------------------------------------------------------------
        check("a bar at half", 50, Telemetry.percent(50, 100));
        check("a bar clamps high", 100, Telemetry.percent(150, 100));
        check("a bar with no ceiling has no fill", -1, Telemetry.percent(50, 0));
        check("a bar of nothing", -1, Telemetry.percent(Telemetry.ABSENT, 100));
        // 30 C empty, 90 C full. Scaled to a fixed window rather than a zone's
        // crit trip, several of which sit far above anything the chassis reaches.
        check("30 C reads empty",   0,  Telemetry.tempPercent(30000));
        check("60 C reads half",    50, Telemetry.tempPercent(60000));
        check("90 C reads full",    100, Telemetry.tempPercent(90000));
        check("25 C clamps to empty", 0, Telemetry.tempPercent(25000));
        check("no temperature, no bar", -1, Telemetry.tempPercent(Telemetry.ABSENT));

        // --- which gauges ------------------------------------------------------
        List<String> keys = Telemetry.gaugeKeys(l, false);
        check("no GPU row when its node is unreadable", false, keys.contains("gpu"));
        check("the gauges this device offers",
              "[cpu, fan, temp:CPU, temp:Skin, temp:USB, battery, ram]", keys.toString());
        check("no FPS row until phase 2 proves it", false, keys.contains("fps"));
        check("FPS leads when it works", "fps", Telemetry.gaugeKeys(l, true).get(0));
        check("the odd row goes left", 4, Telemetry.leftColumnCount(7));
        check("an even split is even", 4, Telemetry.leftColumnCount(8));
        check("one gauge is one column", 1, Telemetry.leftColumnCount(1));

        check("a temperature label", "CPU temp", Telemetry.gaugeLabel("temp:CPU"));
        check("usb-therm keeps its own name", "USB", Telemetry.gaugeLabel("temp:USB"));
        check("the battery row is called Power", "Power", Telemetry.gaugeLabel("battery"));

        check("a gauge renders its reading", "2.71 GHz", Telemetry.gaugeValue("cpu", r, null));
        check("a temperature gauge renders", "48\u00b0C",
              Telemetry.gaugeValue("temp:CPU", r, null));
        check("a gauge with no group reads absent", Telemetry.NONE,
              Telemetry.gaugeValue("temp:Nonexistent", r, null));
        // The CPU bar is scaled to the prime core's ceiling: 2707200/3187200.
        check("the CPU bar uses the discovered ceiling", 85,
              Telemetry.gaugePercent("cpu", r, null, l));
        check("the fan bar is duty out of 255", 38,
              Telemetry.gaugePercent("fan", r, null, l));
        check("the RAM bar is used out of total", 40,
              Telemetry.gaugePercent("ram", r, null, l));
        // No reachable maximum, so no bar rather than a bar against a guess.
        check("power gets no bar", -1, Telemetry.gaugePercent("battery", r, null, l));
        check("gpu gets no bar", -1, Telemetry.gaugePercent("gpu", r, null, l));

        // --- when to tick -------------------------------------------------------
        check("tick on the telemetry tab while resumed", true, Telemetry.shouldPoll(true, true));
        check("no tick on another tab", false, Telemetry.shouldPoll(false, true));
        check("no tick while paused", false, Telemetry.shouldPoll(true, false));
        check("no tick when neither", false, Telemetry.shouldPoll(false, false));
        // One second, chosen so the gauges cannot be reading their own heat.
        check("the tick rate is one second", 1000L, Telemetry.TICK_MS);
    }

    private static void fpsTests() {
        System.out.println();
        System.out.println("fps (phase 2 - parsers only, nothing device-verified)");

        // --- focus, per display ---------------------------------------------
        check("a package out of a focus line", "com.foo.game",
              Fps.parseFocusedPackage(
                  "mCurrentFocus=Window{a1b2c3 u0 com.foo.game/com.foo.game.MainActivity}"));
        check("a package out of mFocusedApp", "com.foo.game",
              Fps.parseFocusedPackage(
                  "mFocusedApp=ActivityRecord{d4 u0 com.foo.game/.MainActivity t42}"));
        check("no package in a line without one", null,
              Fps.parseFocusedPackage("mCurrentFocus=null"));

        // THE trap this file exists to avoid. The panel is itself focused, on
        // the bottom screen, while it is open - so the focused package without
        // a display is the panel, and a counter built on it would measure this
        // card's static contents and report a confident, wrong 60.
        String displays =
            "Display: mDisplayId=0\n"
          + "  mCurrentFocus=Window{a1 u0 com.foo.game/com.foo.game.MainActivity}\n"
          + "  mFocusedApp=ActivityRecord{a2 u0 com.foo.game/.MainActivity t9}\n"
          + "Display: mDisplayId=1\n"
          + "  mCurrentFocus=Window{b1 u0 com.thorfeatures.screenpanel/"
          + "com.thorfeatures.screenpanel.PanelActivity}\n";
        check("display 0 is the game", "com.foo.game",
              Fps.parseFocusedPackageOnDisplay(displays, 0));
        check("display 1 is the panel itself", "com.thorfeatures.screenpanel",
              Fps.parseFocusedPackageOnDisplay(displays, 1));
        check("a display with no section has no focus", null,
              Fps.parseFocusedPackageOnDisplay(displays, 2));
        // A focus line before any display header belongs to no display, and
        // guessing it means display 0 is how the panel gets picked up again.
        check("a focus line outside any section is ignored", null,
              Fps.parseFocusedPackageOnDisplay(
                  "mCurrentFocus=Window{z1 u0 com.thorfeatures.screenpanel/.PanelActivity}\n", 0));
        check("no dump, no focus", null, Fps.parseFocusedPackageOnDisplay(null, 0));

        // --- picking the layer ------------------------------------------------
        String list =
            "com.foo.game/com.foo.game.MainActivity#0\n"
          + "SurfaceView[com.foo.game/com.foo.game.MainActivity](BLAST)#0\n"
          + "Splash Screen com.foo.game#0\n"
          + "NavigationBar0#0\n";
        // The SurfaceView wins: that is what a game renders into, and the
        // activity's own layer above it would report the UI's rate instead.
        check("the SurfaceView layer is preferred",
              "SurfaceView[com.foo.game/com.foo.game.MainActivity](BLAST)#0",
              Fps.pickLayer(list, "com.foo.game"));
        check("the activity layer is the fallback",
              "com.foo.game/com.foo.game.MainActivity#0",
              Fps.pickLayer("com.foo.game/com.foo.game.MainActivity#0\n", "com.foo.game"));
        check("a splash screen is never the answer", null,
              Fps.pickLayer("Splash Screen com.foo.game#0\n", "com.foo.game"));
        check("an unlisted package has no layer", null,
              Fps.pickLayer(list, "com.other.app"));
        check("no list, no layer", null, Fps.pickLayer(null, "com.foo.game"));

        // --- quoting a layer name ---------------------------------------------
        // Layer names carry brackets, hashes and spaces, so this is the one
        // command in the panel that cannot be built from literals.
        check("a layer name is quoted whole",
              "dumpsys SurfaceFlinger --latency 'SurfaceView[com.foo/.A](BLAST)#0' 2>/dev/null",
              Fps.latencyCommand("SurfaceView[com.foo/.A](BLAST)#0"));
        check("an embedded quote cannot escape the quoting",
              "'it'\\''s'", Fps.shellQuote("it's"));
        check("a sane layer is safe", true, Fps.isSafeLayer("SurfaceView[com.foo/.A]#0"));
        // Quoting contains metacharacters but cannot contain a newline, which
        // would end the command and begin another.
        check("a newline in a layer name is refused", false,
              Fps.isSafeLayer("layer\nrm -rf /"));
        check("a control character is refused", false, Fps.isSafeLayer("layer\u0001"));
        check("an empty layer name is refused", false, Fps.isSafeLayer(""));
        check("an unsafe layer yields no command", null,
              Fps.latencyCommand("layer\nid"));

        // --- parsing the frame timestamps --------------------------------------
        // 16666667 ns is 60 Hz. Five present timestamps 16.667 ms apart bound
        // four intervals, so the rate is 4 / 0.0666668 s = 60 fps.
        String latency =
            "16666667\n"
          + "0 0 0\n".replace("0 0 0", "1000000000 1000000000 1000000000")
          + "1016666667 1016666667 1016666667\n"
          + "1033333334 1033333334 1033333334\n"
          + "1050000001 1050000001 1050000001\n"
          + "1066666668 1066666668 1066666668\n";
        Fps.Sample s = Fps.parseLatency(latency);
        check("the refresh rate comes off the first line", 60L, Math.round(s.refreshHz));
        check("five timestamps bound four intervals", 5, s.frames);
        // Dividing by the frame count instead of the interval count would give
        // 75 here - and the error grows as the window shortens, which is
        // exactly the case of a game that has just launched.
        check("the rate is intervals, not frames", 60L, Math.round(s.fps));
        check("the bar is scaled to the panel's own rate", 100, Fps.percent(s));
        check("a sample formats as whole frames", "60 fps", Fps.format(s));

        // A 30 fps game on a 60 Hz panel reads half full, not full.
        String half =
            "16666667\n"
          + "1000000000 1000000000 1000000000\n"
          + "1033333334 1033333334 1033333334\n"
          + "1066666668 1066666668 1066666668\n";
        Fps.Sample s30 = Fps.parseLatency(half);
        check("a 30 fps game reads 30", 30L, Math.round(s30.fps));
        check("and fills half the bar", 50, Fps.percent(s30));

        // INT64_MAX marks a frame with no timestamp yet. Left in, it would put
        // a time 292 years ahead into the window and compute ~0 fps from a
        // perfectly healthy game.
        String pending =
            "16666667\n"
          + "1000000000 1000000000 1000000000\n"
          + "1016666667 1016666667 1016666667\n"
          + "9223372036854775807 9223372036854775807 9223372036854775807\n";
        check("pending frames are dropped", 2, Fps.parseLatency(pending).frames);
        check("and the rate comes from the real ones", 60L,
              Math.round(Fps.parseLatency(pending).fps));

        check("zero timestamps are dropped", 0,
              Fps.parseLatency("16666667\n0 0 0\n0 0 0\n").frames);
        check("one frame is not a rate", Fps.ABSENT,
              (long) Fps.parseLatency("16666667\n1000000000 1000000000 1000000000\n").fps);
        check("an empty dump is no sample", Fps.ABSENT, (long) Fps.parseLatency("").fps);
        check("a null dump is no sample", Fps.ABSENT, (long) Fps.parseLatency(null).fps);
        check("no sample shows an em dash", Telemetry.NONE, Fps.format(Fps.NO_SAMPLE));
        check("no sample has no bar", -1, Fps.percent(Fps.NO_SAMPLE));

        // The middle column is the one that is a frame rate: "desired" is the
        // app's intent and "ready" is when its buffer was finished, and a game
        // that renders early and waits for vsync has a "ready" rate far above
        // anything a person sees.
        String columns =
            "16666667\n"
          + "500000000 1000000000 900000000\n"
          + "508333333 1016666667 905000000\n"
          + "516666666 1033333334 910000000\n";
        check("the rate comes from actual-present, not desired or ready", 60L,
              Math.round(Fps.parseLatency(columns).fps));

        // A dump with no period still gives a rate; only the bar loses its top.
        Fps.Sample noPeriod = Fps.parseLatency(
            "0\n1000000000 1000000000 1000000000\n1033333334 1033333334 1033333334\n");
        check("a missing period still yields a rate", 30L, Math.round(noPeriod.fps));
        check("and the bar falls back to 60", 50, Fps.percent(noPeriod));
    }
}
