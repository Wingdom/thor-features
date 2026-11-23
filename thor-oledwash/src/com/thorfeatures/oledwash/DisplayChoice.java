package com.thorfeatures.oledwash;

import java.util.ArrayList;
import java.util.List;

/**
 * Which panels get washed.
 *
 * <p>Two rules, and both earn their place:
 *
 * <ol>
 *   <li><b>Built-in only.</b> Washing an HDMI or DisplayPort output would put a
 *       bright scrolling band on somebody's television, which is at best rude
 *       and at worst looks like a fault. An external panel is also not ours to
 *       make decisions about -- it has its own idea of what to do when idle.
 *   <li><b>Lit only.</b> A panel already off needs no protection, and starting
 *       an Activity on it would turn it on. The whole design says never wake
 *       anything.
 * </ol>
 *
 * <p>The ordering is by display id, so the primary panel comes first and the
 * caller can treat index 0 as "the main screen" without a second lookup.
 */
public final class DisplayChoice {

    private DisplayChoice() {}

    /**
     * @param panels every display the platform reported
     * @param target one of {@link Params#TARGET_ALL}, {@code TARGET_PRIMARY},
     *               {@code TARGET_SECONDARY}
     * @return the ids to wash, lowest first; possibly empty
     */
    public static List<Integer> pick(List<PanelRef> panels, int target) {
        List<PanelRef> eligible = new ArrayList<>();
        for (PanelRef p : panels) {
            if (p == null) continue;
            if (!p.valid) continue;
            if (!p.builtIn) continue;
            if (p.state != PanelRef.STATE_ON) continue;
            eligible.add(p);
        }
        eligible.sort((a, b) -> Integer.compare(a.id, b.id));

        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < eligible.size(); i++) {
            boolean take;
            switch (target) {
                case Params.TARGET_PRIMARY:   take = (i == 0); break;
                case Params.TARGET_SECONDARY: take = (i > 0);  break;
                default:                      take = true;     break;
            }
            if (take) out.add(eligible.get(i).id);
        }
        return out;
    }

    /**
     * True when a platform category lookup came back empty and the caller
     * should fall back to listing every display and marking them built-in by
     * other means.
     *
     * <p>{@code DISPLAY_CATEGORY_BUILT_IN_DISPLAYS} is recent. On a platform
     * that does not know the category, {@code getDisplays(category)} returns an
     * empty array rather than failing -- which would silently wash nothing.
     * A fallback is cheaper than a minimum-SDK bump that would exclude the
     * older AYN handhelds this is meant to be portable to.
     */
    public static boolean needsFallback(int categoryResultCount) {
        return categoryResultCount <= 0;
    }
}
