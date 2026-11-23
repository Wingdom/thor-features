package com.thorfeatures.oledwash;

import android.app.Activity;
import android.os.Bundle;

/**
 * The door the idle trigger comes through. Picks the panels, starts a wash on
 * each, and finishes without ever drawing anything.
 *
 * <p><b>Why an Activity and not a BroadcastReceiver.</b> Starting an Activity
 * from the background is restricted, and a receiver has no exemption -- it
 * would need {@code SYSTEM_ALERT_WINDOW} or a visible window. A root
 * {@code am start} is not subject to the restriction at all, and an Activity
 * it starts is foreground, so it may freely start the per-panel washes. The
 * trampoline exists to convert "root wants a wash" into "a foreground
 * component is launching one".
 *
 * <p><b>Why it is not exported.</b> Root can start an internal Activity --
 * verified on hardware 2026-11-23 -- so there is no need to offer this to
 * other apps, and offering it would let any app on the device throw a bright
 * wash over whatever the user was doing. Harmless, but free to avoid.
 *
 * <p>Themed {@code Theme.NoDisplay}, which requires finishing before
 * {@code onResume}; {@link #finish()} in {@link #onCreate} satisfies that.
 */
public final class AutoWashActivity extends Activity {

    public static final String EXTRA_TARGET = "target";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        double band = getIntent().getDoubleExtra(WashActivity.EXTRA_BAND, Params.DEFAULT_BAND);
        long cycleMs = getIntent().getLongExtra(WashActivity.EXTRA_CYCLE_MS, Params.DEFAULT_CYCLE_MS);
        int cycles = getIntent().getIntExtra(WashActivity.EXTRA_CYCLES, Params.DEFAULT_CYCLES);
        int grain = getIntent().getIntExtra(WashActivity.EXTRA_GRAIN, Params.DEFAULT_GRAIN);
        int target = getIntent().getIntExtra(EXTRA_TARGET, Params.DEFAULT_TARGET);

        WashLauncher.launch(this, band, cycleMs, cycles, grain, target);
        finish();
    }
}
