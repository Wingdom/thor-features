package com.thorfeatures.screenpanel;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher icon. Restarts the button daemon if it is not running, then
 * opens the panel on the bottom screen.
 *
 * This is the way back when aynkeyd has died. With the daemon gone the AYN
 * button does nothing at all - no panel, and no long press to bring the bottom
 * screen back - and before this the only fix was `adb shell su -c 'aynkeyd
 * start'`, which is no help away from a host.
 *
 * Why a second activity rather than a LAUNCHER category on PanelActivity:
 *   PanelActivity is the panel, and the panel is bottom-screen only. A
 *   launcher tap starts an activity on the display the launcher is on, which
 *   is the top screen - so putting the category there would put the panel on
 *   the wrong screen, and the panel's whole layout and purpose assume the
 *   3.92" one. This activity instead asks the daemon's own action script to
 *   place it, exactly as a button press does.
 *
 * Why it renders nothing:
 *   There is nothing to show. The work is two root commands and takes well
 *   under a second, and the result - the panel appearing on the bottom screen
 *   - is its own feedback. The window is transparent and undimmed rather than
 *   windowNoDisplay, because a no-display activity has to finish before it
 *   resumes, and finishing first would drop the process to background priority
 *   while the su calls were still in flight.
 *
 * It holds no permissions. Everything it does goes through `su`, the same as
 * PanelActivity and the Quick Settings tiles.
 */
public final class LaunchActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setFinishOnTouchOutside(true);

        final RootClient root = new RootClient(RootClient.EXEC);
        io.execute(() -> {
            final Recovery.Outcome outcome = Recovery.tap(root);
            ui.post(() -> {
                if (Recovery.needsMessage(outcome)) {
                    Toast.makeText(this, messageFor(outcome), Toast.LENGTH_LONG).show();
                }
                finish();
            });
        });
    }

    /**
     * Kept here rather than in Recovery so Recovery stays free of Android
     * types and keeps running on the host.
     */
    private int messageFor(Recovery.Outcome o) {
        switch (o) {
            case RESTARTED_AND_OPENED: return R.string.launch_restarted;
            case DAEMON_FAILED:        return R.string.launch_no_daemon;
            case PANEL_FAILED:         return R.string.launch_no_action;
            default:                   return R.string.launch_restarted;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
