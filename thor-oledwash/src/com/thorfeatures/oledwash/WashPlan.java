package com.thorfeatures.oledwash;

/**
 * Where the band is at a given moment, and when the wash is over.
 *
 * <p>Pure arithmetic over a panel height. Nothing here knows about Android,
 * which is the point: the geometry is the part that can be wrong in a way
 * nobody notices on screen, so it is the part that gets tested on the host.
 *
 * <p><b>The band sweeps from fully off the top to fully off the bottom.</b>
 * Its top edge travels {@code panelHeight + bandHeight}, not {@code panelHeight},
 * because a band that started with its top edge at 0 would never light row 0
 * for its full dwell and a band that stopped there would never light the last
 * row at all. The lead-in and lead-out are what make the coverage uniform.
 *
 * <p>One consequence worth knowing before reading the numbers: because of that
 * extra travel, each row is lit for {@code bandHeight / (panelHeight +
 * bandHeight)} of a cycle, not {@code bandHeight / panelHeight}. At a band of
 * one third of the panel that is 25 % per cycle, not 33 %.
 */
public final class WashPlan {

    /** A band thinner than this is not worth drawing and probably a bad input. */
    public static final int MIN_BAND_PX = 8;

    public final int panelHeight;
    public final int bandHeight;
    public final long cycleMs;
    public final int cycles;

    private WashPlan(int panelHeight, int bandHeight, long cycleMs, int cycles) {
        this.panelHeight = panelHeight;
        this.bandHeight = bandHeight;
        this.cycleMs = cycleMs;
        this.cycles = cycles;
    }

    /**
     * @param panelHeight  the drawable height in pixels, taken from the view at
     *                     runtime rather than from a constant -- this is the
     *                     whole of what makes the component portable to a panel
     *                     that is not 1080 tall.
     * @param bandFraction band height as a fraction of the panel, 0 &lt; f &lt;= 1
     */
    public static WashPlan of(int panelHeight, double bandFraction, long cycleMs, int cycles) {
        if (panelHeight < 1) throw new IllegalArgumentException("panelHeight < 1");
        if (cycleMs < 1) throw new IllegalArgumentException("cycleMs < 1");
        if (cycles < 1) throw new IllegalArgumentException("cycles < 1");
        if (!(bandFraction > 0.0) || bandFraction > 1.0) {
            throw new IllegalArgumentException("bandFraction out of range: " + bandFraction);
        }
        int h = (int) Math.round(panelHeight * bandFraction);
        if (h < MIN_BAND_PX) h = Math.min(MIN_BAND_PX, panelHeight);
        if (h > panelHeight) h = panelHeight;
        return new WashPlan(panelHeight, h, cycleMs, cycles);
    }

    /** Distance the band's top edge covers in one cycle. */
    public int travel() {
        return panelHeight + bandHeight;
    }

    public long totalMs() {
        return cycleMs * (long) cycles;
    }

    /** 0-based cycle index at t, clamped into range so a readout cannot overrun. */
    public int cycleAt(long t) {
        if (t <= 0) return 0;
        long c = t / cycleMs;
        return (c >= cycles) ? cycles - 1 : (int) c;
    }

    /**
     * Top edge of the band at t, in panel pixels. Negative means the band is
     * still entering from above; {@code >= panelHeight} means it has left.
     *
     * <p>At exactly {@code totalMs} this returns the band fully clear of the
     * bottom rather than wrapping to the start of a cycle that will not run.
     */
    public int bandTopAt(long t) {
        if (t < 0) t = 0;
        long within = (t >= totalMs()) ? cycleMs : (t % cycleMs);
        double f = (double) within / (double) cycleMs;
        return (int) Math.round(-bandHeight + travel() * f);
    }

    public boolean finishedAt(long t) {
        return t >= totalMs();
    }

    /** Fraction of each cycle that any one row spends lit. */
    public double litFractionPerCycle() {
        return (double) bandHeight / (double) travel();
    }

    /** Total milliseconds any one row spends lit across the whole wash. */
    public long litMsPerRow() {
        return Math.round(litFractionPerCycle() * cycleMs * (double) cycles);
    }

    /** Band travel in panel pixels per second. */
    public double pixelsPerSecond() {
        return travel() * 1000.0 / (double) cycleMs;
    }
}
