package com.thorfeatures.oledwash;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns {@code DisplayManager} into the plain facts {@link DisplayChoice} wants.
 *
 * <p>The primary route is {@code DISPLAY_CATEGORY_BUILT_IN_DISPLAYS}, which is
 * public API and says exactly what we need -- the panels bolted to this device,
 * excluding anything plugged into it. It is recent, though, so a platform that
 * does not recognise the category returns an empty array rather than failing.
 * That would quietly wash nothing, so an empty result falls back.
 *
 * <p><b>The fallback is deliberately timid: the default display only.</b> There
 * is no public way to identify a second internal panel on an older platform,
 * and the alternative -- washing every display and hoping none of them is a
 * television -- is worse than washing one panel instead of two.
 */
public final class Displays {

    private Displays() {}

    /** Every display, with built-in membership resolved as well as it can be. */
    public static List<PanelRef> panels(Context ctx) {
        DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        List<PanelRef> out = new ArrayList<>();
        if (dm == null) return out;

        Display[] builtIn = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_BUILT_IN_DISPLAYS);
        int builtInCount = (builtIn == null) ? 0 : builtIn.length;

        if (!DisplayChoice.needsFallback(builtInCount)) {
            for (Display d : builtIn) {
                out.add(ref(d, true));
            }
            return out;
        }

        for (Display d : dm.getDisplays()) {
            out.add(ref(d, d.getDisplayId() == Display.DEFAULT_DISPLAY));
        }
        return out;
    }

    private static PanelRef ref(Display d, boolean builtIn) {
        return new PanelRef(d.getDisplayId(), d.getName(), builtIn, d.getState(), d.isValid());
    }

    /** True when the platform answered the built-in category at all. */
    public static boolean categorySupported(Context ctx) {
        DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) return false;
        Display[] builtIn = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_BUILT_IN_DISPLAYS);
        return builtIn != null && builtIn.length > 0;
    }
}
