package com.thorfeatures.fanctl;

/**
 * Fan modes exposed by fanctl.
 *
 * The three *tiers* -- QUIET, BALANCED, PERFORMANCE -- are what the picker
 * offers. They differ only in how far the CPU/GPU fan ladder is shifted down;
 * all three apply the same usb-therm fix.
 *
 * STOCK is deliberately NOT a tier. "LineageOS stock" means disabling the
 * Magisk module, not selecting a mode -- offering it here would be offering the
 * loud 43 C usb-therm behaviour the module exists to fix. It remains in this
 * enum only so the tile can honestly report what fanctl says if the module is
 * installed but no tier has been chosen yet.
 *
 * Pure logic -- no Android dependencies -- so it is unit-tested on the host.
 */
public enum FanMode {
    QUIET("quiet", "Quiet"),
    BALANCED("balanced", "Balanced"),
    PERFORMANCE("performance", "Performance"),
    STOCK("default", "Stock");

    private final String wire;
    private final String label;

    FanMode(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    /** Exact argument for fanctl. Fixed set -- never user input. */
    public String wire() { return wire; }

    /** Human label. */
    public String label() { return label; }

    /** True when a tier is applied, i.e. the tile should look switched on. */
    public boolean isActive() { return this != STOCK; }

    /** The modes the picker offers, in order. STOCK is excluded by design. */
    public static FanMode[] tiers() {
        return new FanMode[] { QUIET, BALANCED, PERFORMANCE };
    }

    /** Index of this mode within tiers(), or -1 if it is not a tier. */
    public int tierIndex() {
        FanMode[] t = tiers();
        for (int i = 0; i < t.length; i++) if (t[i] == this) return i;
        return -1;
    }

    /**
     * Parse `fanctl get`. Tolerates whitespace, newlines and case. Anything
     * unrecognised -- including null and empty -- reports STOCK, because
     * claiming a tier is applied when we cannot tell would be the wrong way to
     * be wrong.
     */
    public static FanMode fromString(String s) {
        if (s == null) return STOCK;
        String t = s.trim();
        for (FanMode m : values()) {
            if (m.wire.equalsIgnoreCase(t)) return m;
        }
        return STOCK;
    }
}
