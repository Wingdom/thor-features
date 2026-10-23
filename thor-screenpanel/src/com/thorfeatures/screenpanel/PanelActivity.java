package com.thorfeatures.screenpanel;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.thorfeatures.screenpanel.ScreenMode.Mode;

/**
 * The AYN button's panel - the "Mode" tab of stock's pop-up, recreated.
 *
 * Stock put this on the bottom screen and opened it from a short press of the
 * AYN button. Here the short press is delivered by aynkeyd (thor-aynkey),
 * which runs as root on the evdev node, because an app only receives key
 * events while focused and nothing in LineageOS binds KEY_F24.
 *
 * All device work happens off the main thread: every operation is a `su`
 * process, and the first one in a session blocks on the Magisk prompt.
 */
public final class PanelActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private RootClient root;
    private Mode currentMode = Mode.DUAL;
    private int bottomDisplay = 1;

    private View dualItem, topItem, bottomItem;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.panel);

        // Sit above the app behind it without taking its place, and let a tap
        // outside dismiss - the panel is a transient control, not a screen.
        getWindow().setGravity(android.view.Gravity.BOTTOM);
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                              WindowManager.LayoutParams.WRAP_CONTENT);

        insetCardAboveSystemBars();

        root = new RootClient(RootClient.EXEC);

        // The display this panel is actually on is the one to treat as
        // "bottom" when it is not the default display. Resolved, never
        // assumed: display ids are assigned at boot.
        int shownOn = getDisplay() != null ? getDisplay().getDisplayId() : 0;
        if (shownOn != ScreenMode.TOP) bottomDisplay = shownOn;

        dualItem   = bind(R.id.mode_dual,   R.drawable.ic_mode_dual,   R.string.mode_dual,   Mode.DUAL);
        topItem    = bind(R.id.mode_top,    R.drawable.ic_mode_top,    R.string.mode_top,    Mode.TOP_ONLY);
        bottomItem = bind(R.id.mode_bottom, R.drawable.ic_mode_bottom, R.string.mode_bottom, Mode.BOTTOM_ONLY);

        refresh();
    }

    /**
     * A second short press of the AYN button while the panel is showing closes
     * it - the button toggles the panel rather than re-opening it.
     *
     * This is handled here rather than in the button's action script because
     * the activity already knows it is showing. The alternative was for the
     * script to parse `dumpsys activity activities` to decide whether to open
     * or close, which is both slower and the exact read that
     * is easiest to get wrong: task lines repeat across
     * sections, so a flat grep reports an activity on both displays at once.
     *
     * android:launchMode="singleTop" is what routes the second launch here
     * instead of creating a second instance.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /**
     * Keep the card clear of the navigation bar.
     *
     * The panel is bottom-anchored and the navigation bar is drawn above
     * ordinary windows, so without this the back/home/recents buttons land
     * directly on top of the three mode labels - seen on the bottom screen
     * 2026-10-22. Margin rather than padding, so the card's background stops
     * above the bar instead of running underneath it.
     *
     * Read from the live insets rather than a fixed dimension: the two screens
     * have different bar geometry, and gesture navigation has a much smaller
     * inset than three-button navigation.
     */
    private void insetCardAboveSystemBars() {
        final View card = findViewById(R.id.card);
        final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        final int baseBottom = lp.bottomMargin;
        final int baseLeft   = lp.leftMargin;
        final int baseRight  = lp.rightMargin;

        card.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            p.bottomMargin = baseBottom + bars.bottom;
            p.leftMargin   = baseLeft   + bars.left;
            p.rightMargin  = baseRight  + bars.right;
            v.setLayoutParams(p);
            return insets;
        });
        card.requestApplyInsets();
    }

    private View bind(int itemId, int iconRes, int labelRes, Mode mode) {
        LinearLayout item = findViewById(itemId);
        ((ImageView) item.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView)  item.findViewById(R.id.label)).setText(labelRes);
        item.setOnClickListener(v -> select(mode));
        return item;
    }

    /** Read the device's current state and mark the matching option. */
    private void refresh() {
        io.execute(() -> {
            if (!root.available()) {
                ui.post(() -> {
                    Toast.makeText(this, R.string.no_root, Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }
            boolean[] dark = root.blankState();
            Mode m = ScreenMode.current(dark[0], dark[1]);
            ui.post(() -> { currentMode = m; markSelected(m); });
        });
    }

    private void markSelected(Mode m) {
        setSelected(dualItem,   m == Mode.DUAL);
        setSelected(topItem,    m == Mode.TOP_ONLY);
        setSelected(bottomItem, m == Mode.BOTTOM_ONLY);
    }

    private void setSelected(View item, boolean on) {
        item.findViewById(R.id.icon).setSelected(on);
    }

    /**
     * Apply a mode.
     *
     * The plan is computed from the state read back off the device, not from
     * what this activity last displayed - the AYN button's long press can
     * change the mode while the panel is open, and a plan built from a stale
     * view of the world would blank the wrong screen.
     */
    private void select(Mode target) {
        markSelected(target);                     // immediate feedback
        io.execute(() -> {
            boolean[] dark = root.blankState();
            boolean[] movable = root.movableState();
            List<ScreenMode.Op> ops = PanelCommands.dropNoOpMoves(
                    ScreenMode.plan(target, dark[0], dark[1], bottomDisplay),
                    movable[0], movable[1]);
            String failed = root.apply(PanelCommands.commandsFor(ops));
            ui.post(() -> {
                if (failed != null) {
                    Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show();
                    refresh();                    // show what actually happened
                    return;
                }
                currentMode = target;
                // Picking a single-screen mode dismisses the panel: on
                // TOP_ONLY the panel's own screen is the one being powered
                // down, so leaving it up would draw it on a dark panel.
                if (target != Mode.DUAL) finish();
            });
        });
    }

    /**
     * The panel never lingers invisibly.
     *
     * It is a transient control, so there is no state worth keeping alive -
     * and an instance that survives out of sight is actively harmful here:
     * this window is translucent over a transparent background, so a panel
     * that is "open" with nothing behind it paints the bottom screen black.
     * That is what a restored task did across a reboot on 2026-10-22, and it
     * read as the bottom screen having failed to turn on.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
