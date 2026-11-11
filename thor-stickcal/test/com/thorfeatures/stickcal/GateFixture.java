package com.thorfeatures.stickcal;

/**
 * This unit's two gates, as measured on 2026-11-10.
 *
 * Copied from the per-angle maps recorded in
 * {@code thor/findings/controller-input.md}: the radius about the
 * pre-deflection rest position, in raw ADC counts, by 15-degree bucket. These
 * are real hardware measurements, not invented numbers, so a solver change
 * that would have produced a worse calibration on this device fails in CI.
 *
 * Both maps have their <b>minimum</b> radius at a cardinal (0 degrees) and
 * their maximum at an off-axis angle, which is the asymmetry per-axis
 * calibration cannot express and the reason this tool exists.
 */
final class GateFixture {

    /** Left stick: 86.0% round, min 1198 at 0 deg, max 1392 at 165 deg. */
    static final double[] LEFT = {
        1198, 1255, 1257, 1310, 1367, 1381, 1368, 1350,
        1368, 1389, 1374, 1392, 1372, 1375, 1347, 1332,
        1323, 1299, 1262, 1258, 1248, 1278, 1265, 1217
    };

    /** Right stick: 83.7% round, min 1195 at 0 deg, max 1428 at 240 deg. */
    static final double[] RIGHT = {
        1195, 1195, 1201, 1201, 1220, 1237, 1260, 1287,
        1317, 1348, 1350, 1342, 1311, 1373, 1363, 1389,
        1428, 1369, 1363, 1314, 1252, 1235, 1241, 1202
    };

    /** Rest position before any deflection: LX:LY:RX:RY. */
    static final int[] REST_BEFORE = { 111, -26, 138, 67 };
    /** And after both sticks have been rolled - 40 counts of LX hysteresis. */
    static final int[] REST_AFTER = { 151, -33, 156, 70 };

    /** The calibration string applied by hand on 2026-11-10 and verified. */
    static final String SHIPPED =
        "-1057:1142:131:60:-1102:1124:-30:60:-952:1116:147:60:-1057:1092:68:60:0:1550:0:1550";

    /** What AynParts' own wizard left behind, for comparison. */
    static final String WIZARD =
        "-1096:1170:139:200:-1160:1216:-28:200:-1017:1157:147:200:-1054:1222:67:200:0:1550:0:1550";

    private GateFixture() {}

    /**
     * The radius this map records for the sector an angle falls in.
     *
     * Piecewise constant by sector, which is the faithful inverse of how the
     * map was recorded: each figure is the furthest radius reached anywhere
     * in that 15-degree bucket, so a reconstruction whose bucket maximum
     * equals the recorded figure is the right one. Interpolating between
     * neighbours instead puts each bucket's maximum at its upper edge, which
     * quietly inflates every radius where the gate is widening - it reported
     * this left gate as 87.4% round rather than the measured 86.0%.
     */
    static double radiusAt(double[] gate, double degrees) {
        double a = ((degrees % 360) + 360) % 360;
        int b = (int) (a / 15.0);
        return gate[b % 24];
    }

    /**
     * A plausible sweep of a gate: two laps at 20 Hz, 1.5 degrees per sample.
     *
     * @param travel when true, bracket the laps with the stick travelling out
     *               from centre and back - the interior samples that a
     *               min-over-all-samples solver is vulnerable to
     */
    static GateMap sweep(double[] gate, boolean travel) {
        GateMap m = new GateMap();
        long t = 0;
        if (travel) {
            double r = radiusAt(gate, 0);
            for (int i = 1; i <= 12; i++, t += 50) {
                double f = i / 12.0;
                m.add(t, r * f, 0);
            }
        }
        // Offset off the bucket boundaries. A sample placed at exactly 15.0
        // degrees round-trips through cos/sin and atan2 to 14.999999999999998,
        // so GateMap sorts it into bucket 0 while this generator intended
        // bucket 1 - and bucket 0 then reports its neighbour's wider radius.
        // It read this gate as 87.4% round instead of the measured 86.0%.
        for (double a = 0.75; a < 720; a += 1.5, t += 50) {
            double r = radiusAt(gate, a);
            double rad = Math.toRadians(a);
            m.add(t, r * Math.cos(rad), r * Math.sin(rad));
        }
        if (travel) {
            double r = radiusAt(gate, 0);
            for (int i = 11; i >= 0; i--, t += 50) {
                double f = i / 12.0;
                m.add(t, r * f, 0);
            }
        }
        return m;
    }

    /** A sweep with one 15-degree sector left under-deflected. */
    static GateMap sweepWithNotch(double[] gate, int bucket, double fraction) {
        double[] g = gate.clone();
        g[bucket] = gate[bucket] * fraction;
        return sweep(g, false);
    }

    /** A hands-off phase holding one reading perfectly still, as this unit does. */
    static RestMeasure rest(int[] v, long durationMs) {
        RestMeasure r = new RestMeasure();
        RawSample s = new RawSample(v[0], v[1], v[2], v[3], 1509, 1489);
        for (long t = 0; t <= durationMs; t += 100) r.add(t, s);
        return r;
    }
}
