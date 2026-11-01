package com.thorfeatures.screenpanel;

import com.thorfeatures.fanctl.FanMode;
import com.thorfeatures.stickfx.StickMode;

/**
 * Builds the panel's quick settings from the Quick Settings tiles' own mode
 * enums.
 *
 * The imports above are the point. {@link FanMode} and {@link StickMode} live
 * in thor-fan-tile and thor-stickfx-tile; build.sh compiles those source files
 * into this APK rather than copying them, so the exact strings passed to a
 * root binary have exactly one definition. If a tier is renamed or removed in
 * a tile, this panel stops compiling instead of silently invoking an argument
 * its CLI no longer accepts.
 *
 * Both enums deliberately exclude one value from their picker - FanMode.STOCK
 * ("default") and StickMode.OFF - because those mean "not doing anything"
 * rather than being a choice. The panel treats them the same way: they are the
 * inactive label, reachable by the CLI reporting them, not by tapping.
 */
public final class QuickSettings {

    private QuickSettings() {}

    public static QuickSetting fan() {
        FanMode[] tiers = FanMode.tiers();
        String[] wires = new String[tiers.length];
        String[] labels = new String[tiers.length];
        for (int i = 0; i < tiers.length; i++) {
            wires[i] = tiers[i].wire();
            labels[i] = tiers[i].label();
        }
        // "default" is excluded from tiers(), so every entry here is a real
        // tier and none of them means "off".
        return new QuickSetting(QuickSetting.Kind.FAN, "/system/bin/fanctl",
                wires, labels, FanMode.STOCK.label(), null);
    }

    public static QuickSetting stick() {
        StickMode[] choices = StickMode.choices();
        String[] wires = new String[choices.length];
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            wires[i] = choices[i].wire();
            labels[i] = choices[i].label();
        }
        // OFF is inside choices() - it is a thing you pick - so it has to be
        // named as the inactive state or the tile lights up while saying Off.
        return new QuickSetting(QuickSetting.Kind.STICK, "/system/bin/stickfx",
                wires, labels, StickMode.OFF.label(), StickMode.OFF.wire());
    }
}
