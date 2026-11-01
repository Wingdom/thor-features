package com.thorfeatures.screenpanel;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Insets;
import android.media.AudioManager;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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
    /**
     * The panel's root transport: one persistent `su` shell for the life of
     * the activity, instead of a fresh `su` per command.
     *
     * Everything in the panel goes through it, because the alternative is two
     * transports with two failure modes for no benefit. It falls back to
     * one-shot `su` on any failure, so a session that cannot open leaves the
     * panel working exactly as it did before, only slower.
     */
    private RootSession session;
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

        session = new RootSession();
        root = new RootClient(session);

        // The display this panel is actually on is the one to treat as
        // "bottom" when it is not the default display. Resolved, never
        // assumed: display ids are assigned at boot.
        int shownOn = getDisplay() != null ? getDisplay().getDisplayId() : 0;
        if (shownOn != ScreenMode.TOP) bottomDisplay = shownOn;

        bindBrightness();
        bindVolume();
        bindQuick();

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
     * Keep the panel clear of the navigation bar.
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
        final View card = findViewById(R.id.panel_root);
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

    // --- per-screen brightness ----------------------------------------------
    // Each panel gets its own slider. This only does anything once
    // thor-screenbright has removed the bottom panel's leadDisplayAddress:
    // until then the framework drives panel1's backlight from display 0 and
    // a per-display value is stored but never applied. Measured 2026-10-28.

    private void bindBrightness() {
        bindBrightnessRow(R.id.bright_top,    R.string.bright_top,    Brightness.TOP);
        bindBrightnessRow(R.id.bright_bottom, R.string.bright_bottom, bottomDisplay);
    }

    // --- refresh rate, per display -------------------------------------------
    // Sits on each screen's own row rather than in the tile grid: the lever
    // takes a display id, so it belongs with that display's brightness.
    // Width and height come from the Display, never hardcoded - they differ
    // between the two panels.

    private void bindRefreshRate(View row, final int displayId) {
        final TextView low  = row.findViewById(R.id.hz_low);
        final TextView high = row.findViewById(R.id.hz_high);
        low.setOnClickListener(v -> setRate(row, displayId, RefreshRate.LOW));
        high.setOnClickListener(v -> setRate(row, displayId, RefreshRate.HIGH));
    }

    /** Physical size of a display, for the set-mode command. */
    private int[] sizeOf(int displayId) {
        android.hardware.display.DisplayManager dm =
                getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display d = dm.getDisplay(displayId);
        if (d == null) return null;
        android.view.Display.Mode m = d.getMode();
        return new int[] { m.getPhysicalWidth(), m.getPhysicalHeight() };
    }

    private void setRate(final View row, final int displayId, final int rate) {
        renderRate(row, rate);                     // immediate feedback
        io.execute(() -> {
            int[] wh = sizeOf(displayId);
            if (wh == null) return;
            root.run(RefreshRate.setCommand(displayId, wh[0], wh[1], rate));
            int actual = RefreshRate.parse(root.run(RefreshRate.getCommand(displayId)).out);
            ui.post(() -> renderRate(row, actual));
        });
    }

    private void renderRate(View row, int rate) {
        row.findViewById(R.id.hz_low).setSelected(rate == RefreshRate.LOW);
        row.findViewById(R.id.hz_high).setSelected(rate == RefreshRate.HIGH);
    }

    private void refreshRates() {
        io.execute(() -> {
            final int top = RefreshRate.parse(root.run(RefreshRate.getCommand(Brightness.TOP)).out);
            final int bot = RefreshRate.parse(root.run(RefreshRate.getCommand(bottomDisplay)).out);
            ui.post(() -> {
                renderRate(findViewById(R.id.bright_top), top);
                renderRate(findViewById(R.id.bright_bottom), bot);
            });
        });
    }

    // --- volume ---------------------------------------------------------------
    // The one control here that needs no root at all: AudioManager is an
    // ordinary app API. There is a single slider because `dumpsys audio`
    // reports a single output device - the two screens share one speaker.

    private AudioManager audio;

    private void bindVolume() {
        audio = getSystemService(AudioManager.class);
        View row = findViewById(R.id.row_volume);
        ((TextView) row.findViewById(R.id.bright_label)).setText(R.string.row_volume);
        ((ImageView) row.findViewById(R.id.bright_icon)).setImageResource(R.drawable.ic_volume);
        row.findViewById(R.id.hz_group).setVisibility(View.GONE);

        final int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        SeekBar bar = row.findViewById(R.id.bright_bar);
        bar.setMax(Math.max(max, 1));
        bar.setProgress(SystemToggle.clampVolume(
                audio.getStreamVolume(AudioManager.STREAM_MUSIC), max));
        tintVolumeIcon(row, bar.getProgress(), max);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                // Volume is cheap and local - no process, no root - so unlike
                // brightness this one can follow the finger.
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                        SystemToggle.clampVolume(progress, max), 0);
                tintVolumeIcon(row, progress, max);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
    }

    private void tintVolumeIcon(View row, int index, int max) {
        ImageView icon = row.findViewById(R.id.bright_icon);
        boolean onFill = Brightness.iconOnFill(
                max > 0 ? Math.round(index * 100f / max) : 0);
        icon.setImageTintList(getColorStateList(
                onFill ? R.color.bright_icon_on_fill : R.color.bright_icon_on_track));
    }

    private void bindBrightnessRow(int rowId, int labelRes, int displayId) {
        final View row = findViewById(rowId);
        ((TextView) row.findViewById(R.id.bright_label)).setText(labelRes);
        bindRefreshRate(row, displayId);
        SeekBar bar = row.findViewById(R.id.bright_bar);

        bar.setMax(Brightness.STEPS);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                // Keep the icon readable as the fill sweeps under it.
                tintBrightnessIcon(row, progress);
                // Otherwise nothing to do while dragging. The track fill is the
                // feedback, exactly as in the notification shade, and the
                // command is sent on release: a set per pixel would queue
                // dozens of `su` processes, and process spawns on this device
                // are expensive enough to have cost a session before.
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                final float target = Brightness.fromSlider(sb.getProgress());
                io.execute(() -> {
                    root.run(Brightness.setCommand(displayId, target));
                    // Read back rather than assume: the framework clamps to its
                    // own configured range and may not land on what was asked.
                    float actual = Brightness.parse(
                            root.run(Brightness.getCommand(displayId)).out);
                    if (actual >= 0f) {
                        ui.post(() -> renderBrightness(rowId, actual));
                    }
                });
            }
        });
    }

    private void renderBrightness(int rowId, float v) {
        View row = findViewById(rowId);
        SeekBar bar = row.findViewById(R.id.bright_bar);
        if (v < 0f) {
            // Unknown is not zero. Dim the whole row and disable the slider
            // rather than parking the thumb at the bottom, which would look
            // like a real setting and invite dragging a dead control.
            bar.setEnabled(false);
            row.setAlpha(0.4f);
            return;
        }
        bar.setEnabled(true);
        row.setAlpha(1f);
        int pos = Brightness.toSlider(v);
        bar.setProgress(pos);
        tintBrightnessIcon(row, pos);
    }

    /** Dark icon on the accent fill, light icon on the dark track. */
    private void tintBrightnessIcon(View row, int sliderPos) {
        ImageView icon = row.findViewById(R.id.bright_icon);
        icon.setImageTintList(getColorStateList(Brightness.iconOnFill(sliderPos)
                ? R.color.bright_icon_on_fill
                : R.color.bright_icon_on_track));
    }

    private void refreshBrightness() {
        io.execute(() -> {
            float top = Brightness.parse(root.run(Brightness.getCommand(Brightness.TOP)).out);
            float bot = Brightness.parse(root.run(Brightness.getCommand(bottomDisplay)).out);
            ui.post(() -> {
                renderBrightness(R.id.bright_top, top);
                renderBrightness(R.id.bright_bottom, bot);
            });
        });
    }

    // --- quick settings ------------------------------------------------------
    // The fan and the joystick LEDs already have Quick Settings tiles. The
    // panel offers the same controls, reusing those tiles' mode enums rather
    // than copying their state lists - see QuickSettings.

    private final QuickSetting fanSetting   = QuickSettings.fan();
    private final QuickSetting stickSetting = QuickSettings.stick();

    private void bindQuick() {
        bindCycle(R.id.quick_fan,   R.drawable.ic_quick_fan,   R.string.quick_fan,   fanSetting);
        bindCycle(R.id.quick_stick, R.drawable.ic_quick_stick, R.string.quick_stick, stickSetting);
        bindCleanMemory();
        bindHaptics();

        // Move-app is an action, not a cycle: there is no state to display and
        // nothing to step through, so it gets a hint line instead.
        //
        // This is a straight pass-through of `screenmove toggle`, exactly what
        // thor-screenmove-tile does. The panel deliberately does NOT pre-check
        // whether anything is movable or interpret why a move failed: that is
        // the module's business, the module is being tuned independently, and
        // a copy of its rules here would be a second opinion that goes stale.
        LinearLayout move = findViewById(R.id.quick_move);
        ((ImageView) move.findViewById(R.id.icon)).setImageResource(R.drawable.ic_quick_move);
        ((TextView)  move.findViewById(R.id.label)).setText(R.string.quick_move);
        ((TextView)  move.findViewById(R.id.state)).setText(R.string.quick_move_hint);
        move.setOnClickListener(v -> io.execute(() -> {
            boolean ok = root.run(PanelCommands.SCREENMOVE + " toggle").code == 0;
            ui.post(() -> {
                if (ok) {
                    // A successful move fronts the task, so the panel is no
                    // longer what the user is looking at. Get out of the way.
                    finish();
                } else {
                    // Left open on purpose: closing the panel after nothing
                    // visibly happened reads as the tap being ignored.
                    ((TextView) move.findViewById(R.id.state)).setText(R.string.quick_move_none);
                }
            });
        }));
    }

    // Stock's "Clean Memory" tile. One shot, no state to show.
    private void bindCleanMemory() {
        LinearLayout item = findViewById(R.id.quick_clean);
        ((ImageView) item.findViewById(R.id.icon)).setImageResource(R.drawable.ic_quick_clean);
        ((TextView)  item.findViewById(R.id.label)).setText(R.string.quick_clean);
        ((TextView)  item.findViewById(R.id.state)).setText("");
        item.setOnClickListener(v -> io.execute(() -> {
            boolean ok = root.run(SystemToggle.cleanMemoryCommand()).code == 0;
            ui.post(() -> {
                if (ok) Toast.makeText(this, R.string.cleaned, Toast.LENGTH_SHORT).show();
            });
        }));
    }

    // Haptics. Stock drove `vibrate_on`, which does not exist on this build -
    // it reads null. `haptic_feedback_enabled` is the live key.
    private void bindHaptics() {
        LinearLayout item = findViewById(R.id.quick_haptics);
        ((ImageView) item.findViewById(R.id.icon)).setImageResource(R.drawable.ic_quick_haptics);
        ((TextView)  item.findViewById(R.id.label)).setText(R.string.quick_haptics);
        item.setOnClickListener(v -> io.execute(() -> {
            Boolean now = SystemToggle.parseHaptics(
                    root.run(SystemToggle.hapticsGetCommand()).out);
            // Unknown is not off: default to turning them ON rather than
            // silently disabling haptics for someone who had them enabled.
            boolean target = (now == null) || !now;
            root.run(SystemToggle.hapticsSetCommand(target));
            Boolean after = SystemToggle.parseHaptics(
                    root.run(SystemToggle.hapticsGetCommand()).out);
            ui.post(() -> renderHaptics(after));
        }));
    }

    private void renderHaptics(Boolean on) {
        LinearLayout item = findViewById(R.id.quick_haptics);
        TextView state = item.findViewById(R.id.state);
        View icon = item.findViewById(R.id.icon);
        if (on == null) {
            state.setText(R.string.quick_unavailable);
            icon.setSelected(false);
            return;
        }
        state.setText(on ? R.string.haptics_on : R.string.haptics_off);
        icon.setSelected(on);
    }

    private void bindCycle(int itemId, int iconRes, int labelRes, QuickSetting q) {
        LinearLayout item = findViewById(itemId);
        ((ImageView) item.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView)  item.findViewById(R.id.label)).setText(labelRes);
        item.setOnClickListener(v -> io.execute(() -> {
            // Read the state immediately before acting rather than trusting
            // what is on screen: the QS tile for this same control can change
            // it while the panel is open.
            String current = root.quickState(q);
            String target = q.next(current);
            root.quickSet(q, target);
            String after = root.quickState(q);
            ui.post(() -> renderQuick(itemId, q, after));
        }));
    }

    private void renderQuick(int itemId, QuickSetting q, String wire) {
        LinearLayout item = findViewById(itemId);
        TextView state = item.findViewById(R.id.state);
        View icon = item.findViewById(R.id.icon);
        if (wire == null) {
            // The CLI did not answer - its module is not installed. Say so
            // rather than showing a control that silently does nothing.
            state.setText(R.string.quick_unavailable);
            icon.setSelected(false);
            item.setEnabled(false);
            item.setClickable(false);
            return;
        }
        state.setText(q.labelFor(wire));
        icon.setSelected(q.isActive(wire));
    }

    private void refreshQuick() {
        io.execute(() -> {
            String fanState = root.quickState(fanSetting);
            String stickState = root.quickState(stickSetting);
            final Boolean haptics = SystemToggle.parseHaptics(
                    root.run(SystemToggle.hapticsGetCommand()).out);
            ui.post(() -> {
                renderQuick(R.id.quick_fan, fanSetting, fanState);
                renderQuick(R.id.quick_stick, stickSetting, stickState);
                renderHaptics(haptics);
            });
        });
    }

    /** Read the device's current state and mark the matching option. */
    private void refresh() {
        io.execute(() -> {
            // One call, not two: availability and state come back together.
            RootClient.BlankState bs = root.blankState();
            if (!bs.available) {
                ui.post(() -> {
                    Toast.makeText(this, R.string.no_root, Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }
            Mode m = ScreenMode.current(bs.topDark, bs.bottomDark);
            ui.post(() -> { currentMode = m; markSelected(m); });
        });
        refreshQuick();
        refreshBrightness();
        refreshRates();
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
            RootClient.BlankState bs = root.blankState();
            boolean[] movable = root.movableState();
            List<ScreenMode.Op> ops = PanelCommands.dropNoOpMoves(
                    ScreenMode.plan(target, bs.topDark, bs.bottomDark, bottomDisplay),
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
        // Close the shell before stopping the executor: a command in flight
        // holds the session's lock, and killing the thread under it would
        // leave a root process with no owner.
        if (session != null) session.close();
        io.shutdownNow();
        super.onDestroy();
    }
}
