package com.thorfeatures.screenpanel;

/**
 * A quick setting in the panel's second row: one control that cycles through a
 * fixed list of states and reports which one is current.
 *
 * Stock's panel had a tab of these - eight tiles, Fan among them - so this is
 * restoring the shape of the original, not inventing one.
 *
 * Why this class exists rather than the panel calling each CLI directly:
 *
 *   The fan and the joystick LEDs already have Quick Settings tiles
 *   (thor-fan-tile, thor-stickfx-tile), and both wrap their CLI the same way:
 *   `<binary> get` prints the current state, `<binary> <wire>` sets it. That
 *   shared contract is the whole reason these can live in the panel at all,
 *   and it is captured here once instead of twice.
 *
 * Why the state lists are NOT duplicated here:
 *
 *   The wire strings live in the tiles' own FanMode and StickMode enums, which
 *   are already host-tested. build.sh compiles those source files directly
 *   into this APK rather than copying them. A copy would be a second source of
 *   truth for the exact strings a root binary is invoked with, and version
 *   skew between an APK and its CLI is a failure this project has already seen
 *   - `screenblank litscreen` appeared and was removed inside one session.
 *
 * Nothing here interpolates user input into a command. Every wire string comes
 * from a fixed enum, and a test asserts no shell metacharacter can reach the
 * argv.
 */
public final class QuickSetting {

    /** Which control this is. Used to pick the icon and to look up state. */
    public enum Kind { FAN, STICK }

    public final Kind kind;
    public final String binary;
    /** Exact CLI arguments, in cycle order. Never user input. */
    public final String[] wires;
    /** Human labels, index-aligned with {@link #wires}. */
    public final String[] labels;
    /** The label shown when the CLI reports a state outside {@link #wires}. */
    public final String inactiveLabel;

    public QuickSetting(Kind kind, String binary, String[] wires, String[] labels,
                        String inactiveLabel) {
        this.kind = kind;
        this.binary = binary;
        this.wires = wires;
        this.labels = labels;
        this.inactiveLabel = inactiveLabel;
    }

    /** The command that reports the current state. */
    public String statusCommand() { return binary + " get"; }

    /** The command that selects {@code wire}. */
    public String setCommand(String wire) { return binary + " " + wire; }

    /**
     * Index of {@code wire} in the cycle, or -1 when it is not one of them.
     *
     * -1 is a real state, not an error: `fanctl get` reports "default" when no
     * tier is applied, and `stickfx get` reports "off". Neither is in the
     * cycle, and both mean "this control is not currently doing anything".
     */
    public int indexOf(String wire) {
        if (wire == null) return -1;
        String t = wire.trim();
        for (int i = 0; i < wires.length; i++) {
            if (wires[i].equalsIgnoreCase(t)) return i;
        }
        return -1;
    }

    /**
     * The wire string a tap selects, given the current one.
     *
     * From outside the cycle - "default", "off", unreadable - a tap enters at
     * the first entry rather than the second. Entering at index 1 would make
     * the first option unreachable by tapping, which is how a cycling control
     * usually goes wrong.
     */
    public String next(String currentWire) {
        int i = indexOf(currentWire);
        if (i < 0) return wires[0];
        return wires[(i + 1) % wires.length];
    }

    /** The label to show for a reported state. */
    public String labelFor(String wire) {
        int i = indexOf(wire);
        return i < 0 ? inactiveLabel : labels[i];
    }

    /** True when the control is in one of its cycle states, i.e. looks "on". */
    public boolean isActive(String wire) { return indexOf(wire) >= 0; }
}
