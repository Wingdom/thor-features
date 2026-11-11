package com.thorfeatures.stickcal;

/**
 * The value of /sys/class/moorechip-joystick/joystick/calibration.
 *
 * Twenty colon-separated integers: four stick axes as
 * {@code min:max:center:deadzone} in the order LX LY RX RY, then the two
 * analogue triggers as {@code min:max}.
 *
 * <pre>
 *   -1057:1142:131:60 : -1102:1124:-30:60 : -952:1116:147:60 : -1057:1092:68:60 : 0:1550 : 0:1550
 *      LX                   LY                  RX                 RY             L2       R2
 * </pre>
 *
 * This is the same string AynParts keeps in its {@code joystick_calibration}
 * preference, so one parser serves both the node and the preference.
 *
 * <h3>The triggers are carried, not computed</h3>
 *
 * Nothing in this tool solves for L2/R2. They are read from whatever the node
 * currently holds and written back unchanged, so a value AynParts' own wizard
 * arrived at survives a stick calibration. Hardcoding the {@code 0:1550} this
 * unit happens to carry would silently overwrite another unit's triggers.
 */
public final class Calibration {

    /** One axis's four fields. Immutable. */
    public static final class Axis {
        public final int min, max, center, deadzone;

        public Axis(int min, int max, int center, int deadzone) {
            this.min = min; this.max = max; this.center = center; this.deadzone = deadzone;
        }

        /**
         * Travel available in one direction, as the driver computes it.
         *
         * @param positive true for the {@code max} side, false for {@code min}
         */
        public int limit(boolean positive) {
            return positive ? max - center : center - min;
        }

        @Override
        public String toString() { return min + ":" + max + ":" + center + ":" + deadzone; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Axis)) return false;
            Axis a = (Axis) o;
            return min == a.min && max == a.max && center == a.center && deadzone == a.deadzone;
        }

        @Override
        public int hashCode() { return ((min * 31 + max) * 31 + center) * 31 + deadzone; }
    }

    /** An analogue trigger's two fields. */
    public static final class Trigger {
        public final int min, max;
        public Trigger(int min, int max) { this.min = min; this.max = max; }
        @Override public String toString() { return min + ":" + max; }
        @Override public boolean equals(Object o) {
            return o instanceof Trigger && ((Trigger) o).min == min && ((Trigger) o).max == max;
        }
        @Override public int hashCode() { return min * 31 + max; }
    }

    /** LX, LY, RX, RY. */
    public final Axis[] axes;
    public final Trigger l2, r2;

    public Calibration(Axis[] axes, Trigger l2, Trigger r2) {
        if (axes == null || axes.length != 4) throw new IllegalArgumentException("need 4 axes");
        this.axes = axes.clone();
        this.l2 = l2; this.r2 = r2;
    }

    public Axis axis(int i) { return axes[i]; }

    /** Replace one axis, keeping everything else - including the triggers. */
    public Calibration withAxis(int i, Axis a) {
        Axis[] n = axes.clone();
        n[i] = a;
        return new Calibration(n, l2, r2);
    }

    /**
     * Parse the node's value, or return null if it is not twenty integers.
     *
     * Strict about the field count on purpose. A short string would otherwise
     * be read as a valid calibration with axes silently defaulted, and the
     * first thing this tool does with a parsed value is save it as the
     * original to restore to.
     */
    public static Calibration parse(String s) {
        if (s == null) return null;
        String[] f = s.trim().split(":");
        if (f.length != 20) return null;
        int[] v = new int[20];
        for (int i = 0; i < 20; i++) {
            try {
                v[i] = Integer.parseInt(f[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Axis[] ax = new Axis[4];
        for (int i = 0; i < 4; i++) {
            ax[i] = new Axis(v[i * 4], v[i * 4 + 1], v[i * 4 + 2], v[i * 4 + 3]);
        }
        return new Calibration(ax, new Trigger(v[16], v[17]), new Trigger(v[18], v[19]));
    }

    /** The exact text to write to the node. No trailing newline. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Axis a : axes) sb.append(a).append(':');
        return sb.append(l2).append(':').append(r2).toString();
    }

    /**
     * Whether a string is safe to hand to a shell and to AynParts' XML.
     *
     * The written value reaches a root shell inside single quotes and an XML
     * attribute, so it is checked against the only character set a
     * calibration can legitimately contain rather than escaped. Anything else
     * is a bug upstream of here, and refusing it is cheaper than reasoning
     * about two quoting layers.
     */
    public static boolean isSafe(String s) {
        if (s == null || s.isEmpty() || s.length() > 200) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ':' && c != '-' && (c < '0' || c > '9')) return false;
        }
        return parse(s) != null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Calibration)) return false;
        return toString().equals(o.toString());
    }

    @Override
    public int hashCode() { return toString().hashCode(); }
}
