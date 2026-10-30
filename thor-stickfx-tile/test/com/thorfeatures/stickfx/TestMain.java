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

        System.out.println("\nTileRender cached rendering -- the shade must not ask for root");
        // The defect this guards: onStartListening() used to call stickfx, so
        // the shade opening fired an su request. Magisk's prompt cannot take
        // focus over the open shade, so it was rejected rather than prompted
        // and the tile reported "no root" having asked the user nothing.
        // Confirmed on device 2026-10-29 against this exact package (uid
        // 10182, four rejections logged). Shade-open rendering now comes from
        // the cache, and null means "never observed a mode under root".
        check(TileRender.cachedState(null) != TileRender.UNAVAILABLE,
                "a fresh install is never UNAVAILABLE",
                "got UNAVAILABLE -- that withholds onClick, which is now the only "
                + "place root is requested, so root would be unobtainable forever");
        eq(TileRender.cachedState(null), TileRender.INACTIVE, "no cache -> INACTIVE (clickable)");
        eq(TileRender.cachedState(StickMode.CHARGE), TileRender.ACTIVE, "cached charge -> ACTIVE");
        eq(TileRender.cachedState(StickMode.BREATHE), TileRender.ACTIVE, "cached breathe -> ACTIVE");
        eq(TileRender.cachedState(StickMode.OFF), TileRender.INACTIVE,
                "cached 'off' -> INACTIVE (no effect running)");

        check(TileRender.needsSetup(null), "no cache -> invite a tap", "");
        check(!TileRender.needsSetup(StickMode.BREATHE), "cached mode -> name the mode", "");
        check(!TileRender.needsSetup(StickMode.OFF),
                "cached 'off' names itself rather than inviting setup",
                "'off' is a mode stickfx reported, not an absent cache");

        // Every mode must be renderable straight from the cache, or a mode
        // could be stored and then not be drawable. USB included: it is not in
        // the picker but the CLI can report it.
        boolean allCacheable = true; String uncacheable = "";
        for (StickMode m : StickMode.values()) {
            if (TileRender.needsSetup(m)) { allCacheable = false; uncacheable = m.name(); }
            if (TileRender.cachedState(m) == TileRender.UNAVAILABLE) {
                allCacheable = false; uncacheable = m.name();
            }
        }
        check(allCacheable, "every mode renders from the cache without UNAVAILABLE", uncacheable);

        System.out.println("\nTileRender.cacheAfterProbe");
        eq(TileRender.cacheAfterProbe(true, StickMode.BREATHE), StickMode.BREATHE,
                "a granted probe caches what stickfx reported");
        eq(TileRender.cacheAfterProbe(true, StickMode.OFF), StickMode.OFF,
                "a granted probe caches 'off' too");
        check(TileRender.cacheAfterProbe(false, StickMode.CHARGE) == null,
                "a refused probe forgets the cache",
                "keeping it would leave the tile claiming an effect after the module "
                + "was removed or Superuser access revoked");
        // And the forgotten cache must round-trip back to the invite, not to a
        // dimmed tile -- otherwise revoking access would brick the tile.
        eq(TileRender.cachedState(TileRender.cacheAfterProbe(false, StickMode.CHARGE)),
                TileRender.INACTIVE,
                "after a refusal the tile is still clickable");
        System.out.println();
        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
