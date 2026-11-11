package com.thorfeatures.stickcal;

/**
 * One line of /sys/class/moorechip-joystick/joystick/raw.
 *
 * The node emits seven colon-separated fields:
 *
 * <pre>
 *   0000:152:-19:151:73:1509:1489
 *   ^    ^   ^   ^   ^  ^    ^
 *   |    LX  LY  RX  RY L2   R2
 *   frame counter
 * </pre>
 *
 * The leading field is deliberately NOT parsed. It is a counter whose base is
 * unverified - "0000" reads the same in decimal and hex - and nothing here
 * needs it. Parsing it would be a guess that could reject every sample on a
 * firmware that counts in hex.
 *
 * Values are raw ADC counts about an arbitrary electrical zero, not about the
 * stick's mechanical centre: this unit rests at LX=152 with a nominal range of
 * +/-1350. Finding the centre is the calibration's job, not the node's.
 */
public final class RawSample {

    /** Stick axes, in the node's own order. Index with {@link Stick}. */
    public final int lx, ly, rx, ry;
    /** Analogue triggers. Carried through, never solved for. */
    public final int l2, r2;

    public RawSample(int lx, int ly, int rx, int ry, int l2, int r2) {
        this.lx = lx; this.ly = ly; this.rx = rx; this.ry = ry;
        this.l2 = l2; this.r2 = r2;
    }

    /** One axis by its index 0..3 (LX, LY, RX, RY). */
    public int axis(int i) {
        switch (i) {
            case 0: return lx;
            case 1: return ly;
            case 2: return rx;
            case 3: return ry;
            default: throw new IllegalArgumentException("axis " + i);
        }
    }

    /**
     * Parse one line, or return null if it is not a sample.
     *
     * Returning null rather than throwing is deliberate: the stream this
     * reads is a root shell's stdout, which also carries shell diagnostics,
     * and a single stray line must not end a capture the user has spent a
     * minute on.
     */
    public static RawSample parse(String line) {
        if (line == null) return null;
        String[] f = line.trim().split(":");
        if (f.length != 7) return null;
        try {
            return new RawSample(
                    Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()),
                    Integer.parseInt(f[3].trim()), Integer.parseInt(f[4].trim()),
                    Integer.parseInt(f[5].trim()), Integer.parseInt(f[6].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return lx + ":" + ly + ":" + rx + ":" + ry + ":" + l2 + ":" + r2;
    }
}
