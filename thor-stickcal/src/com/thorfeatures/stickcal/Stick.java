package com.thorfeatures.stickcal;

/** Which stick, and which of the four raw axes belong to it. */
public enum Stick {

    LEFT(0, 1, "Left stick"),
    RIGHT(2, 3, "Right stick");

    /** Index into the four raw stick axes, in the node's order LX LY RX RY. */
    public final int xAxis, yAxis;
    public final String label;

    Stick(int xAxis, int yAxis, String label) {
        this.xAxis = xAxis; this.yAxis = yAxis; this.label = label;
    }
}
