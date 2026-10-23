package com.thorfeatures.screenmove;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host unit tests. No device, no root, no Android framework.
 *
 * Only the pure classes are covered: ScreenStatus (parsing), TileRender
 * (state and label) and ScreenmoveClient's argv construction and fallbacks,
 * driven through a fake Runner. ScreenmoveTileService is deliberately a thin
 * shell over these so there is nothing left in it worth testing off-device.
 *
 * The status strings below are REAL output captured from the device on
 * 2026-10-22, not invented. If the CLI's wording changes, these fail -- which
 * is the point, because the tile parses that wording.
 */
public final class TestMain {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        statusParsing();
        toggleSource();
        tileState();
        tileLabels();
        clientArgv();
        clientFallbacks();

        System.out.println();
        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    // --- real captured CLI output ---------------------------------------------

    /** Both screens showing an app. */
    private static final String BOTH =
            "top screen    : display 0\n" +
            "bottom screen : display 1\n" +
            "focused       : display 1\n" +
            "movable on top    : 261\n" +
            "movable on bottom : 262\n";

    /** Only launchers on both screens. */
    private static final String IDLE =
            "top screen    : display 0\n" +
            "bottom screen : display 1\n" +
            "focused       : display 1\n" +
            "movable on top    : <none, launcher only>\n" +
            "movable on bottom : <none, launcher only>\n";

    /** App on top only, focus on top. */
    private static final String TOP_ONLY =
            "top screen    : display 0\n" +
            "bottom screen : display 1\n" +
            "focused       : display 0\n" +
            "movable on top    : 262\n" +
            "movable on bottom : <none, launcher only>\n";

    /** The CLI's own degraded output when no second display is found. */
    private static final String NO_SECOND =
            "top screen    : display 0\n" +
            "bottom screen : <not found>\n" +
            "focused       : display 0\n" +
            "movable on top    : <none, launcher only>\n";

    // --- tests ----------------------------------------------------------------

    private static void statusParsing() {
        System.out.println("ScreenStatus.parse");

        ScreenStatus b = ScreenStatus.parse(BOTH);
        eq("both: top display",     0,   b.topDisplay);
        eq("both: bottom display",  1,   b.bottomDisplay);
        eq("both: focused",         1,   b.focusedDisplay);
        eq("both: movable top",     261, b.movableOnTop);
        eq("both: movable bottom",  262, b.movableOnBottom);
        yes("both: displays known",  b.displaysKnown());
        yes("both: something movable", b.anythingMovable());

        ScreenStatus i = ScreenStatus.parse(IDLE);
        eq("idle: movable top    is NONE", ScreenStatus.NONE, i.movableOnTop);
        eq("idle: movable bottom is NONE", ScreenStatus.NONE, i.movableOnBottom);
        yes("idle: displays still known", i.displaysKnown());
        no("idle: nothing movable", i.anythingMovable());

        ScreenStatus n = ScreenStatus.parse(NO_SECOND);
        eq("no-second: bottom is NONE", ScreenStatus.NONE, n.bottomDisplay);
        no("no-second: displays not known", n.displaysKnown());

        // Robustness: the tile must never crash on bad input, because the CLI
        // may be absent, truncated by a killed su, or changed by a later build.
        no("null input -> not known",     ScreenStatus.parse(null).displaysKnown());
        no("empty input -> not known",    ScreenStatus.parse("").displaysKnown());
        no("garbage -> not known",        ScreenStatus.parse("su: not found").displaysKnown());
        no("truncated -> not known",      ScreenStatus.parse("top screen    : display 0\n").displaysKnown());
        no("non-numeric id -> not known", ScreenStatus.parse(
                "top screen    : display x\nbottom screen : display y\n").displaysKnown());

        // Leading/trailing whitespace and CRLF should not defeat parsing.
        eq("tolerates extra spaces", 1,
                ScreenStatus.parse("  bottom screen   :   display 1  \n").bottomDisplay);
    }

    private static void toggleSource() {
        System.out.println("ScreenStatus.sourceDisplayForToggle");

        // Focus wins when the focused screen has something to move.
        eq("focused bottom with app -> bottom", 1,
                ScreenStatus.parse(BOTH).sourceDisplayForToggle());

        // Focused screen is empty -> fall back to the other screen, so one
        // tile still works in both directions.
        String focusBottomAppOnTop =
                "top screen    : display 0\n" +
                "bottom screen : display 1\n" +
                "focused       : display 1\n" +
                "movable on top    : 300\n" +
                "movable on bottom : <none, launcher only>\n";
        eq("focused empty -> other screen", 0,
                ScreenStatus.parse(focusBottomAppOnTop).sourceDisplayForToggle());

        eq("focused top with app -> top", 0,
                ScreenStatus.parse(TOP_ONLY).sourceDisplayForToggle());

        eq("nothing movable -> NONE", ScreenStatus.NONE,
                ScreenStatus.parse(IDLE).sourceDisplayForToggle());
    }

    private static void tileState() {
        System.out.println("TileRender.state");

        // THE rule, learned on device: when the tile cannot act it must stay
        // clickable, or SystemUI withholds onClick() and the explanation
        // dialog becomes unreachable.
        eq("no root -> INACTIVE (clickable)", TileRender.INACTIVE,
                TileRender.state(false, ScreenStatus.parse(BOTH)));
        eq("null status -> INACTIVE", TileRender.INACTIVE,
                TileRender.state(true, null));
        eq("no second display -> INACTIVE", TileRender.INACTIVE,
                TileRender.state(true, ScreenStatus.parse(NO_SECOND)));
        eq("nothing to move -> INACTIVE", TileRender.INACTIVE,
                TileRender.state(true, ScreenStatus.parse(IDLE)));

        eq("app present -> ACTIVE", TileRender.ACTIVE,
                TileRender.state(true, ScreenStatus.parse(BOTH)));

        // UNAVAILABLE is reserved for the transient in-flight state.
        eq("busy -> UNAVAILABLE", TileRender.UNAVAILABLE, TileRender.busy());

        no("state() never returns UNAVAILABLE for a resting tile",
                TileRender.state(false, ScreenStatus.parse(IDLE)) == TileRender.UNAVAILABLE
             || TileRender.state(true,  ScreenStatus.parse(IDLE)) == TileRender.UNAVAILABLE
             || TileRender.state(true,  ScreenStatus.parse(BOTH)) == TileRender.UNAVAILABLE);
    }

    private static void tileLabels() {
        System.out.println("TileRender.label");

        eqs("no root", "Root required", TileRender.label(false, ScreenStatus.parse(BOTH)));
        eqs("null status", "Unavailable", TileRender.label(true, null));
        eqs("no second display", "No second screen",
                TileRender.label(true, ScreenStatus.parse(NO_SECOND)));
        eqs("idle", "Nothing to move", TileRender.label(true, ScreenStatus.parse(IDLE)));

        // The label names the action the tap will take, in the right direction.
        eqs("app on focused bottom -> send up", "Send to top",
                TileRender.label(true, ScreenStatus.parse(BOTH)));
        eqs("app on focused top -> send down", "Send to bottom",
                TileRender.label(true, ScreenStatus.parse(TOP_ONLY)));
    }

    private static void clientArgv() {
        System.out.println("ScreenmoveClient.suArgv");

        String[] a = ScreenmoveClient.suArgv("toggle");
        eq("argv length", 3, a.length);
        eqs("argv[0]", "su", a[0]);
        eqs("argv[1]", "-c", a[1]);
        eqs("argv[2]", "/system/bin/screenmove toggle", a[2]);
        yes("uses an absolute path, not PATH lookup",
                a[2].startsWith("/system/bin/"));

        // Arguments are fixed literals chosen in code, never user input or
        // anything read back off the device. Assert that stays true: a shell
        // metacharacter reaching argv[2] would be a command-injection bug.
        for (String arg : new String[] { "status", "toggle", "to-top", "to-bottom" }) {
            String cmd = ScreenmoveClient.suArgv(arg)[2];
            no("no shell metacharacters in: " + arg,
                    cmd.matches(".*[;&|$`><\\\\\n].*"));
        }
    }

    private static void clientFallbacks() {
        System.out.println("ScreenmoveClient behaviour");

        // Non-zero exit (module missing, root denied) must degrade to unknown
        // rather than throwing or reporting a half-parsed status.
        ScreenmoveClient denied = new ScreenmoveClient(new FakeRunner(1, ""));
        no("denied: not available", denied.available());
        no("denied: status unknown", denied.status().displaysKnown());

        ScreenmoveClient ok = new ScreenmoveClient(new FakeRunner(0, BOTH));
        yes("ok: available", ok.available());
        eq("ok: parses bottom display", 1, ok.status().bottomDisplay);

        // toggle() reports the exit code faithfully.
        yes("toggle success", new ScreenmoveClient(new FakeRunner(0, "moved task #262 to display 0")).toggle());
        no("toggle failure",  new ScreenmoveClient(new FakeRunner(1, "nothing to move")).toggle());

        // The client must call the CLI with the expected verbs.
        RecordingRunner rec = new RecordingRunner();
        ScreenmoveClient c = new ScreenmoveClient(rec);
        c.status(); c.toggle();
        eqs("called status then toggle",
                "[/system/bin/screenmove status, /system/bin/screenmove toggle]",
                rec.calls.toString());
    }

    // --- fakes ----------------------------------------------------------------

    private static final class FakeRunner implements ScreenmoveClient.Runner {
        private final int code; private final String out;
        FakeRunner(int code, String out) { this.code = code; this.out = out; }
        @Override public ScreenmoveClient.Result run(String[] argv) {
            return new ScreenmoveClient.Result(code, out);
        }
    }

    private static final class RecordingRunner implements ScreenmoveClient.Runner {
        final List<String> calls = new ArrayList<>();
        @Override public ScreenmoveClient.Result run(String[] argv) {
            calls.add(argv[argv.length - 1]);
            return new ScreenmoveClient.Result(0, BOTH);
        }
    }

    // --- assertions -----------------------------------------------------------

    private static void eq(String label, int expected, int actual) {
        if (expected == actual) ok(label);
        else bad(label + " (expected " + expected + ", got " + actual + ")");
    }

    private static void eqs(String label, String expected, String actual) {
        if (expected.equals(actual)) ok(label);
        else bad(label + " (expected [" + expected + "], got [" + actual + "])");
    }

    private static void yes(String label, boolean v) { if (v) ok(label); else bad(label); }
    private static void no(String label, boolean v)  { if (!v) ok(label); else bad(label); }

    private static void ok(String label)  { pass++; System.out.println("  ok    " + label); }
    private static void bad(String label) { fail++; System.out.println("  FAIL  " + label); }
}
