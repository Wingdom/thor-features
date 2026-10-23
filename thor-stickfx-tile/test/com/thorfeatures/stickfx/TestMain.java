package com.thorfeatures.stickfx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host-side unit tests. Plain Java, no JUnit, no Android, no device.
 * Run: java -cp out/test com.thorfeatures.stickfx.TestMain
 */
public class TestMain {

    private static int pass = 0, fail = 0;

    private static void ok(String what) { pass++; System.out.println("  PASS " + what); }
    private static void bad(String what, String detail) {
        fail++; System.out.println("  FAIL " + what + "\n       " + detail);
    }
    private static void check(boolean cond, String what, String detail) {
        if (cond) ok(what); else bad(what, detail);
    }
    private static void eq(Object got, Object want, String what) {
        check(want.equals(got), what, "wanted <" + want + "> got <" + got + ">");
    }

    /** Fake runner: records what it was asked to run, replays a scripted result. */
    static final class FakeRunner implements StickfxClient.Runner {
        final List<String[]> calls = new ArrayList<>();
        int code; String out;
        FakeRunner(int code, String out) { this.code = code; this.out = out; }
        @Override public StickfxClient.Result run(String[] argv) {
            calls.add(argv);
            return new StickfxClient.Result(code, out);
        }
        String lastCommand() {
            String[] a = calls.get(calls.size() - 1);
            return a[a.length - 1];
        }
    }

    public static void main(String[] args) {
        System.out.println("StickMode");
        eq(StickMode.fromString("off"), StickMode.OFF, "parses 'off'");
        eq(StickMode.fromString("charge"), StickMode.CHARGE, "parses 'charge'");
        eq(StickMode.fromString("breathe"), StickMode.BREATHE, "parses 'breathe'");
        eq(StickMode.fromString("usb"), StickMode.USB, "parses 'usb'");
        eq(StickMode.fromString("  charge\n"), StickMode.CHARGE, "tolerates whitespace/newline");
        eq(StickMode.fromString("CHARGE"), StickMode.CHARGE, "tolerates case");
        eq(StickMode.fromString(null), StickMode.OFF, "null -> OFF");
        eq(StickMode.fromString(""), StickMode.OFF, "empty -> OFF");
        eq(StickMode.fromString("nonsense"), StickMode.OFF, "unknown -> OFF");

        check(!StickMode.OFF.isActive(), "OFF is not active", "OFF reported active");
        check(StickMode.CHARGE.isActive(), "CHARGE is active", "CHARGE reported inactive");
        check(StickMode.BREATHE.isActive(), "BREATHE is active", "BREATHE reported inactive");

        List<StickMode> choices = Arrays.asList(StickMode.choices());
        check(choices.contains(StickMode.OFF), "picker offers Off",
                "Off missing: " + choices);
        check(!choices.contains(StickMode.USB), "picker does not offer the USB test mode",
                "USB offered: " + choices);
        eq(choices.size(), 3, "picker offers exactly three modes");
        eq(StickMode.OFF.choiceIndex(), 0, "Off is first in the picker");
        eq(StickMode.USB.choiceIndex(), -1, "USB has no picker index");

        // Every wire value must be a bare word: these are concatenated into a
        // shell command, so anything else would be a quoting bug waiting to bite.
        for (StickMode m : StickMode.values()) {
            check(m.wire().matches("[a-z]+"), "wire '" + m.wire() + "' is a bare word",
                    "wire contains something other than [a-z]");
        }

        System.out.println("StickfxClient");
        String[] argv = StickfxClient.suArgv("get");
        eq(argv.length, 3, "suArgv is su -c <cmd>");
        eq(argv[0], "su", "suArgv runs su");
        eq(argv[1], "-c", "suArgv passes -c");
        eq(argv[2], "/system/bin/stickfx get", "suArgv builds the full command");

        FakeRunner r = new FakeRunner(0, "charge\n");
        StickfxClient c = new StickfxClient(r);
        eq(c.getMode(), StickMode.CHARGE, "getMode parses the CLI output");
        eq(r.lastCommand(), "/system/bin/stickfx get", "getMode calls `stickfx get`");
        check(c.available(), "available() true on exit 0", "reported unavailable");

        FakeRunner bad = new FakeRunner(1, "");
        StickfxClient cb = new StickfxClient(bad);
        eq(cb.getMode(), StickMode.OFF, "getMode falls back to OFF on failure");
        check(!cb.available(), "available() false on non-zero exit", "reported available");
        check(!cb.apply(StickMode.CHARGE), "apply() false on non-zero exit", "reported success");

        FakeRunner ap = new FakeRunner(0, "");
        StickfxClient ca = new StickfxClient(ap);
        check(ca.apply(StickMode.BREATHE), "apply() true on exit 0", "reported failure");
        eq(ap.lastCommand(), "/system/bin/stickfx breathe", "apply sends the wire name");

        // The RGB panel is AynParts', opened by root because it is exported=false.
        String[] rgb = StickfxClient.rgbPanelArgv();
        eq(rgb[0], "su", "rgb panel goes through su");
        eq(rgb[2], "am start -n org.lineageos.settings.ayn/.lights.RGBLightingSettingsActivity",
                "rgb panel targets AynParts' RGB activity");
        FakeRunner rp = new FakeRunner(0, "");
        check(new StickfxClient(rp).openRgbPanel(), "openRgbPanel true on exit 0", "reported failure");

        // Nothing user-supplied ever reaches the shell string.
        for (StickMode m : StickMode.values()) {
            String cmd = StickfxClient.suArgv(m.wire())[2];
            check(!cmd.matches(".*[;&|<>$`()\\\\\"'].*"),
                    "no shell metacharacter in command for " + m.wire(), "got: " + cmd);
        }


        System.out.println("TileRender");
        // The bug this exists to prevent: a dimmed, unclickable tile that can
        // never explain itself. Verified on device -- tapping an unavailable
        // tile produced no su request at all.
        check(TileRender.state(false, false) != TileRender.UNAVAILABLE,
                "no root is never UNAVAILABLE (must stay clickable)",
                "got UNAVAILABLE, which withholds onClick");
        check(TileRender.state(false, true) != TileRender.UNAVAILABLE,
                "no root is never UNAVAILABLE even if a mode was applied",
                "got UNAVAILABLE");
        eq(TileRender.state(false, false), TileRender.INACTIVE, "no root -> INACTIVE");
        eq(TileRender.state(true, true), TileRender.ACTIVE, "root + mode applied -> ACTIVE");
        eq(TileRender.state(true, false), TileRender.INACTIVE, "root + no mode -> INACTIVE");
        eq(TileRender.busy(), TileRender.UNAVAILABLE, "the transient applying state is UNAVAILABLE");
        System.out.println();
        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
