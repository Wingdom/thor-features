package com.thorfeatures.stickcal;

/**
 * Host tests for thor-stickcal. No device, no Android, no root.
 *
 * The solver is pure arithmetic over a measured gate, so there is no excuse
 * for a wrong limit reaching the hardware: the gates this runs against are
 * the ones actually measured on this unit (see {@link GateFixture}), and the
 * central assertion is the property the whole tool exists for - every one of
 * the 24 directions saturates.
 *
 * It also asserts the <b>defect</b>: a per-axis calibration built the way
 * AynParts' wizard builds one, from the same measured gate, must fail to
 * saturate diagonally. If that test ever passes, the premise of this
 * component is wrong and it should be deleted rather than shipped.
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

    /** Rounded to n decimals, so a float comparison is a string comparison. */
    private static String round(double v, int n) {
        return String.format("%." + n + "f", v);
    }

    public static void main(String[] args) {
        System.out.println("thor-stickcal logic tests");

        rawSamples();
        calibrationStrings();
        transferFunction();
        restAndHysteresis();
        gateCoverage();
        solver();
        theDefect();
        prefsFile();
        commands();
        phases();
        verification();

        System.out.println();
        System.out.println("  " + pass + " passed, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }

    // ---------------------------------------------------------------- raw ---

    private static void rawSamples() {
        System.out.println("\n-- the raw node --");

        RawSample s = RawSample.parse("0000:152:-19:151:73:1509:1489");
        check("a sample parses", "152:-19:151:73:1509:1489", s);
        check("axis 0 is LX", 152, s.axis(0));
        check("axis 3 is RY", 73, s.axis(3));

        // The leading field is never parsed, so a hex frame counter is fine.
        ok("a hex frame counter still parses", RawSample.parse("00ff:1:2:3:4:5:6") != null);
        ok("whitespace is tolerated", RawSample.parse(" 0:1:2:3:4:5:6 \n") != null);

        // A capture is a root shell's stdout and carries shell noise too. One
        // stray line must not end a capture the user spent a minute on.
        check("a short line is not a sample", null, RawSample.parse("0:1:2:3"));
        check("shell noise is not a sample", null,
              RawSample.parse("sh: can't open: Permission denied"));
        check("null is not a sample", null, RawSample.parse(null));
    }

    // -------------------------------------------------------- calibration ---

    private static void calibrationStrings() {
        System.out.println("\n-- the calibration string --");

        Calibration c = Calibration.parse(GateFixture.SHIPPED);
        ok("the applied string parses", c != null);
        check("and round-trips byte-identically", GateFixture.SHIPPED, c.toString());
        check("LX reads back", "-1057:1142:131:60", c.axis(0));
        check("RY reads back", "-1057:1092:68:60", c.axis(3));
        check("L2 is carried", "0:1550", c.l2);
        check("R2 is carried", "0:1550", c.r2);

        check("the wizard's string parses too", GateFixture.WIZARD,
              Calibration.parse(GateFixture.WIZARD).toString());

        // Nineteen or twenty-one fields would otherwise be read as a valid
        // calibration with axes silently defaulted - and the first thing this
        // tool does with a parsed value is store it as the original.
        check("19 fields is not a calibration", null,
              Calibration.parse("0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0"));
        check("the kernel default is not 20 fields", null,
              Calibration.parse("-1350:1350:0:0"));
        check("empty is not a calibration", null, Calibration.parse(""));

        // Travel per direction, as the driver computes it.
        Calibration.Axis lx = c.axis(0);
        check("positive travel is max - center", 1011, lx.limit(true));
        check("negative travel is center - min", 1188, lx.limit(false));

        // Replacing an axis must not disturb the triggers.
        Calibration swapped = c.withAxis(0, new Calibration.Axis(-1, 1, 0, 0));
        check("replacing an axis keeps L2", "0:1550", swapped.l2);
        check("replacing an axis keeps R2", "0:1550", swapped.r2);
        check("replacing an axis keeps the others", "-1057:1092:68:60", swapped.axis(3));

        // This value reaches a root shell inside single quotes and an XML
        // element. It is checked against the character set a calibration can
        // contain rather than escaped through two layers.
        ok("the applied string is safe", Calibration.isSafe(GateFixture.SHIPPED));
        ok("a quote is refused", !Calibration.isSafe("0:0:0:0'; rm -rf /;'"));
        ok("a space is refused", !Calibration.isSafe(GateFixture.SHIPPED + " x"));
        ok("a shell substitution is refused", !Calibration.isSafe("$(id):0:0:0"));
        ok("digits alone are not a calibration", !Calibration.isSafe("12345"));
        ok("null is refused", !Calibration.isSafe(null));
    }

    // ----------------------------------------------------------- transfer ---

    private static void transferFunction() {
        System.out.println("\n-- the driver's transfer function --");

        Calibration.Axis a = new Calibration.Axis(-1057, 1142, 131, 60);

        check("at centre the output is zero", "0.000", round(Transfer.axis(131, a), 3));
        check("inside the deadzone, zero", "0.000", round(Transfer.axis(190, a), 3));
        check("at the deadzone edge, zero", "0.000", round(Transfer.axis(191, a), 3));
        check("at max, full scale", "1.000", round(Transfer.axis(1142, a), 3));
        check("at min, full scale negative", "-1.000", round(Transfer.axis(-1057, a), 3));
        check("beyond max it clamps", "1.000", round(Transfer.axis(2000, a), 3));

        // The deadzone comes off numerator AND denominator: it shortens
        // travel, it does not merely mute the centre.
        check("just past the deadzone", "0.009", round(Transfer.axis(200, a), 3));
        // Two decimals: the travel either side of the midpoint is 951
        // counts, an odd number, so the exact midpoint is half a count away.
        check("halfway in travel terms", "0.50",
              round(Transfer.axis(131 + 60 + (1011 - 60) / 2, a), 2));

        check("evdev units are the fraction times 32767", 32767, Transfer.evdev(1142, a));
        check("and zero inside the deadzone", 0, Transfer.evdev(180, a));

        // A degenerate calibration must read zero rather than divide by it.
        Calibration.Axis flat = new Calibration.Axis(100, 100, 100, 60);
        check("no travel yields no output", "0.000", round(Transfer.axis(500, flat), 3));

        // Each axis clamps independently, so a cardinal reads ~1.0 and a
        // diagonal can legitimately reach 1.41. This is the artefact that
        // made cardinals look like they had headroom when they never did.
        Calibration.Axis b = new Calibration.Axis(-1057, 1092, 68, 60);
        check("a cardinal reads one", "1.000",
              round(Transfer.magnitude(1142, 68, a, b), 3));
        check("a saturated diagonal reads root two", "1.414",
              round(Transfer.magnitude(1142, 1092, a, b), 3));
    }

    // --------------------------------------------------- rest / hysteresis ---

    private static void restAndHysteresis() {
        System.out.println("\n-- rest, noise and hysteresis --");

        RestMeasure before = GateFixture.rest(GateFixture.REST_BEFORE, 37_000);
        RestMeasure after = GateFixture.rest(GateFixture.REST_AFTER, 8_000);

        // Noise measured exactly zero over 37 s on this unit: the MCU filters.
        check("rest noise is zero", 0, before.span(0));
        ok("a held reading settles", before.settled());

        RestAnalysis r = new RestAnalysis(before, after);

        // LX and RX reproduce the applied string exactly.
        check("LX centre is the hysteresis midpoint", 131, r.center[0]);
        check("RX centre is the hysteresis midpoint", 147, r.center[2]);
        // LY and RY land one count off the recorded string, and deliberately
        // so: both are exact .5 ties, and Java rounds a tie away from zero
        // where the Python prototype rounded it to even. One count is 0.08%
        // of this gate's travel. Asserted rather than papered over so that a
        // future change to the rounding is visible here.
        check("LY centre, tie rounded half up", -29, r.center[1]);
        check("RY centre, tie rounded half up", 69, r.center[3]);

        check("LX hysteresis is 40 counts", 40, r.hysteresis[0]);
        check("LY hysteresis is 7 counts", 7, r.hysteresis[1]);

        // Half the hysteresis, because the centre sits at its midpoint.
        check("LX needs at least 20 counts of deadzone", 20, r.requiredDeadzone(0));
        check("the left stick's floor is its worst axis", 20,
              r.requiredDeadzone(Stick.LEFT));
        check("the right stick's floor", 9, r.requiredDeadzone(Stick.RIGHT));
        check("recommended adds half again", 30, r.recommendedDeadzone(Stick.LEFT));
        check("and 20 is the floor on a clean axis", 20,
              r.recommendedDeadzone(Stick.RIGHT));

        // The shipped default has to clear the measured floor, or this unit
        // drifts at rest - which is what the deadzone is for.
        ok("the shipped deadzone clears the measured floor",
           GateSolver.DEFAULT_DEADZONE > r.recommendedDeadzone());

        // A stick that is still moving must not be mistaken for one at rest.
        RestMeasure moving = new RestMeasure();
        for (long t = 0; t <= 9_000; t += 100) {
            moving.add(t, new RawSample(111 + (int) (t / 100) % 80, -26, 138, 67, 0, 0));
        }
        ok("a drifting axis does not settle", !moving.settled());

        RestMeasure brief = GateFixture.rest(GateFixture.REST_BEFORE, 2_000);
        ok("two seconds is too short to settle", !brief.settled());

        // --- the stick travelling home is not noise -----------------------
        // A hands-off phase starts the instant the previous one ends, so its
        // first samples are the stick still returning from a roll. Measured
        // over the whole phase, that reported "noise 1358" on a unit whose
        // noise is zero, and told the user their 60-count deadzone was below
        // the 1019 counts the unit supposedly needed. Everything here is
        // measured over the trailing settle window instead.
        RestMeasure arriving = new RestMeasure();
        long t = 0;
        for (int i = 0; i < 12; i++, t += 100) {          // 1.2 s coming home
            int far = 1200 - i * 100;
            arriving.add(t, new RawSample(111 + far, -26, 138, 67, 0, 0));
        }
        for (; t <= 9_000; t += 100) {                     // then genuinely still
            arriving.add(t, new RawSample(111, -26, 138, 67, 0, 0));
        }
        ok("it does settle once the stick is home", arriving.settled());
        check("the approach is not counted as noise", 0, arriving.span(0));
        check("and the median is the resting value", 111, arriving.median(0));

        RestAnalysis fromArriving = new RestAnalysis(arriving, arriving);
        check("so the deadzone floor stays sane", 0, fromArriving.requiredDeadzone(0));
        check("and the recommendation is the floor, not a thousand", 20,
              fromArriving.recommendedDeadzone(Stick.LEFT));

        // The window is trailing, so a phase that is still moving at the end
        // must not be mistaken for a settled one whatever came before it.
        RestMeasure leaving = new RestMeasure();
        long u = 0;
        for (; u <= 6_000; u += 100) leaving.add(u, new RawSample(111, -26, 138, 67, 0, 0));
        for (int i = 0; i < 12; i++, u += 100) {
            leaving.add(u, new RawSample(111 + i * 100, -26, 138, 67, 0, 0));
        }
        ok("a stick being picked up again does not read as settled", !leaving.settled());
    }

    // ------------------------------------------------------------- the gate ---

    private static void gateCoverage() {
        System.out.println("\n-- mapping the gate --");

        GateMap left = GateFixture.sweep(GateFixture.LEFT, false);
        GateMap right = GateFixture.sweep(GateFixture.RIGHT, false);

        // Reproduces the roundness figures recorded for this unit.
        // 1198/1392 and 1195/1428. The finding quotes these as 86.0% and
        // 83.7%; the first differs in the last digit only because it was
        // printed there from a slightly different rounding.
        check("the left gate is 86% round", "0.861", round(left.roundness(), 3));
        check("the right gate is 83.7% round", "0.837", round(right.roundness(), 3));
        check("the left gate's widest radius", 1392, (int) Math.round(left.maxRadius()));
        check("the right gate's widest radius", 1428, (int) Math.round(right.maxRadius()));

        check("a full sweep covers every bucket", 24, left.bucketsOk());
        ok("and can advance on its own", left.complete());

        // Coverage is judged against neighbours, never against the largest
        // radius anywhere: the smallest radius on both of these gates is at a
        // cardinal, so a global threshold would reject the 0-degree bucket on
        // a perfect sweep.
        check("the minimum radius is at 0 degrees", 1198,
              (int) Math.round(left.bucketRadii()[0]));
        check("the maximum is off-axis at 165 degrees", 1392,
              (int) Math.round(left.bucketRadii()[11]));
        ok("and the narrowest bucket still passes", left.bucketOk(0));

        // The failure this is for: one sector the user did not push into.
        GateMap notched = GateFixture.sweepWithNotch(GateFixture.LEFT, 6, 0.75);
        ok("an under-deflected sector fails its bucket", !notched.bucketOk(6));
        check("and only that one", 23, notched.bucketsOk());
        ok("so the phase will not advance", !notched.complete());

        // Two adjacent thin buckets would satisfy each other's notch test;
        // the absolute floor is what catches them.
        double[] g = GateFixture.LEFT.clone();
        g[10] *= 0.45; g[11] *= 0.45;
        GateMap twoThin = GateFixture.sweep(g, false);
        ok("two adjacent thin sectors are both caught",
           !twoThin.bucketOk(10) && !twoThin.bucketOk(11));

        // A sweep still getting further out each lap has not found the gate.
        GateMap growing = new GateMap();
        long t = 0;
        for (int lap = 0; lap < 3; lap++) {
            for (double a = 0; a < 360; a += 1.5, t += 50) {
                double r = GateFixture.radiusAt(GateFixture.LEFT, a) * (0.8 + 0.1 * lap);
                growing.add(t, r * Math.cos(Math.toRadians(a)), r * Math.sin(Math.toRadians(a)));
            }
        }
        check("every bucket is covered", 24, growing.bucketsOk());
        ok("but a still-growing sweep is not steady", growing.steadyMs() < GateMap.STABLE_MS);
        ok("so it does not advance", !growing.complete());

        // Per-direction travel comes from gate samples only.
        // Not the radius at 0 and 180 degrees. The furthest the stick
        // physically reaches along +x is in the wider sector next door
        // (345 deg, radius 1217) rather than at the cardinal itself, which is
        // the narrowest point on this gate. Getting this backwards is how a
        // per-axis calibration ends up with limits no direction can satisfy.
        check("left travel +x comes from the wider neighbour", 1218,
              (int) Math.round(left.travel(true, true)));
        check("left travel -x", 1392, (int) Math.round(left.travel(true, false)));
        ok("+x travel exceeds the radius at 0 degrees",
           left.travel(true, true) > left.bucketRadii()[0]);

        // A short sweep cannot advance however clean it looks.
        GateMap quick = new GateMap();
        for (double a = 0; a < 360; a += 3) {
            double r = GateFixture.radiusAt(GateFixture.LEFT, a);
            quick.add((long) (a * 10), r * Math.cos(Math.toRadians(a)),
                      r * Math.sin(Math.toRadians(a)));
        }
        ok("under six seconds never advances", !quick.complete());
    }

    // ----------------------------------------------------------- the solve ---

    private static void solver() {
        System.out.println("\n-- solving the limits --");

        RestAnalysis rest = new RestAnalysis(
                GateFixture.rest(GateFixture.REST_BEFORE, 37_000),
                GateFixture.rest(GateFixture.REST_AFTER, 8_000));

        GateSolver.Solution l = GateSolver.solve(Stick.LEFT,
                GateFixture.sweep(GateFixture.LEFT, false),
                rest.center[0], rest.center[1],
                GateSolver.DEFAULT_DEADZONE, GateSolver.DEFAULT_TARGET);
        GateSolver.Solution r = GateSolver.solve(Stick.RIGHT,
                GateFixture.sweep(GateFixture.RIGHT, false),
                rest.center[2], rest.center[3],
                GateSolver.DEFAULT_DEADZONE, GateSolver.DEFAULT_TARGET);

        ok("the left solve is not degenerate", !l.degenerate);
        ok("the right solve is not degenerate", !r.degenerate);

        // THE assertion. Every direction must saturate, which is the whole
        // reason this component exists.
        check("no left bucket falls short of full scale", 0, l.bucketsUnderFullScale());
        check("no right bucket falls short of full scale", 0, r.bucketsUnderFullScale());

        // And the worst one lands exactly on the requested margin: that is
        // what the closed form solves for, so anything else is an arithmetic
        // slip between the solve and the limits that get written.
        check("the worst left direction hits the target", "1.120", round(l.worst, 3));
        check("the worst right direction hits the target", "1.120", round(r.worst, 3));

        // The measured cost, which matches the 15-18% recorded on hardware.
        // Matches the 15-18% of travel the hand process cost on hardware.
        check("the left stick saturates at 86% of travel", "0.855", round(l.travelUsed, 3));
        check("the right stick saturates at 83% of travel", "0.826", round(r.travelUsed, 3));
        ok("both land in the measured 80-90% band",
           l.travelUsed > 0.80 && l.travelUsed < 0.90
                && r.travelUsed > 0.80 && r.travelUsed < 0.90);

        // Diagonals end up with real headroom, cardinals near the target.
        ok("the best direction has more headroom than the worst", l.best > l.worst);
        ok("no direction is absurdly over", l.best < 1.6);

        // Close to the applied string, not identical, and the difference is
        // the fixture rather than the solver: these gates are the 24-point
        // maps the finding recorded, where the hand solve ran over the whole
        // sample cloud those maps were reduced from. 50 counts is ~4% of this
        // gate's travel, and the headroom assertions above are what actually
        // constrains the result.
        Calibration applied = Calibration.parse(GateFixture.SHIPPED);
        for (int i = 0; i < 4; i++) {
            Calibration.Axis solved = i == 0 ? l.x : i == 1 ? l.y : i == 2 ? r.x : r.y;
            ok("axis " + i + " max is within 50 counts of what was applied",
               Math.abs(solved.max - applied.axis(i).max) <= 50);
            ok("axis " + i + " min is within 50 counts of what was applied",
               Math.abs(solved.min - applied.axis(i).min) <= 50);
        }

        // Interior samples must not reach the solve. min() over a point set
        // is decided by one member, so a single sample caught halfway out on
        // the way in would have halved every limit under the prototype's
        // radius filter.
        GateSolver.Solution withTravel = GateSolver.solve(Stick.LEFT,
                GateFixture.sweep(GateFixture.LEFT, true),
                rest.center[0], rest.center[1],
                GateSolver.DEFAULT_DEADZONE, GateSolver.DEFAULT_TARGET);
        check("travelling in and out does not change LX max", l.x.max, withTravel.x.max);
        check("nor LY min", l.y.min, withTravel.y.min);
        check("nor the travel used", round(l.travelUsed, 6), round(withTravel.travelUsed, 6));

        // An under-deflected sector costs travel rather than producing a
        // calibration that falls short there - the solver believes the gate
        // it was shown.
        GateSolver.Solution notched = GateSolver.solve(Stick.LEFT,
                GateFixture.sweepWithNotch(GateFixture.LEFT, 6, 0.75),
                rest.center[0], rest.center[1],
                GateSolver.DEFAULT_DEADZONE, GateSolver.DEFAULT_TARGET);
        check("a notch still saturates everywhere", 0, notched.bucketsUnderFullScale());
        ok("but costs real travel", notched.travelUsed < l.travelUsed - 0.1);
        check("the notch costs a fifth of the travel", "0.653",
              round(notched.travelUsed, 3));

        // Margin and deadzone both move the result in the expected direction.
        GateSolver.Solution tight = GateSolver.solve(Stick.LEFT,
                GateFixture.sweep(GateFixture.LEFT, false),
                rest.center[0], rest.center[1], GateSolver.DEFAULT_DEADZONE, 1.00);
        // At a margin of exactly 1.0 the worst direction lands on full scale
        // before the limits are rounded to whole counts, and rounding can
        // leave it a fraction under. Which is the point of having a margin at
        // all: 1.12 is not caution about the maths, it is room for the gate
        // to be measured a little short and for the stick to wear.
        check("and lands on full scale", "1.000", round(tight.worst, 3));
        ok("though rounding can leave one direction a hair under",
           tight.bucketsUnderFullScale() <= 1);
        ok("using more travel than the shipped margin", tight.travelUsed > l.travelUsed);

        GateSolver.Solution deadish = GateSolver.solve(Stick.LEFT,
                GateFixture.sweep(GateFixture.LEFT, false),
                rest.center[0], rest.center[1], 200, GateSolver.DEFAULT_TARGET);
        check("a 200-count deadzone still saturates", 0, deadish.bucketsUnderFullScale());
        check("because the deadzone is inside the limits too", 200, deadish.x.deadzone);

        // A deadzone wider than the travel is unsolvable, and must say so
        // rather than emit a calibration.
        GateSolver.Solution broken = GateSolver.solve(Stick.LEFT,
                GateFixture.sweep(GateFixture.LEFT, false),
                rest.center[0], rest.center[1], 2000, GateSolver.DEFAULT_TARGET);
        ok("a deadzone past the gate is degenerate", broken.degenerate);
        check("and writes no travel", "0.000", round(broken.travelUsed, 3));

        GateSolver.Solution empty = GateSolver.solve(Stick.LEFT, new GateMap(), 0, 0,
                GateSolver.DEFAULT_DEADZONE, GateSolver.DEFAULT_TARGET);
        ok("an empty gate is degenerate", empty.degenerate);

        // The saturation contour is the inverse of the transfer function, and
        // is what the visualiser draws against the measured gate.
        double rad0 = l.saturationRadius(0);
        ok("the saturation radius at 0 deg is inside the gate", rad0 < 1198 && rad0 > 900);
        check("and is exactly the limit on that axis", l.x.max - l.x.center,
              (int) Math.round(rad0));

        // The contour's defining property, at every angle: a stick pushed to
        // exactly that radius reads exactly full scale. Asserted over all 24
        // directions because the near-cardinal case takes a different branch
        // - one axis never leaves its deadzone there - and getting that
        // branch wrong drew a contour that was wrong only where the gate is
        // narrowest.
        int offContour = 0;
        for (int b = 0; b < GateMap.BUCKETS; b++) {
            double ang = GateMap.bucketAngle(b);
            double rad = l.saturationRadius(ang);
            double m = Transfer.headroom(rad * Math.cos(ang), rad * Math.sin(ang),
                    l.x.limit(true), l.x.limit(false),
                    l.y.limit(true), l.y.limit(false), l.deadzone);
            if (Math.abs(m - 1.0) > 1e-9) offContour++;
        }
        check("the saturation contour reads full scale all the way round",
              0, offContour);

        // Folding both sticks into one string must leave the triggers alone.
        Calibration out = GateSolver.apply(applied, l, r);
        check("the folded string carries L2 through", "0:1550", out.l2);
        check("and R2", "0:1550", out.r2);
        ok("and is safe to write", Calibration.isSafe(out.toString()));
        check("it is twenty fields", 20, out.toString().split(":").length);
    }

    // -------------------------------------------------------- the defect ---

    private static void theDefect() {
        System.out.println("\n-- the defect this replaces --");

        // Build a calibration the way a per-axis wizard must: each axis's
        // limit set to that axis's own measured extreme. This is the best
        // AynParts' interface can express, and on a gate whose minimum radius
        // is at a cardinal it CANNOT saturate the diagonals. If this ever
        // passes, the premise of this component is wrong.
        GateMap g = GateFixture.sweep(GateFixture.LEFT, false);
        double px = g.travel(true, true), nx = g.travel(true, false);
        double py = g.travel(false, true), ny = g.travel(false, false);

        double[][] pts = g.gatePoints();
        double worst = Double.MAX_VALUE, worstDz = Double.MAX_VALUE;
        int under = 0, underDz = 0;
        for (int b = 0; b < GateMap.BUCKETS; b++) {
            if (pts[b] == null) continue;
            double m = Transfer.headroom(pts[b][0], pts[b][1], px, nx, py, ny, 0);
            double mDz = Transfer.headroom(pts[b][0], pts[b][1], px, nx, py, ny, 200);
            worst = Math.min(worst, m);
            worstDz = Math.min(worstDz, mDz);
            if (m < 1.0) under++;
            if (mDz < 1.0) underDz++;
        }

        ok("per-axis extremes fall short somewhere", worst < 1.0);
        ok("in most directions, not a few", under >= 12);

        // And with AynParts' own 200-count default deadzone, in ALL of them.
        // The deadzone comes off both the numerator and the denominator of
        // each axis, so it costs magnitude on both at once - which is why a
        // deadzone chosen for noise that measured zero did real harm.
        check("a 200-count deadzone puts every direction under full scale",
              GateMap.BUCKETS, underDz);
        ok("and makes the worst case worse", worstDz < worst);

        // The measured symptom: the worst direction sits just under the run
        // threshold, which is why a character sneaked rather than ran.
        ok("the shortfall is in the measured 0.85-0.99 band",
           worstDz > 0.85 && worstDz < 0.99);

        // The same gate, solved radially, saturates in every direction. This
        // pairing is the component's whole argument in two assertions.
        RestAnalysis rest = new RestAnalysis(
                GateFixture.rest(GateFixture.REST_BEFORE, 37_000),
                GateFixture.rest(GateFixture.REST_AFTER, 8_000));
        GateSolver.Solution solved = GateSolver.solve(Stick.LEFT, g,
                rest.center[0], rest.center[1],
                GateSolver.DEFAULT_DEADZONE, GateSolver.DEFAULT_TARGET);
        check("solved radially, nothing falls short", 0, solved.bucketsUnderFullScale());
    }

    // --------------------------------------------------------- prefs file ---

    private static void prefsFile() {
        System.out.println("\n-- AynParts' preferences --");

        String original =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
          + "<map>\n"
          + "    <string name=\"gamepad_trigger_mode\">both</string>\n"
          + "    <string name=\"joystick_calibration\">" + GateFixture.WIZARD + "</string>\n"
          + "    <boolean name=\"rgb_lighting_enable\" value=\"false\" />\n"
          + "    <string name=\"global_lighting\">0:165:180:85</string>\n"
          + "</map>\n";

        check("the current value is read back", GateFixture.WIZARD, PrefsXml.read(original));

        String updated = PrefsXml.withCalibration(original, GateFixture.SHIPPED);
        check("the new value is in place", GateFixture.SHIPPED, PrefsXml.read(updated));

        // The same file holds the RGB configuration, the gamepad layout and
        // the trigger settings. Regenerating it would drop all of them.
        ok("the trigger mode survives", updated.contains("gamepad_trigger_mode\">both"));
        ok("the RGB switch survives", updated.contains("rgb_lighting_enable"));
        ok("the global lighting survives", updated.contains("0:165:180:85"));
        ok("the header survives", updated.startsWith("<?xml"));
        check("exactly one calibration element", 1,
              updated.split("name=\"joystick_calibration\"", -1).length - 1);
        check("nothing else changed", original.length() - GateFixture.WIZARD.length(),
              updated.length() - GateFixture.SHIPPED.length());

        // AynParts had never been opened on this device, so no file existed.
        String fresh = PrefsXml.withCalibration(null, GateFixture.SHIPPED);
        check("a missing file is created", GateFixture.SHIPPED, PrefsXml.read(fresh));
        ok("with a map element", fresh.contains("<map>") && fresh.contains("</map>"));
        check("an empty file is the same case", GateFixture.SHIPPED,
              PrefsXml.read(PrefsXml.withCalibration("   \n", GateFixture.SHIPPED)));

        String noKey =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
          + "    <string name=\"rgb_mode\">global</string>\n</map>\n";
        String inserted = PrefsXml.withCalibration(noKey, GateFixture.SHIPPED);
        check("a file without the key gains it", GateFixture.SHIPPED, PrefsXml.read(inserted));
        ok("and keeps what it had", inserted.contains("rgb_mode\">global"));

        check("no key means no value", null, PrefsXml.read(noKey));
        check("null means no value", null, PrefsXml.read(null));

        // Single-quoted attributes, in case a different serializer wrote it.
        check("single-quoted attributes are found", "1:2:3:4",
              PrefsXml.read("<map><string name='joystick_calibration'>1:2:3:4</string></map>"));

        // The value is the last structured thing before a root shell sees it.
        try {
            PrefsXml.withCalibration(original, "0:0:0:0'; rm -rf /; '");
            check("an unsafe value is refused", "threw", "did not throw");
        } catch (IllegalArgumentException e) {
            check("an unsafe value is refused", "threw", "threw");
        }

        // A file that is not a preferences map is not ours to replace.
        try {
            PrefsXml.withCalibration("this is not xml at all", GateFixture.SHIPPED);
            check("a non-map file is refused", "threw", "did not throw");
        } catch (IllegalArgumentException e) {
            check("a non-map file is refused", "threw", "threw");
        }

        // The DE store, not /data/data: AynParts is directBootAware and its
        // default preferences are not in the credential-encrypted path.
        ok("the path is the device-encrypted store", PrefsXml.PATH.startsWith("/data/user_de/0/"));
    }

    // ----------------------------------------------------------- commands ---

    private static void commands() {
        System.out.println("\n-- the shell commands --");

        String[] all = {
            DeviceCommands.WHOAMI,
            DeviceCommands.readCalibration(),
            DeviceCommands.readRaw(),
            DeviceCommands.readPrefs(),
            DeviceCommands.probeFractionalSleep(),
            DeviceCommands.canSeeAynParts(),
            DeviceCommands.streamRaw(true),
            DeviceCommands.streamRaw(false),
            DeviceCommands.forceStopAynParts(),
            DeviceCommands.writeCalibration(GateFixture.SHIPPED),
            DeviceCommands.installPrefs("/data/user/0/com.thorfeatures.stickcal/cache/p.xml"),
        };

        // A multi-line string handed to su -c never reaches su: it answers
        // "sh: -c: requires an argument", exits, and the rest runs as uid
        // 2000 whose EACCES looks exactly like an SELinux denial.
        for (String c : all) {
            ok("one line: " + c.substring(0, Math.min(28, c.length())), !c.contains("\n"));
        }
        // cd inside a root shell has silently failed here and run the command
        // somewhere else. Twice, in one session.
        for (String c : all) {
            ok("no cd: " + c.substring(0, Math.min(28, c.length())), !c.contains("cd "));
        }

        ok("root is asserted, not assumed", DeviceCommands.WHOAMI.equals("id -u"));

        // --- the mount namespace ------------------------------------------
        // Measured on the device 2026-11-11: an app's mount namespace lists
        // TWO entries under /data/user_de/0 - itself and GMS - where the
        // shell's lists 223. So a root shell started from an app is genuinely
        // root and still cannot see AynParts at all: no SELinux denial, no
        // error, [ -f <its prefs> ] simply false. The calibration reached the
        // node and took effect while the write that makes it survive a reboot
        // silently did not happen. --mount-master is what fixes it.
        String[][] su = DeviceCommands.suCandidates();
        ok("there is more than one way to start su", su.length >= 2);
        check("the first attempt asks for the global mount namespace",
              "su --mount-master", String.join(" ", su[0]));
        check("the last falls back to plain su", "su", String.join(" ", su[su.length - 1]));
        for (String[] c : su) {
            ok("candidate starts with su: " + String.join(" ", c), "su".equals(c[0]));
        }
        // Checked when the session opens so the app can say a calibration
        // will not persist BEFORE a minute of rolling the sticks, not after.
        ok("the visibility probe names AynParts",
           DeviceCommands.canSeeAynParts().contains(PrefsXml.PACKAGE));
        ok("and answers yes or no",
           DeviceCommands.canSeeAynParts().contains("echo yes")
                && DeviceCommands.canSeeAynParts().contains("echo no"));
        ok("the probe is one line", !DeviceCommands.canSeeAynParts().contains("\n"));
        ok("the probe uses an absolute path",
           DeviceCommands.canSeeAynParts().contains("/data/user_de/0/"));

        ok("the stream reads one line per open", DeviceCommands.streamRaw(true).contains("read -r L <"));
        ok("fractional sleep streams at 20 Hz", DeviceCommands.streamRaw(true).contains("sleep 0.05"));
        ok("whole-second sleep falls back to 10 Hz", DeviceCommands.streamRaw(false).contains("sleep 0.1"));
        check("and the interval is reported to match", 50, DeviceCommands.intervalMs(true));
        check("the fallback interval", 100, DeviceCommands.intervalMs(false));

        // The write and its read-back are one command, so nothing can happen
        // in between.
        String w = DeviceCommands.writeCalibration(GateFixture.SHIPPED);
        ok("the write uses printf, not echo", w.contains("printf '%s'"));
        ok("and reads the node back in the same command", w.contains("&& cat " + DeviceCommands.NODE_CALIBRATION));

        try {
            DeviceCommands.writeCalibration("0:0:0:0'; reboot; '");
            check("an unsafe value never reaches a shell", "threw", "did not throw");
        } catch (IllegalArgumentException e) {
            check("an unsafe value never reaches a shell", "threw", "threw");
        }

        String inst = DeviceCommands.installPrefs("/tmp/p.xml");
        ok("ownership is cloned, not hardcoded", inst.contains("stat -c %u:%g"));
        ok("the file is staged and moved", inst.contains(".new") && inst.contains("mv -f"));
        ok("SELinux labelling is restored", inst.contains("restorecon"));
        // SharedPreferences restores a .bak over the main file on load, so a
        // stale backup would undo this write.
        ok("a stale .bak is removed", inst.contains("rm -f " + PrefsXml.PATH + ".bak"));

        check("exit 20 is a missing AynParts", "AynParts is not installed",
              DeviceCommands.installPrefsError(20));
        check("exit 24 names the move", "could not move the preferences file into place",
              DeviceCommands.installPrefsError(24));
        check("success has no message", null, DeviceCommands.installPrefsError(0));
        check("an unknown code still says something", "root command failed (7)",
              DeviceCommands.installPrefsError(7));
    }

    // ------------------------------------------------------------- phases ---

    private static void phases() {
        System.out.println("\n-- the wizard's order --");

        // The cold rest phase must come before any deflection, or it is not a
        // cold rest position.
        ok("rest is measured before any roll",
           Phase.REST_BEFORE.ordinal() < Phase.ROLL_LEFT.ordinal());
        ok("and again after both rolls",
           Phase.REST_AFTER.ordinal() > Phase.ROLL_RIGHT.ordinal());

        check("intro leads to the cold rest", Phase.REST_BEFORE, Phase.INTRO.next());
        check("then the left stick", Phase.ROLL_LEFT, Phase.REST_BEFORE.next());
        check("then the right", Phase.ROLL_RIGHT, Phase.ROLL_LEFT.next());
        check("then rest again", Phase.REST_AFTER, Phase.ROLL_RIGHT.next());
        check("then the review", Phase.REVIEW, Phase.REST_AFTER.next());
        check("then verification", Phase.VERIFY, Phase.REVIEW.next());
        check("done is the end", Phase.DONE, Phase.DONE.next());
        check("intro is the beginning", Phase.INTRO, Phase.INTRO.previous());

        check("the left roll is the left stick", Stick.LEFT, Phase.ROLL_LEFT.stick);
        check("the right roll is the right stick", Stick.RIGHT, Phase.ROLL_RIGHT.stick);
        check("rest belongs to neither", null, Phase.REST_BEFORE.stick);

        ok("the capture phases sample", Phase.ROLL_LEFT.samples && Phase.REST_AFTER.samples);
        ok("the review does not", !Phase.REVIEW.samples);
        // Verify reads MotionEvent, not sysfs: the one check that proves the
        // calibration is in effect rather than merely stored.
        ok("verification needs no raw stream", !Phase.VERIFY.needsStream());

        check("four capture steps", 4, Phase.CAPTURE_STEPS);
        check("the left roll is step two", 2, Phase.ROLL_LEFT.captureStep());
        check("the review is outside the capture", 0, Phase.REVIEW.captureStep());
    }

    // ------------------------------------------------------ verification ---

    private static void verification() {
        System.out.println("\n-- verifying downstream --");

        // A full sweep of a calibration that saturates everywhere.
        VerifyMap good = new VerifyMap();
        for (double a = 0; a < 720; a += 1.5) {
            double rad = Math.toRadians(a);
            // 1.12 of full scale, clamped per axis as the framework does.
            double x = Math.min(1, Math.max(-1, 1.12 * Math.cos(rad)));
            double y = Math.min(1, Math.max(-1, 1.12 * Math.sin(rad)));
            good.add(x, y);
        }
        check("every bucket is covered", 24, good.bucketsCovered());
        check("none falls short", 0, good.bucketsUnder());
        ok("so it passes", good.passed());
        // A cardinal reads ~1.0 because one axis clamps and the other is near
        // zero. That is the clamp, not headroom.
        ok("the worst reading is at or just over full scale",
           good.worst() >= 1.0 && good.worst() < 1.05);
        // A uniform 1.12 of full scale clamps to 1.0 at the cardinals and
        // stays at 1.12 everywhere else, so the spread here is the clamp.
        ok("and the best is the full margin", good.best() > 1.10 && good.best() < 1.15);

        // The fault as it actually presented: diagonals short, cardinals fine.
        VerifyMap bad = new VerifyMap();
        for (double a = 0; a < 720; a += 1.5) {
            double rad = Math.toRadians(a);
            double x = Math.min(1, Math.max(-1, Math.cos(rad) / 0.75));
            double y = Math.min(1, Math.max(-1, Math.sin(rad) * 0.63 / 0.75));
            bad.add(x, y);
        }
        ok("a short diagonal fails", bad.bucketsUnder() > 0);
        ok("and the sweep does not pass", !bad.passed());
        ok("while the cardinals still read full scale", bad.magnitudes()[0] >= 1.0);

        // Reporting success on a partial sweep would tell the user the
        // diagonals are fixed without having looked at a quarter of them.
        VerifyMap partial = new VerifyMap();
        for (double a = 0; a < 270; a += 1.5) {
            double rad = Math.toRadians(a);
            partial.add(1.12 * Math.cos(rad), 1.12 * Math.sin(rad));
        }
        check("three quarters is not covered", 18, partial.bucketsCovered());
        check("nothing covered falls short", 0, partial.bucketsUnder());
        ok("but it still does not pass", !partial.passed());

        // Readings on the way out are not gate readings.
        VerifyMap travel = new VerifyMap();
        travel.add(0.1, 0.0);
        travel.add(0.2, 0.0);
        check("small deflections are ignored", 0, travel.bucketsCovered());

        // --- what to say on the way out ------------------------------------
        // Found on the device: the obvious thing to do on the check screen is
        // roll one stick and move on, so arriving at Done without a pass
        // usually means "not finished", not "still broken". Saying the latter
        // sends the user back to redo work that was fine.
        check("a clean pass says nothing", null, Report.verifyExitNote(good, good));

        VerifyMap untouched = new VerifyMap();
        String unswept = Report.verifyExitNote(good, untouched);
        ok("an unswept stick is called unswept", unswept.contains("never swept"));
        ok("and is not called a shortfall", !unswept.contains("below full scale"));
        ok("and says the check is incomplete rather than failed",
           unswept.contains("incomplete, not failed"));
        ok("and counts both sticks' directions", unswept.contains("of 48"));

        String shortfall = Report.verifyExitNote(bad, good);
        ok("a genuine shortfall is called one", shortfall.contains("below full scale"));
        ok("and points at the margin", shortfall.contains("raise"));
        ok("and does not mention sweeping", !shortfall.contains("never swept"));

        String both = Report.verifyExitNote(bad, untouched);
        ok("both problems at once are both named",
           both.contains("below full scale") && both.contains("never swept"));
    }
}
