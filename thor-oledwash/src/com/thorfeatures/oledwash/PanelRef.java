package com.thorfeatures.oledwash;

/**
 * A display, reduced to the facts the choice depends on.
 *
 * <p>Exists so {@link DisplayChoice} can be tested without a device. The
 * Android side builds these from {@code DisplayManager}; nothing else about a
 * {@code Display} matters to the decision.
 */
public final class PanelRef {

    /** Mirrors {@code Display.STATE_ON}. */
    public static final int STATE_ON = 2;

    public final int id;
    public final String name;
    /** True when the platform listed it under its built-in displays category. */
    public final boolean builtIn;
    public final int state;
    public final boolean valid;

    public PanelRef(int id, String name, boolean builtIn, int state, boolean valid) {
        this.id = id;
        this.name = (name == null) ? "" : name;
        this.builtIn = builtIn;
        this.state = state;
        this.valid = valid;
    }

    @Override
    public String toString() {
        return "PanelRef{" + id + " \"" + name + "\" builtIn=" + builtIn
                + " state=" + state + " valid=" + valid + "}";
    }
}
