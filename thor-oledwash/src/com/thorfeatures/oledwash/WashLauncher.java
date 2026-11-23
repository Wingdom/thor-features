package com.thorfeatures.oledwash;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;

import java.util.List;

/**
 * Starts a wash on each chosen panel.
 *
 * <p>One implementation, used by both entry points -- the Run now button and
 * the idle trigger's {@link AutoWashActivity}. They must agree about which
 * panels get washed, and the way to guarantee that is to have one of them.
 *
 * <p><b>This holds no permissions and calls no shell.</b> It is on the manual
 * path, which works with no root, no Magisk and no module installed; the
 * component's build refuses to package if anything on that path acquires a
 * root dependency.
 */
public final class WashLauncher {

    private WashLauncher() {}

    /**
     * @return the number of panels a wash was started on
     */
    public static int launch(Activity from, double band, long cycleMs,
                             int cycles, int grain, int target) {
        List<Integer> ids = DisplayChoice.pick(Displays.panels(from), target);
        for (int id : ids) {
            Intent i = new Intent(from, WashActivity.class);
            i.putExtra(WashActivity.EXTRA_BAND, band);
            i.putExtra(WashActivity.EXTRA_CYCLE_MS, cycleMs);
            i.putExtra(WashActivity.EXTRA_CYCLES, cycles);
            i.putExtra(WashActivity.EXTRA_GRAIN, grain);
            i.putExtra(WashActivity.EXTRA_DIAGNOSTICS, false);
            // MULTIPLE_TASK so the second panel gets its own instance rather
            // than the system reusing the first one and moving it across.
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(id);
            from.startActivity(i, opts.toBundle());
        }
        return ids.size();
    }
}
