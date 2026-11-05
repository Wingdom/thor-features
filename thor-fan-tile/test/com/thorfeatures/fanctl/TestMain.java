package com.thorfeatures.fanctl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host-side unit tests. Plain Java, no JUnit, no Android, no device.
 * Run: java -cp out/test com.thorfeatures.fanctl.TestMain
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
    static final class FakeRunner implements FanctlClient.Runner {
        final List<String[]> calls = new ArrayList<>();
        int code; String out;
        FakeRunner(int code, String out) { this.code = code; this.out = out; }
        @Override public FanctlClient.Result run(String[] argv) {
            calls.add(argv);
            return new FanctlClient.Result(code, out);
        }
        String lastCommand() {
            String[] a = calls.get(calls.size() - 1);
            return a[a.length - 1];
        }
    }

    public static void main(String[] args) {
        System.out.println("FanMode");
        eq(FanMode.fromString("quiet"), FanMode.QUIET, "parses 'quiet'");
        eq(FanMode.fromString("balanced"), FanMode.BALANCED, "parses 'balanced'");
        eq(FanMode.fromString("performance"), FanMode.PERFORMANCE, "parses 'performance'");
        eq(FanMode.fromString("default"), FanMode.STOCK, "'default' from fanctl maps to STOCK");
        eq(FanMode.fromString("  balanced \n"), FanMode.BALANCED, "tolerates whitespace and newline");
        eq(FanMode.fromString("QUIET"), FanMode.QUIET, "is case-insensitive");
        eq(FanMode.fromString(null), FanMode.STOCK, "null falls back to STOCK");
        eq(FanMode.fromString(""), FanMode.STOCK, "empty falls back to STOCK");
        eq(FanMode.fromString("banana"), FanMode.STOCK, "unknown falls back to STOCK");

        // Falling back to STOCK matters: if we cannot tell, the tile must not
        // claim a tier is applied.
        check(!FanMode.STOCK.isActive(), "STOCK is not 'active'", "");
        check(FanMode.QUIET.isActive() && FanMode.BALANCED.isActive()
              && FanMode.PERFORMANCE.isActive(), "all three tiers are 'active'", "");

        System.out.println("\nFanMode.tiers (what the picker offers)");
        FanMode[] tiers = FanMode.tiers();
        eq(tiers.length, 3, "the picker offers exactly three tiers");
        eq(tiers[0], FanMode.QUIET, "order: quiet first");
        eq(tiers[1], FanMode.BALANCED, "order: balanced second");
        eq(tiers[2], FanMode.PERFORMANCE, "order: performance third");

        // STOCK must never be offered: selecting it would mean choosing the
        // loud 43 C usb-therm behaviour. Stock = disable the Magisk module.
        boolean stockOffered = false;
        for (FanMode t : tiers) if (t == FanMode.STOCK) stockOffered = true;
        check(!stockOffered, "STOCK is never offered as a tier", "it appeared in tiers()");

        eq(FanMode.QUIET.tierIndex(), 0, "tierIndex of QUIET");
        eq(FanMode.BALANCED.tierIndex(), 1, "tierIndex of BALANCED");
        eq(FanMode.PERFORMANCE.tierIndex(), 2, "tierIndex of PERFORMANCE");
        eq(FanMode.STOCK.tierIndex(), -1, "tierIndex of STOCK is -1 (nothing preselected)");

        // every tier must round-trip through its wire name
        boolean rt = true; String rtBad = "";
        for (FanMode t : tiers) {
            if (FanMode.fromString(t.wire()) != t) { rt = false; rtBad = t.name(); }
        }
        check(rt, "every tier round-trips through fromString(wire())", rtBad);

        System.out.println("\nFanctlClient.suArgv");
        String[] argv = FanctlClient.suArgv("quiet");
        eq(argv.length, 3, "argv has three elements");
        eq(argv[0], "su", "invokes su");
        eq(argv[1], "-c", "uses -c");
        eq(argv[2], "/system/bin/fanctl quiet", "passes the absolute binary path and arg");

        // SAFETY: the tile must never be able to inject shell syntax. Every
        // argument originates from the FanMode enum, so assert that directly.
        boolean clean = true; String offender = "";
        for (FanMode mode : FanMode.values()) {
            String w = mode.wire();
            if (!w.matches("[a-z]+")) { clean = false; offender = w; }
        }
        check(clean, "every mode wire name is plain lowercase letters", "offender: " + offender);

        boolean noMeta = true;
        for (FanMode mode : FanMode.values()) {
            String cmd = FanctlClient.suArgv(mode.wire())[2];
            if (cmd.matches(".*[;&|><$`\\\\\n].*")) { noMeta = false; offender = cmd; }
        }
        check(noMeta, "no built command contains a shell metacharacter", "offender: " + offender);

        System.out.println("\nFanctlClient against a fake runner");
        FakeRunner okQuiet = new FakeRunner(0, "quiet\n");
        eq(new FanctlClient(okQuiet).getMode(), FanMode.QUIET, "getMode reads the mode");
        eq(okQuiet.lastCommand(), "/system/bin/fanctl get", "getMode calls 'get'");

        FakeRunner failing = new FakeRunner(1, "");
        eq(new FanctlClient(failing).getMode(), FanMode.STOCK, "non-zero exit reports STOCK");
        check(!new FanctlClient(failing).available(), "available() is false when fanctl fails", "");

        FakeRunner missing = new FakeRunner(-1, "No such file or directory");
        eq(new FanctlClient(missing).getMode(), FanMode.STOCK, "missing binary reports STOCK");

        FakeRunner applyOk = new FakeRunner(0, "");
        check(new FanctlClient(applyOk).apply(FanMode.BALANCED),
              "apply returns true on clean exit", "");
        eq(applyOk.lastCommand(), "/system/bin/fanctl balanced", "apply passes the right mode");

        FakeRunner applyBad = new FakeRunner(3, "boom");
        check(!new FanctlClient(applyBad).apply(FanMode.QUIET),
              "apply returns false on non-zero exit", "");

        FakeRunner avail = new FakeRunner(0, "balanced\n");
        check(new FanctlClient(avail).available(), "available() is true on clean exit", "");

        // Garbage output with a clean exit must still degrade to DEFAULT rather
        // than throwing -- the tile is drawn from this value.
        FakeRunner junk = new FakeRunner(0, "<html>404</html>");
        eq(new FanctlClient(junk).getMode(), FanMode.STOCK, "garbage output degrades to STOCK");


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

        System.out.println("\nTileRender cached rendering -- the shade must not ask for root");
        // The defect this guards: onStartListening() used to call fanctl, so
        // the shade opening fired an su request -- one spawn and one logged
        // rejection per shade open, and the tile reported "no root". Confirmed
        // on device 2026-10-29. On Magisk 31 such a request can never prompt
        // (31.0 removed interactive su), so it bought nothing. Shade-open
        // rendering now comes from the cache, and null means "never observed
        // a mode under root".
        check(TileRender.cachedState(null) != TileRender.UNAVAILABLE,
                "a fresh install is never UNAVAILABLE",
                "got UNAVAILABLE -- that withholds onClick, which is now the only "
                + "place root is requested, so root would be unobtainable forever");
        eq(TileRender.cachedState(null), TileRender.INACTIVE, "no cache -> INACTIVE (clickable)");
        eq(TileRender.cachedState(FanMode.QUIET), TileRender.ACTIVE, "cached tier -> ACTIVE");
        eq(TileRender.cachedState(FanMode.BALANCED), TileRender.ACTIVE, "cached balanced -> ACTIVE");
        eq(TileRender.cachedState(FanMode.STOCK), TileRender.INACTIVE,
                "cached 'default' -> INACTIVE (no tier applied)");

        check(TileRender.needsSetup(null), "no cache -> invite a tap", "");
        check(!TileRender.needsSetup(FanMode.QUIET), "cached mode -> name the mode", "");
        check(!TileRender.needsSetup(FanMode.STOCK),
                "cached 'default' names itself rather than inviting setup",
                "'default' is a mode fanctl reported, not an absent cache");

        // Every tier must be renderable straight from the cache, or a mode
        // could be stored and then not be drawable.
        boolean allCacheable = true; String uncacheable = "";
        for (FanMode m : FanMode.values()) {
            if (TileRender.needsSetup(m)) { allCacheable = false; uncacheable = m.name(); }
            if (TileRender.cachedState(m) == TileRender.UNAVAILABLE) {
                allCacheable = false; uncacheable = m.name();
            }
        }
        check(allCacheable, "every mode renders from the cache without UNAVAILABLE", uncacheable);

        System.out.println("\nTileRender.cacheAfterProbe");
        eq(TileRender.cacheAfterProbe(true, FanMode.PERFORMANCE), FanMode.PERFORMANCE,
                "a granted probe caches what fanctl reported");
        eq(TileRender.cacheAfterProbe(true, FanMode.STOCK), FanMode.STOCK,
                "a granted probe caches 'default' too");
        check(TileRender.cacheAfterProbe(false, FanMode.QUIET) == null,
                "a refused probe forgets the cache",
                "keeping it would leave the tile claiming a tier after the module "
                + "was removed or Superuser access revoked");
        // And the forgotten cache must round-trip back to the invite, not to a
        // dimmed tile -- otherwise revoking access would brick the tile.
        eq(TileRender.cachedState(TileRender.cacheAfterProbe(false, FanMode.QUIET)),
                TileRender.INACTIVE,
                "after a refusal the tile is still clickable");
        System.out.println();
        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
