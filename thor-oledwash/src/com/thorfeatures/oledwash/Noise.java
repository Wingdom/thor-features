package com.thorfeatures.oledwash;

/**
 * The random fill that makes up the band.
 *
 * <p>Each channel of each pixel is drawn independently, so every subpixel gets
 * its own drive level and its own history. That is the entire mechanism: a
 * solid white bar would exercise every pixel identically and is itself a static
 * pattern being dragged across the screen.
 *
 * <p>Levels are drawn from {@code [floor, 255]} rather than {@code [0, 255]}.
 * The band in AYN's own implementation photographs at a mean luminance around
 * 222/255, which full-range noise could not produce -- it would average near
 * 128. A high floor keeps the panel driven hard, which is the point of the
 * exercise, while leaving enough variation that no subpixel sits at a constant
 * level.
 *
 * <p>xorshift64* rather than {@link java.util.Random}: this runs per pixel per
 * frame at up to 120 Hz, and {@code Random.nextInt} is synchronized and does
 * considerably more work than is needed to decide how bright a pixel is for
 * 8 milliseconds. Seeded explicitly so the host tests are deterministic.
 */
public final class Noise {

    private long state;

    public Noise(long seed) {
        // xorshift is a fixed point at zero, so a zero seed would produce a
        // constant black band -- exactly the failure this class exists to avoid.
        this.state = (seed == 0L) ? 0x9E3779B97F4A7C15L : seed;
    }

    /** xorshift64*. */
    public long next() {
        long x = state;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        state = x;
        return x * 0x2545F4914F6CDD1DL;
    }

    /**
     * One opaque ARGB pixel with R, G and B drawn independently.
     *
     * @param floor lowest level any channel may take, 0..255
     */
    public int nextPixel(int floor) {
        if (floor < 0 || floor > 255) throw new IllegalArgumentException("floor: " + floor);
        final int span = 256 - floor;
        final long r = next();
        // Three disjoint slices of one 64-bit draw. Taking separate draws per
        // channel would triple the work for no visible difference.
        int rr = floor + (int) ((r >>> 40) % span);
        int gg = floor + (int) ((r >>> 21) % span);
        int bb = floor + (int) ((r >>> 2) % span);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    /** Fill the first {@code count} entries of {@code buf} with fresh pixels. */
    public void fill(int[] buf, int count, int floor) {
        if (count < 0 || count > buf.length) throw new IllegalArgumentException("count: " + count);
        for (int i = 0; i < count; i++) {
            buf[i] = nextPixel(floor);
        }
    }
}
