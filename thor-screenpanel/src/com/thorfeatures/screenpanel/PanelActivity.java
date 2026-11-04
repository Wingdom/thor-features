package com.thorfeatures.screenpanel;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Insets;
import android.media.AudioManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        bindTabs();

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
            RootClient.Result r = root.run(PanelCommands.SCREENMOVE + " toggle");
            ui.post(() -> {
                if (r.code == 0) {
                    // A successful move fronts the task, so the panel is no
                    // longer what the user is looking at. Get out of the way.
                    finish();
                } else {
                    // Left open on purpose, and now saying why: screenmove
                    // explains itself ("nothing to move: no visible app on
                    // either screen") and dropping that was what made this
                    // button look dead.
                    explain(r.out);
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
            RootClient.Result r = root.run(SystemToggle.cleanMemoryCommand());
            ui.post(() -> {
                if (r.code == 0) Toast.makeText(this, R.string.cleaned, Toast.LENGTH_SHORT).show();
                else explain(r.out);
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
            RootClient.Result r = root.run(SystemToggle.hapticsSetCommand(target));
            Boolean after = SystemToggle.parseHaptics(
                    root.run(SystemToggle.hapticsGetCommand()).out);
            ui.post(() -> {
                renderHaptics(after);
                if (r.code != 0) explain(r.out);
            });
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

    // --- the telemetry tab ---------------------------------------------------

    /**
     * Resolved paths for the gauges, or null until the tab is first opened.
     *
     * <b>Discovery is lazy on purpose.</b> It is one more root command, and
     * the panel's open cost is a number this project worked hard to get down -
     * from ~1413 ms to ~587 ms, by deleting a duplicate `screenblank status`
     * and removing forks from it. Paying a discovery grep on every open would
     * spend part of that back for a tab most opens never look at. The cost
     * lands on the first tap of "Telemetry" instead, where the user has asked
     * for it and is waiting for something to appear anyway.
     */
    private Telemetry.Layout telemetryLayout;

    /** Which tab is in front. Controls, until the user says otherwise. */
    private boolean telemetrySelected;

    /** Whether the activity is resumed. Half of the polling condition. */
    private boolean resumed;

    /** Whether a tick is currently scheduled. */
    private boolean polling;

    /** Gauge key to its view, so a tick updates rather than rebuilds. */
    private final Map<String, RingGauge> ringViews = new LinkedHashMap<>();
    private final Map<String, View> barViews = new LinkedHashMap<>();

    /** The layer FPS is being read from, and whether that ever worked. */
    private String fpsLayer;
    private boolean fpsAvailable;

    /**
     * The previous tick's readings, kept only so CPU utilisation has a delta.
     *
     * Cleared when polling stops, so a tab reopened after a gap computes its
     * first utilisation from two adjacent ticks rather than across the pause -
     * a delta spanning a minute of being closed would average the whole gap
     * and report it as the current load.
     */
    private Telemetry.Readings previousTick;

    private void bindTabs() {
        findViewById(R.id.tab_controls).setOnClickListener(v -> selectTab(false));
        findViewById(R.id.tab_telemetry).setOnClickListener(v -> selectTab(true));
        selectTab(false);
    }

    /**
     * Switch tabs.
     *
     * Mode is not touched here. It is outside the tabs and stays on screen in
     * both, nearest the AYN button, which is the arrangement the layout
     * comment forbids changing.
     */
    private void selectTab(boolean telemetry) {
        telemetrySelected = telemetry;
        findViewById(R.id.tab_controls).setSelected(!telemetry);
        findViewById(R.id.tab_telemetry).setSelected(telemetry);
        findViewById(R.id.card_bright).setVisibility(telemetry ? View.GONE : View.VISIBLE);
        findViewById(R.id.card_quick).setVisibility(telemetry ? View.GONE : View.VISIBLE);
        findViewById(R.id.card_telemetry).setVisibility(telemetry ? View.VISIBLE : View.GONE);
        updatePolling();
    }

    /**
     * Start or stop ticking to match the current state.
     *
     * Idempotent, because it is called from tab switches and from the
     * lifecycle both, and the two can arrive in either order.
     */
    private void updatePolling() {
        boolean want = Telemetry.shouldPoll(telemetrySelected, resumed);
        if (want == polling) return;
        polling = want;
        if (!want) {
            ui.removeCallbacks(tick);
            previousTick = null;
            return;
        }
        if (telemetryLayout == null) discoverTelemetry();
        else ui.post(tick);
    }

    /** Resolve every path once, then start ticking. */
    private void discoverTelemetry() {
        io.execute(() -> {
            RootClient.Result d = root.run(Telemetry.discoverCommand());
            final Telemetry.Layout l = Telemetry.parseDiscovery(d.out);
            probeFps(l);
            ui.post(() -> {
                telemetryLayout = l;
                buildGauges(l);
                if (polling) ui.post(tick);
            });
        });
    }

    /**
     * One tick: read everything, render, then schedule the next.
     *
     * <b>Rescheduled after the reply, not on a fixed period.</b> A
     * {@code postDelayed} loop that fired every second regardless would queue
     * reads behind each other the moment one took longer than the interval,
     * and the queue would never drain - the panel's brightness slider already
     * avoids the same shape by sending on release instead of per pixel,
     * because "a set per pixel would queue dozens of `su` processes". Here the
     * effect is self-limiting instead: a slow device ticks more slowly rather
     * than falling behind forever.
     */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            final Telemetry.Layout l = telemetryLayout;
            if (!polling || l == null) return;
            io.execute(() -> {
                RootClient.Result r = root.run(Telemetry.tickCommand(l));
                final Telemetry.Readings readings =
                        Telemetry.parseTick(r.out, l, previousTick);
                final Fps.Sample fps = sampleFps();
                ui.post(() -> {
                    previousTick = readings;
                    renderGauges(l, readings, fps);
                    if (polling) ui.postDelayed(tick, Telemetry.TICK_MS);
                });
            });
        }
    };

    /** Build the ring row and the bar row from what discovery found. */
    private void buildGauges(Telemetry.Layout l) {
        LinearLayout rings = findViewById(R.id.ring_row);
        LinearLayout bars  = findViewById(R.id.bar_row);
        rings.removeAllViews();
        bars.removeAllViews();
        ringViews.clear();
        barViews.clear();

        if (l.isEmpty()) {
            findViewById(R.id.gauge_empty).setVisibility(View.VISIBLE);
            rings.setVisibility(View.GONE);
            bars.setVisibility(View.GONE);
            return;
        }
        findViewById(R.id.gauge_empty).setVisibility(View.GONE);
        rings.setVisibility(View.VISIBLE);
        bars.setVisibility(View.VISIBLE);

        // Rings share the width evenly, and each is as tall as the row. The
        // view draws a circle on min(width, height), so a cell wider than it
        // is tall still renders round.
        int ringHeight = Math.round(112 * getResources().getDisplayMetrics().density);
        for (String key : Telemetry.ringKeys(l, fpsAvailable)) {
            RingGauge g = new RingGauge(this);
            g.setLayoutParams(new LinearLayout.LayoutParams(0, ringHeight, 1f));
            rings.addView(g);
            ringViews.put(key, g);
        }

        LayoutInflater inf = LayoutInflater.from(this);
        for (String key : Telemetry.barKeys(l)) {
            View tile = inf.inflate(R.layout.gauge_tile, bars, false);
            ((TextView) tile.findViewById(R.id.tile_label)).setText(Telemetry.gaugeLabel(key));
            ((TextView) tile.findViewById(R.id.tile_value)).setText(Telemetry.NONE);
            ProgressBar bar = tile.findViewById(R.id.tile_bar);
            bar.setProgressTintList(getColorStateList(colorRes(key)));
            bars.addView(tile);
            barViews.put(key, tile);
        }
    }

    /**
     * Each metric's colour.
     *
     * Fixed hues rather than the Material You palette, which is the one place
     * this panel departs from its own theming. The point of these gauges is
     * that a metric is identifiable at a glance from the other screen, and a
     * palette derived from the wallpaper would give four rings four shades of
     * one colour.
     */
    private int colorRes(String key) {
        if (key == null) return R.color.gauge_cpu;
        if (key.startsWith(Telemetry.TEMP)) {
            switch (key.substring(Telemetry.TEMP.length())) {
                case "Board": return R.color.gauge_board;
                case "USB":   return R.color.gauge_usb;
                default:      return R.color.gauge_temp;
            }
        }
        switch (key) {
            case "fps":     return R.color.gauge_fps;
            case "gpu":     return R.color.gauge_gpu;
            case "battery": return R.color.gauge_pwr;
            case "ram":     return R.color.gauge_ram;
            case "fan":     return R.color.gauge_fan;
            default:        return R.color.gauge_cpu;
        }
    }

    private void renderGauges(Telemetry.Layout l, Telemetry.Readings r, Fps.Sample fps) {
        for (Map.Entry<String, RingGauge> e : ringViews.entrySet()) {
            String key = e.getKey();
            e.getValue().set(Telemetry.ringLabel(key),
                             Telemetry.ringBig(key, r, fps),
                             Telemetry.ringSub(key, r, fps),
                             Telemetry.ringFraction(key, r, l, fps),
                             getColor(colorRes(key)));
        }
        for (Map.Entry<String, View> e : barViews.entrySet()) {
            String key = e.getKey();
            View tile = e.getValue();
            ((TextView) tile.findViewById(R.id.tile_value))
                    .setText(Telemetry.gaugeValue(key, r, fps));
            ProgressBar bar = tile.findViewById(R.id.tile_bar);
            int pct = Telemetry.gaugePercent(key, r, fps, l);
            // INVISIBLE rather than GONE: a bar with no reading still has to
            // leave the row the height its neighbours have.
            bar.setVisibility(pct < 0 ? View.INVISIBLE : View.VISIBLE);
            if (pct >= 0) bar.setProgress(pct);
        }
    }

    // --- phase 2: FPS --------------------------------------------------------

    /**
     * Find out whether a frame counter is possible at all, once.
     *
     * Phase 2 was an investigation, and this is the probe that settled it: if
     * nothing answers, {@code fpsAvailable} stays false, no FPS ring is built,
     * and the rest of the tab is unaffected. A ring that always read an em
     * dash would be the "looked alive and did nothing" failure this panel has
     * already been bitten by.
     *
     * Runs on the io thread as part of discovery. It is three `dumpsys` calls
     * at worst and the layer one costs 35 ms, which is why this happens once
     * rather than per tick.
     */
    private void probeFps(Telemetry.Layout l) {
        fpsLayer = resolveFpsLayer();
        fpsAvailable = fpsLayer != null;
    }

    /**
     * The layer carrying the game's frames, or null.
     *
     * Focus is read for the TOP display specifically. The panel is itself a
     * focused window while it is open - on the bottom screen, taking taps - so
     * "the focused package" without a display is the panel, and a counter
     * built on that would measure this card.
     *
     * A focused app owns several layers and only one of them has timestamps;
     * the rest return the refresh period and nothing else. Rather than assert
     * which, the candidates come back ranked and this takes the first that
     * actually yields frames. That is self-correcting if a release reshuffles
     * them, and the extra calls are paid here rather than on every tick.
     */
    private String resolveFpsLayer() {
        String pkg = Fps.parseFocusedPackageOnDisplay(
                root.run(Fps.focusedPackageCommand()).out, ScreenMode.TOP);
        if (pkg == null || pkg.equals(getPackageName())) return null;
        String list = root.run(Fps.listCommand()).out;
        for (String layer : Fps.pickLayers(list, pkg)) {
            if (readFps(layer).fps != Fps.ABSENT) return layer;
        }
        return null;
    }

    private Fps.Sample readFps(String layer) {
        String cmd = Fps.latencyCommand(layer);
        if (cmd == null) return Fps.NO_SAMPLE;
        return Fps.parseLatency(root.run(cmd).out);
    }

    /**
     * This tick's frame rate, re-resolving the layer when it stops answering.
     *
     * The layer name is cached rather than looked up every tick, because the
     * lookup is two `dumpsys` calls and the answer only changes when the user
     * switches app. Going absent is the signal that it has changed, which
     * makes the cache self-healing without a timer: one tick reads nothing,
     * the next has re-resolved.
     */
    private Fps.Sample sampleFps() {
        if (!fpsAvailable) return Fps.NO_SAMPLE;
        if (fpsLayer != null) {
            Fps.Sample s = readFps(fpsLayer);
            if (s.fps != Fps.ABSENT) return s;
        }
        fpsLayer = resolveFpsLayer();
        return fpsLayer == null ? Fps.NO_SAMPLE : readFps(fpsLayer);
    }

    /**
     * Show a failing command's own explanation.
     *
     * These CLIs refuse with a reason -- stickfx will tell you the LED colour
     * is black, screenmove that there is nothing to move -- and the panel used
     * to drop all of it and redraw the unchanged state. A button that explains
     * itself and a dead button looked identical, which is exactly how the
     * stick LEDs were reported broken on 2026-11-01 when nothing was broken.
     */
    private void explain(String out) {
        String msg = CliMessage.summarize(out);
        Toast.makeText(this, msg != null ? msg : getString(R.string.failed),
                msg != null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
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
            RootClient.Result r = root.run(q.setCommand(target));
            String after = root.quickState(q);
            ui.post(() -> {
                renderQuick(itemId, q, after);
                if (r.code != 0) explain(r.out);
            });
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
            final String[] failedOut = new String[1];
            String failed = root.apply(PanelCommands.commandsFor(ops), failedOut);
            ui.post(() -> {
                if (failed != null) {
                    explain(failedOut[0]);
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
    protected void onResume() {
        super.onResume();
        resumed = true;
        updatePolling();
    }

    /**
     * Stop ticking before anything else can.
     *
     * onPause rather than onStop, so the gauges stop reading hardware the
     * moment the panel is no longer in front rather than once it is fully
     * hidden. There is nothing to show in between, and a read issued on the
     * way out would land after the activity had decided to finish.
     */
    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        updatePolling();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        // Drop any scheduled tick first: it would otherwise post work to an
        // executor that is about to be shut down and a shell about to close.
        polling = false;
        ui.removeCallbacks(tick);
        // Close the shell before stopping the executor: a command in flight
        // holds the session's lock, and killing the thread under it would
        // leave a root process with no owner.
        if (session != null) session.close();
        io.shutdownNow();
        super.onDestroy();
    }
}
