package com.thorfeatures.stickfx;

/**
 * Effect modes exposed by the stickfx CLI.
 *
 * OFF is a real mode here, unlike FanMode.STOCK in the fan tile. `stickfx off`
 * restores the LED state recorded at boot -- it hands the LEDs back to
 * AynParts rather than disabling anything -- so it belongs in the picker.
 *
 * USB is the CLI's verification mode (usb-online trigger, observable without a
 * charging battery). It is deliberately NOT offered: it exists for proving the
 * trigger works, not for daily use. It stays in this enum only so the tile can
 * report honestly if someone selected it from the shell.
 *
 * Pure logic -- no Android dependencies -- so it is unit-tested on the host.
 */
public enum StickMode {
    OFF("off", "Off"),
    CHARGE("charge", "Charge"),
    BREATHE("breathe", "Breathe"),
    USB("usb", "USB test");

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
