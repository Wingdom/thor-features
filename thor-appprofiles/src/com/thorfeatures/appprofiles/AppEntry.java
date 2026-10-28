package com.thorfeatures.appprofiles;

import com.thorfeatures.fanctl.FanMode;

import java.util.Comparator;
import java.util.List;

/**
 * One row of the app list: what to show, and where it sorts.
 *
 * Pure logic -- no Android dependencies -- so the ordering is unit-tested on
 * the host. The Activity builds these from PackageManager and does nothing
 * else with the sort.
 */
public final class AppEntry {

    public final String pkg;
    public final String label;
    public final boolean isGame;
    /** The tier set for this app, or null for no override. */
    public FanMode tier;

    public AppEntry(String pkg, String label, boolean isGame, FanMode tier) {
        this.pkg = pkg;
        this.label = (label == null || label.isEmpty()) ? pkg : label;
        this.isGame = isGame;
        this.tier = tier;
    }

    public boolean hasOverride() { return tier != null && tier.isActive(); }

    /**
     * Configured apps first, then games, then everything else alphabetically.
     *
     * The first group is what makes the screen usable: once a handful of apps
     * are configured, the list the user actually came to check is at the top
     * rather than buried among 150 packages. Games come next because they are
     * the reason the feature exists.
     *
     * Ties break on label then package, case-insensitively, so the order is
     * total and stable -- two apps sharing a label must not swap places
     * between openings.
     */
    public static final Comparator<AppEntry> ORDER = (a, b) -> {
        if (a.hasOverride() != b.hasOverride()) return a.hasOverride() ? -1 : 1;
        if (a.isGame != b.isGame) return a.isGame ? -1 : 1;
        int byLabel = a.label.compareToIgnoreCase(b.label);
        if (byLabel != 0) return byLabel;
        return a.pkg.compareToIgnoreCase(b.pkg);
    };

    /** Sort in place. */
    public static void sort(List<AppEntry> entries) { entries.sort(ORDER); }

    @Override public String toString() {
        return pkg + (hasOverride() ? "=" + tier.wire() : "");
    }
}
