package com.thorfeatures.oledwash;

import java.io.File;

/**
 * Whether the companion module is installed.
 *
 * <p><b>This is the only class in the app permitted to name the module</b>, and
 * a test in {@code thor-oledwatch} asserts that. The rule being protected is
 * that the app must not <i>depend</i> on the module — every wash the app draws
 * works with nothing flashed and no root. Knowing whether the module is there
 * is a different thing: without it the Automatic switch would be a control that
 * silently does nothing, which is worse than saying so.
 *
 * <p>No root is needed to answer it. {@code /system/bin} is world-readable, so
 * an ordinary {@code File.exists()} settles it, and the app keeps its property
 * of holding no permissions at all.
 */
public final class ModuleState {

    private static final String BINARY = "/system/bin/oledwatch";

    private ModuleState() {}

    public static boolean moduleInstalled() {
        try {
            return new File(BINARY).exists();
        } catch (SecurityException e) {
            // Nothing on this path should deny us, but a false "not installed"
            // only costs an inaccurate label, where an exception would cost the
            // settings screen.
            return false;
        }
    }
}
