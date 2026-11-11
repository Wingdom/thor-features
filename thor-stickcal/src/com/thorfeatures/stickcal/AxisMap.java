package com.thorfeatures.stickcal;

import java.util.Set;

/**
 * Which MotionEvent axes carry which stick.
 *
 * Needed only by the verify phase, which reads the framework's own view of
 * the pad rather than sysfs. The built-in pad presents to the kernel as
 * {@code Name="Xbox Wireless Controller"}, and that layout puts the right
 * stick on Z/RZ - but it is discovered from the device's reported motion
 * ranges rather than assumed, because the same unit also exposes
 * {@code layout} as a writable node (AynParts offers a "nintendo" layout) and
 * a remapped pad is a supported state, not a fault.
 *
 * The constants are {@code android.view.MotionEvent.AXIS_*} values, written
 * out so that the choice can be tested on the host. They are public, stable
 * API and have not changed since API 9.
 */
public final class AxisMap {

    public static final int AXIS_X = 0, AXIS_Y = 1;
    public static final int AXIS_Z = 11, AXIS_RX = 12, AXIS_RY = 13, AXIS_RZ = 14;
    public static final int AXIS_HAT_X = 15, AXIS_HAT_Y = 16;

    public final int leftX, leftY, rightX, rightY;
    /** False when the right stick could not be located at all. */
    public final boolean rightFound;

    private AxisMap(int lx, int ly, int rx, int ry, boolean rightFound) {
        leftX = lx; leftY = ly; rightX = rx; rightY = ry; this.rightFound = rightFound;
    }

    /**
     * Pick the axes from those the device reports.
     *
     * The left stick is X/Y on every layout worth supporting. For the right,
     * Z/RZ is the Xbox arrangement this pad uses and is tried first; RX/RY is
     * the other common pairing. The hat is never a stick - a device that
     * reports only a hat has no second analogue stick and must say so rather
     * than verify the d-pad.
     */
    public static AxisMap choose(Set<Integer> available) {
        if (available.contains(AXIS_Z) && available.contains(AXIS_RZ)) {
            return new AxisMap(AXIS_X, AXIS_Y, AXIS_Z, AXIS_RZ, true);
        }
        if (available.contains(AXIS_RX) && available.contains(AXIS_RY)) {
            return new AxisMap(AXIS_X, AXIS_Y, AXIS_RX, AXIS_RY, true);
        }
        return new AxisMap(AXIS_X, AXIS_Y, AXIS_Z, AXIS_RZ, false);
    }

    public int x(Stick s) { return s == Stick.LEFT ? leftX : rightX; }
    public int y(Stick s) { return s == Stick.LEFT ? leftY : rightY; }
}
