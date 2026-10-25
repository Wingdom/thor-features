package com.thorfeatures.stickfx;

/**
 * Effect modes exposed by the stickfx CLI.
 *
 * OFF is a real mode here, unlike FanMode.STOCK in the fan tile. `stickfx off`
 * restores the LED state recorded at boot -- it hands the LEDs back to
 * AynParts rather than disabling anything -- so it belongs in the picker.
 *
 * USB names the usb-online trigger explicitly. As of thor-stickfx v0.1.4 that is
 * also what CHARGE binds by default, so the two are the same behaviour and USB
 * stays out of the picker. It remains in this enum so the tile reports honestly
 * if someone selected it from the shell.
 *
 * Why usb-online is the default: measured 2026-10-23, every battery-status
 * trigger goes dark while LineageOS charging control holds the battery at its
 * limit, because the kernel reports "Not charging" there. With a battery-*
 * trigger the tile would show CHARGE active while the LEDs sat dark.
 *
 * Pure logic -- no Android dependencies -- so it is unit-tested on the host.
 */
public enum StickMode {
    OFF("off", "Off"),
    CHARGE("charge", "Charge"),
    BREATHE("breathe", "Breathe"),
    USB("usb", "USB (alias of Charge)");

    private final String wire;
    private final String label;

    StickMode(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    /** Exact argument for stickfx. Fixed set -- never user input. */
    public String wire() { return wire; }

    /** Human label. */
    public String label() { return label; }

    /** True when an effect is running, i.e. the tile should look switched on. */
    public boolean isActive() { return this != OFF; }

    /** The modes the picker offers, in order. USB is excluded by design. */
    public static StickMode[] choices() {
        return new StickMode[] { OFF, CHARGE, BREATHE };
    }

    /** Index within choices(), or -1 if this mode is not offered. */
    public int choiceIndex() {
        StickMode[] c = choices();
        for (int i = 0; i < c.length; i++) if (c[i] == this) return i;
        return -1;
    }

    /**
     * Parse `stickfx get`. Tolerates whitespace, newlines and case. Anything
     * unrecognised -- including null and empty -- reports OFF, matching the
     * CLI, whose own `get` prints "off" when no mode file exists.
     */
    public static StickMode fromString(String s) {
        if (s == null) return OFF;
        String t = s.trim();
        for (StickMode m : values()) {
            if (m.wire.equalsIgnoreCase(t)) return m;
        }
        return OFF;
    }
}
