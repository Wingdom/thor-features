package com.thorfeatures.oledwash;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where the settings live, and why they live there.
 *
 * <p>Ordinary {@link SharedPreferences}, written by the app with no root and no
 * permission. The {@code thor-oledwatch} daemon reads this same file as root.
 * That direction matters: the alternative was the app writing the daemon's
 * config under {@code /data/adb}, which would have required {@code su} and cost
 * this app its Magisk Superuser grant. Reading is the half that already has
 * root; writing is the half that must not need it.
 *
 * <p><b>Device-protected storage, deliberately.</b> Default preferences land in
 * credential-encrypted storage, which is unreadable until the first unlock
 * after a boot — so a daemon reading them early would silently see nothing and
 * fall back to defaults, and {@code oledwatch status} would report settings the
 * user never chose. Device-protected storage is readable from boot.
 * {@code AynParts} does the same thing for the same reason; its own preferences
 * sit under {@code /data/user_de/0/}.
 *
 * <p>One consequence worth stating: these settings belong to the app, so
 * uninstalling it forgets them. That is the intended behaviour rather than a
 * loss.
 */
public final class Prefs {

    /** Becomes {@code /data/user_de/0/<pkg>/shared_prefs/oledwash.xml}. */
    public static final String FILE = "oledwash";

    public static final String KEY_AUTO = "auto";
    public static final String KEY_IDLE_MINUTES = "idle_minutes";
    public static final String KEY_BAND = "band";
    public static final String KEY_CYCLE_MS = "cycle_ms";
    public static final String KEY_CYCLES = "cycles";
    public static final String KEY_GRAIN = "grain";
    public static final String KEY_TARGET = "target";

    private Prefs() {}

    public static SharedPreferences of(Context c) {
        Context de = c.createDeviceProtectedStorageContext();
        return de.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
