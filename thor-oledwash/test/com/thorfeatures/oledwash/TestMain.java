package com.thorfeatures.oledwash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host tests for thor-oledwash. No device, no Android, no root.
 *
 * <p>The geometry is the part worth guarding. A band that is off by a few
 * pixels, or a sweep that stops one row short of the bottom edge, looks
 * completely correct on a screen and quietly fails to do the one thing the
 * component exists for. So the central assertion here is a coverage property:
 * over one sweep, <b>every</b> row of the panel spends time under the band.
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

    private static void ok(String label, boolean cond) { check(label, true, cond); }

    private static void throwsIAE(String label, Runnable r) {
        try {
            r.run();
            check(label, "IllegalArgumentException", "no exception");
        } catch (IllegalArgumentException expected) {
            pass++;
            System.out.println("  ok    " + label);
        }
    }

    public static void main(String[] args) {
        System.out.println("thor-oledwash logic tests");

        planGeometry();
        planCoverage();
        planTiming();
        noise();
        params();
        summary();
        session();
        displayChoice();

        System.out.println();
        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }

    // --------------------------------------------------------------- plan ---

    private static void planGeometry() {
        System.out.println("\n plan geometry");

        WashPlan p = WashPlan.of(1080, 0.33, 2500L, 4);
        check("band height at 33% of 1080", 356, p.bandHeight);
        check("travel is panel + band", 1436, p.travel());

        // The second panel is a different width but the same height, so the
        // same fraction must give the same band. That is the whole reason the
        // plan takes a height rather than a device name.
        check("same height, same band", 356, WashPlan.of(1080, 0.33, 1000L, 1).bandHeight);

        // A panel this project has never seen.
        check("720p panel scales", 238, WashPlan.of(720, 0.33, 2500L, 4).bandHeight);

        check("full-height band allowed", 400, WashPlan.of(400, 1.0, 1000L, 1).bandHeight);
        check("tiny fraction clamps up to the floor",
                WashPlan.MIN_BAND_PX, WashPlan.of(1000, 0.001, 1000L, 1).bandHeight);
        check("floor cannot exceed the panel", 4, WashPlan.of(4, 0.1, 1000L, 1).bandHeight);

        throwsIAE("zero panel height rejected", () -> WashPlan.of(0, 0.33, 1000L, 1));
        throwsIAE("zero cycles rejected",       () -> WashPlan.of(1080, 0.33, 1000L, 0));
        throwsIAE("zero cycleMs rejected",      () -> WashPlan.of(1080, 0.33, 0L, 1));
        throwsIAE("fraction 0 rejected",        () -> WashPlan.of(1080, 0.0, 1000L, 1));
        throwsIAE("fraction > 1 rejected",      () -> WashPlan.of(1080, 1.5, 1000L, 1));
    }

    private static void planCoverage() {
        System.out.println("\n plan coverage -- the property the component exists for");

        WashPlan p = WashPlan.of(1080, 0.33, 2500L, 1);

        check("starts fully above the panel", -p.bandHeight, p.bandTopAt(0));
        check("ends fully below the panel", p.panelHeight, p.bandTopAt(p.totalMs()));
        ok("top edge is off-screen at the start", p.bandTopAt(0) + p.bandHeight <= 0);

        // Every row must be under the band at some sampled moment. Sampled at
        // 1 ms, far finer than the band moves in a frame.
        boolean[] seen = new boolean[p.panelHeight];
        for (long t = 0; t <= p.totalMs(); t++) {
            int top = p.bandTopAt(t);
            int lo = Math.max(0, top);
            int hi = Math.min(p.panelHeight, top + p.bandHeight);
            for (int y = lo; y < hi; y++) seen[y] = true;
        }
        int missed = 0;
        for (boolean b : seen) if (!b) missed++;
        check("every row is covered during one sweep", 0, missed);

        // And the same for a fast sweep, where a frame moves the band a long way.
        WashPlan fast = WashPlan.of(1080, 0.20, 1000L, 1);
        boolean[] seen2 = new boolean[fast.panelHeight];
        for (long t = 0; t <= fast.totalMs(); t++) {
            int top = fast.bandTopAt(t);
            int lo = Math.max(0, top);
            int hi = Math.min(fast.panelHeight, top + fast.bandHeight);
            for (int y = lo; y < hi; y++) seen2[y] = true;
        }
        int missed2 = 0;
        for (boolean b : seen2) if (!b) missed2++;
        check("every row covered at the fastest sweep too", 0, missed2);
    }

    private static void planTiming() {
        System.out.println("\n plan timing");

        WashPlan p = WashPlan.of(1080, 0.33, 2500L, 4);

        check("total is cycles x cycle", 10000L, p.totalMs());
        check("cycle 0 at t=0", 0, p.cycleAt(0));
        check("cycle 0 just before the boundary", 0, p.cycleAt(2499));
        check("cycle 1 at the boundary", 1, p.cycleAt(2500));
        check("cycle 3 near the end", 3, p.cycleAt(9999));
        check("cycle index clamps at the end", 3, p.cycleAt(10000));
        check("cycle index clamps past the end", 3, p.cycleAt(999999));
        check("negative time clamps to cycle 0", 0, p.cycleAt(-5));

        // A new cycle restarts the band above the panel rather than continuing.
        check("band restarts each cycle", -p.bandHeight, p.bandTopAt(2500));
        check("halfway through a cycle", -p.bandHeight + p.travel() / 2, p.bandTopAt(1250));
        check("negative time clamps to the start", -p.bandHeight, p.bandTopAt(-100));

        ok("not finished at the last millisecond", !p.finishedAt(9999));
        ok("finished exactly at total", p.finishedAt(10000));

        // Each row is lit for band/(panel+band) of a cycle -- NOT band/panel.
        // The lead-in and lead-out are what make coverage uniform, and they
        // cost dwell. 356/1436 = 0.2479.
        check("lit fraction accounts for the lead-in",
                "0.2479", String.format(java.util.Locale.US, "%.4f", p.litFractionPerCycle()));
        check("lit ms per row over the whole wash", 2479L, p.litMsPerRow());
        check("sweep speed", "574", String.format(java.util.Locale.US, "%.0f", p.pixelsPerSecond()));

        // Speed does not change the dose. Same band, same number of sweeps,
        // different sweep time -> the per-row lit time scales with total time
        // only, which is the arithmetic the finding rests on.
        WashPlan slow = WashPlan.of(1080, 0.33, 5000L, 4);
        check("doubling the sweep time doubles total lit time",
                p.litMsPerRow() * 2, slow.litMsPerRow());
        check("and leaves the per-cycle fraction untouched",
                String.format(java.util.Locale.US, "%.6f", p.litFractionPerCycle()),
                String.format(java.util.Locale.US, "%.6f", slow.litFractionPerCycle()));
    }

    // -------------------------------------------------------------- noise ---

    private static void noise() {
        System.out.println("\n noise");

        Noise a = new Noise(12345L);
        Noise b = new Noise(12345L);
        check("same seed, same stream", a.next(), b.next());

        // A zero seed is a fixed point for xorshift: it would produce a black
        // band, which is the exact failure this class must not have.
        Noise z = new Noise(0L);
        long z1 = z.next(), z2 = z.next();
        ok("zero seed still produces a varying stream", z1 != z2 && z1 != 0L);

        Noise n = new Noise(99L);
        boolean allOpaque = true, inRange = true;
        int floor = 160;
        int first = n.nextPixel(floor);
        boolean varies = false;
        for (int i = 0; i < 5000; i++) {
            int px = n.nextPixel(floor);
            if ((px >>> 24) != 0xFF) allOpaque = false;
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, bl = px & 0xFF;
            if (r < floor || g < floor || bl < floor) inRange = false;
            if (px != first) varies = true;
        }
        ok("every pixel is opaque", allOpaque);
        ok("no channel falls below the floor", inRange);
        ok("pixels vary", varies);

        int white = new Noise(7L).nextPixel(255);
        check("floor 255 is solid white", 0xFFFFFFFF, white);

        throwsIAE("floor above 255 rejected", () -> new Noise(1L).nextPixel(256));
        throwsIAE("negative floor rejected",  () -> new Noise(1L).nextPixel(-1));

        int[] buf = new int[16];
        new Noise(3L).fill(buf, 16, 200);
        boolean filled = true;
        for (int v : buf) if (v == 0) filled = false;
        ok("fill writes every entry", filled);
        throwsIAE("fill past the buffer rejected", () -> new Noise(1L).fill(new int[4], 5, 0));
        throwsIAE("negative count rejected",       () -> new Noise(1L).fill(new int[4], -1, 0));
    }

    // ------------------------------------------------------------- params ---

    private static void params() {
        System.out.println("\n params");

        // A default that is not one of the offered values would leave a picker
        // row with nothing highlighted, which looks like a bug and is one.
        ok("default sweep time is an offered value",
                Params.indexOf(Params.CYCLE_MS, Params.DEFAULT_CYCLE_MS) >= 0);
        ok("default band is an offered value",
                Params.indexOf(Params.BAND, Params.DEFAULT_BAND) >= 0);
        ok("default cycle count is an offered value",
                Params.indexOf(Params.CYCLES, Params.DEFAULT_CYCLES) >= 0);
        ok("default grain is an offered value",
                Params.indexOf(Params.GRAIN, Params.DEFAULT_GRAIN) >= 0);

        check("unknown value has no index", -1, Params.indexOf(Params.CYCLES, 99));
        check("nearest snaps upward", 2500L, Params.nearest(Params.CYCLE_MS, 2400L));
        check("nearest snaps downward", 1500L, Params.nearest(Params.CYCLE_MS, 1600L));
        check("nearest band", 0.33, Params.nearest(Params.BAND, 0.30));

        ok("noise floor is a sane level", Params.NOISE_FLOOR > 0 && Params.NOISE_FLOOR < 255);
        ok("grain options all divide sensibly", Params.GRAIN[0] == 1);
    }

    // ------------------------------------------------------------ summary ---

    private static void summary() {
        System.out.println("\n summary");

        WashPlan p = WashPlan.of(1080, 0.33, 2500L, 4);
        String s = Summary.describe(p, 2);
        ok("describes the band", s.contains("band 356 px of 1080"));
        ok("describes the speed", s.contains("574 px/s"));
        ok("describes the duration", s.contains("10.0 s total"));
        ok("describes the dose", s.contains("each row lit 2.5 s"));
        ok("describes the grain", s.contains("grain 2"));

        check("progress at the start", "sweep 1 of 4", Summary.progress(p, 0));
        check("progress mid-wash", "sweep 3 of 4", Summary.progress(p, 5200));
        check("progress clamps at the end", "sweep 4 of 4", Summary.progress(p, 99999));

        check("no frames yet reports zero", "0", String.format("%.0f", Summary.fps(0L)));
        check("negative interval reports zero", "0", String.format("%.0f", Summary.fps(-1L)));
        check("8.33 ms is 120 fps", "120", String.format("%.0f", Summary.fps(8_333_333L)));
        check("16.67 ms is 60 fps", "60", String.format("%.0f", Summary.fps(16_666_666L)));
    }

    // ------------------------------------------------------------ session ---

    private static final class Spy implements WashSession.Listener {
        String got = null;
        int calls = 0;
        boolean unregisterWhenCalled = false;
        @Override public void onWashStopped(String reason) {
            got = reason;
            calls++;
            if (unregisterWhenCalled) WashSession.unregister(this);
        }
    }

    private static void session() {
        System.out.println("\n session");

        WashSession.resetForTests();
        Spy a = new Spy(), b = new Spy();
        WashSession.register(a);
        WashSession.register(b);
        check("both listeners registered", 2, WashSession.listenerCount());
        WashSession.register(a);
        check("registering twice does not duplicate", 2, WashSession.listenerCount());

        // Stopping a wash that never started must not notify anyone.
        WashSession.stop(WashSession.REASON_INPUT);
        check("stop before start notifies nobody", 0, a.calls);

        WashSession.start();
        ok("running after start", WashSession.isRunning());
        check("no reason while running", null, WashSession.reason());

        WashSession.stop(WashSession.REASON_INPUT);
        check("every panel is told", 1, a.calls);
        check("the other panel too", 1, b.calls);
        check("reason recorded", WashSession.REASON_INPUT, WashSession.reason());
        ok("no longer running", !WashSession.isRunning());

        // The first stop wins: whatever actually interrupted the wash is what
        // gets recorded, not whichever panel finished unwinding last.
        WashSession.stop(WashSession.REASON_COMPLETE);
        check("second stop does not re-notify", 1, a.calls);
        check("second stop does not rewrite the reason",
                WashSession.REASON_INPUT, WashSession.reason());

        // A listener that unregisters itself from inside the callback is the
        // normal case here, since each Activity does exactly that on finish.
        WashSession.resetForTests();
        Spy c = new Spy();
        c.unregisterWhenCalled = true;
        WashSession.register(c);
        WashSession.start();
        WashSession.stop(WashSession.REASON_COMPLETE);
        check("self-unregistering listener is fine", 1, c.calls);
        check("and is gone afterwards", 0, WashSession.listenerCount());

        WashSession.resetForTests();
        check("reset clears listeners", 0, WashSession.listenerCount());
        ok("reset clears running", !WashSession.isRunning());

        regressionNoSession();
    }

    /**
     * The 2026-11-23 defect, pinned.
     *
     * <p>A WashActivity launched by anything other than MainActivity -- which
     * is exactly what the idle trigger's root {@code am start} does -- had no
     * running session. {@code stop} was therefore a no-op, and because
     * finishing was conditional on it, the wash could neither end by itself nor
     * be dismissed: touch did nothing, and BACK was swallowed by a handler that
     * returned true while taking no action. A black fullscreen Activity eating
     * every input, recoverable only by force-stop.
     *
     * <p>Two things fix it and both are asserted here. {@code joinOrStart} means
     * a panel forms its own session, and {@code stop} now reports whether it
     * did anything, so a caller cannot silently make its own exit conditional
     * on somebody else having started the wash.
     */
    private static void regressionNoSession() {
        System.out.println("\n regression -- a wash launched with no session");

        WashSession.resetForTests();

        // This is the state a root `am start` lands in: nothing has started.
        ok("nothing running to begin with", !WashSession.isRunning());
        check("stop on a dead session reports that it did nothing",
                false, WashSession.stop(WashSession.REASON_INPUT));

        // A panel joining forms the session itself.
        check("first panel begins the session", true, WashSession.joinOrStart());
        ok("now running", WashSession.isRunning());

        // The second panel joins the same one rather than restarting it.
        check("second panel joins rather than restarts", false, WashSession.joinOrStart());
        ok("still running", WashSession.isRunning());

        Spy s = new Spy();
        WashSession.register(s);
        check("stop now reports that it ended a wash",
                true, WashSession.stop(WashSession.REASON_COMPLETE));
        check("and the panel was told", 1, s.calls);

        // And it is startable again afterwards, which is what makes a repeating
        // idle trigger possible at all.
        check("a later wash can begin again", true, WashSession.joinOrStart());
        check("reason cleared on the new session", null, WashSession.reason());

        WashSession.resetForTests();
    }

    // ----------------------------------------------------- display choice ---

    private static PanelRef on(int id, String name, boolean builtIn) {
        return new PanelRef(id, name, builtIn, PanelRef.STATE_ON, true);
    }

    private static void displayChoice() {
        System.out.println("\n display choice");

        List<PanelRef> thor = Arrays.asList(
                on(0, "Built-in Screen", true),
                on(1, "Built-in Screen", true));

        check("both panels by default", "[0, 1]", DisplayChoice.pick(thor, Params.TARGET_ALL).toString());
        check("primary only", "[0]", DisplayChoice.pick(thor, Params.TARGET_PRIMARY).toString());
        check("secondary only", "[1]", DisplayChoice.pick(thor, Params.TARGET_SECONDARY).toString());

        // An HDMI television must never be washed.
        List<PanelRef> docked = Arrays.asList(
                on(0, "Built-in Screen", true),
                on(1, "Built-in Screen", true),
                on(7, "HDMI Screen", false));
        check("external display excluded", "[0, 1]",
                DisplayChoice.pick(docked, Params.TARGET_ALL).toString());

        // A panel already off needs no protection, and starting an Activity on
        // it would light it -- which the design forbids.
        List<PanelRef> oneDark = Arrays.asList(
                on(0, "Built-in Screen", true),
                new PanelRef(1, "Built-in Screen", true, 1 /* STATE_OFF */, true));
        check("dark panel excluded", "[0]",
                DisplayChoice.pick(oneDark, Params.TARGET_ALL).toString());
        check("and secondary-only then picks nothing", "[]",
                DisplayChoice.pick(oneDark, Params.TARGET_SECONDARY).toString());

        List<PanelRef> invalid = Arrays.asList(
                new PanelRef(0, "Built-in Screen", true, PanelRef.STATE_ON, false));
        check("invalid display excluded", "[]",
                DisplayChoice.pick(invalid, Params.TARGET_ALL).toString());

        // Ordering is by id, not by the order the platform happened to list them.
        List<PanelRef> shuffled = Arrays.asList(
                on(5, "Built-in Screen", true),
                on(0, "Built-in Screen", true),
                on(2, "Built-in Screen", true));
        check("ordered by id", "[0, 2, 5]",
                DisplayChoice.pick(shuffled, Params.TARGET_ALL).toString());
        check("primary is the lowest id", "[0]",
                DisplayChoice.pick(shuffled, Params.TARGET_PRIMARY).toString());

        List<PanelRef> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(on(0, "Built-in Screen", true));
        check("null entries ignored", "[0]",
                DisplayChoice.pick(withNull, Params.TARGET_ALL).toString());

        check("empty input", "[]",
                DisplayChoice.pick(new ArrayList<>(), Params.TARGET_ALL).toString());

        ok("empty category result triggers the fallback", DisplayChoice.needsFallback(0));
        ok("a populated category does not", !DisplayChoice.needsFallback(2));
    }
}
