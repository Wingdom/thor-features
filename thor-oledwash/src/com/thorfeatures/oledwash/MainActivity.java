package com.thorfeatures.oledwash;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * The control panel: what gets drawn, and whether it runs by itself.
 *
 * <p>Every value here is written to {@link Prefs}, which the
 * {@code thor-oledwatch} daemon reads as root. The app never writes the
 * daemon's own directory and never calls {@code su} — that split is what keeps
 * this app free of permissions, and the component's build asserts it.
 *
 * <p>With no module installed the settings still save; the screen says plainly
 * that nothing is reading them, rather than offering a switch that silently
 * does nothing.
 */
public final class MainActivity extends Activity {

    private boolean auto = Params.DEFAULT_AUTO;
    private int idleMinutes = Params.DEFAULT_IDLE_MINUTES;
    private long cycleMs = Params.DEFAULT_CYCLE_MS;
    private double band = Params.DEFAULT_BAND;
    private int cycles = Params.DEFAULT_CYCLES;
    private int grain = Params.DEFAULT_GRAIN;
    private int target = Params.DEFAULT_TARGET;

    private LinearLayout content;
    private TextView summary;
    private TextView status;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.main);
        content = findViewById(R.id.content);
        applySystemBarInsets(findViewById(R.id.root));
        load();
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /**
     * Keep the content clear of the system bars.
     *
     * <p>An app targeting SDK 35 or later is laid out edge to edge, so without
     * this the scroll view starts at the very top of the window and its first
     * paragraph is drawn underneath the status bar -- unreadable, and not
     * scrollable into view, because the scroll position is already 0.
     *
     * <p>The display cutout is included: this is a handheld, and a wash
     * settings screen is not worth losing a line of text to a camera hole on
     * some other AYN device.
     */
    private void applySystemBarInsets(View root) {
        if (root == null) return;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    // ------------------------------------------------------------- storage --

    private void load() {
        SharedPreferences p = Prefs.of(this);
        auto = p.getBoolean(Prefs.KEY_AUTO, Params.DEFAULT_AUTO);
        idleMinutes = p.getInt(Prefs.KEY_IDLE_MINUTES, Params.DEFAULT_IDLE_MINUTES);
        cycleMs = p.getLong(Prefs.KEY_CYCLE_MS, Params.DEFAULT_CYCLE_MS);
        band = p.getFloat(Prefs.KEY_BAND, (float) Params.DEFAULT_BAND);
        cycles = p.getInt(Prefs.KEY_CYCLES, Params.DEFAULT_CYCLES);
        grain = p.getInt(Prefs.KEY_GRAIN, Params.DEFAULT_GRAIN);
        target = p.getInt(Prefs.KEY_TARGET, Params.DEFAULT_TARGET);

        // Snap anything unexpected onto an offered value, so a hand-edited or
        // stale preference cannot leave a picker row with nothing selected.
        idleMinutes = Params.nearest(Params.IDLE_MINUTES, idleMinutes);
        cycleMs = Params.nearest(Params.CYCLE_MS, cycleMs);
        band = Params.nearest(Params.BAND, band);
        cycles = Params.nearest(Params.CYCLES, cycles);
        grain = Params.nearest(Params.GRAIN, grain);
        if (target < Params.TARGET_ALL || target > Params.TARGET_SECONDARY) {
            target = Params.DEFAULT_TARGET;
        }

        // Write the defaults out once, on first open, so the file exists.
        // Nothing depends on it -- the daemon falls back to the same numbers --
        // but "no settings file" and "settings that happen to be the defaults"
        // look identical from the daemon's side, and only one of them means the
        // user has ever seen this screen. `oledwatch status` can then say which.
        if (!p.contains(Prefs.KEY_AUTO)) save();
    }

    /**
     * Written with {@code commit}, not {@code apply}.
     *
     * <p>{@code apply} returns before the file is on disk. The daemon may read
     * it moments later, and a setting that had visibly changed on screen but
     * not yet in the file is the kind of disagreement that is very hard to see
     * afterwards. This happens on a tap, so the cost is irrelevant.
     */
    private void save() {
        Prefs.of(this).edit()
                .putBoolean(Prefs.KEY_AUTO, auto)
                .putInt(Prefs.KEY_IDLE_MINUTES, idleMinutes)
                .putLong(Prefs.KEY_CYCLE_MS, cycleMs)
                .putFloat(Prefs.KEY_BAND, (float) band)
                .putInt(Prefs.KEY_CYCLES, cycles)
                .putInt(Prefs.KEY_GRAIN, grain)
                .putInt(Prefs.KEY_TARGET, target)
                .commit();
    }

    // --------------------------------------------------------------- build --

    private void build() {
        content.removeAllViews();

        content.addView(heading(getString(R.string.app_name)));
        content.addView(body(getString(R.string.intro)));

        // --- run by itself ---
        content.addView(subheading(getString(R.string.section_auto)));
        content.addView(body(getString(ModuleState.moduleInstalled()
                ? R.string.auto_ready : R.string.auto_no_module)));

        content.addView(pickerRow(getString(R.string.label_auto),
                new String[]{ getString(R.string.auto_off), getString(R.string.auto_on) },
                auto ? 1 : 0,
                i -> { auto = (i == 1); changed(); }));

        content.addView(pickerRow(getString(R.string.label_idle),
                labels(Params.IDLE_MINUTES, v -> getString(R.string.idle_suffix, v)),
                Params.indexOf(Params.IDLE_MINUTES, idleMinutes),
                i -> { idleMinutes = Params.IDLE_MINUTES[i]; changed(); }));

        // --- what gets drawn ---
        content.addView(pickerRow(getString(R.string.label_speed),
                labels(Params.CYCLE_MS, v -> String.format(Locale.US, "%.1fs", v / 1000.0)),
                Params.indexOf(Params.CYCLE_MS, cycleMs),
                i -> { cycleMs = Params.CYCLE_MS[i]; changed(); }));

        content.addView(pickerRow(getString(R.string.label_band),
                labels(Params.BAND, v -> String.format(Locale.US, "%.0f%%", v * 100.0)),
                Params.indexOf(Params.BAND, band),
                i -> { band = Params.BAND[i]; changed(); }));

        content.addView(pickerRow(getString(R.string.label_cycles),
                labels(Params.CYCLES, v -> String.valueOf(v)),
                Params.indexOf(Params.CYCLES, cycles),
                i -> { cycles = Params.CYCLES[i]; changed(); }));

        content.addView(pickerRow(getString(R.string.label_grain),
                labels(Params.GRAIN, v -> String.valueOf(v)),
                Params.indexOf(Params.GRAIN, grain),
                i -> { grain = Params.GRAIN[i]; changed(); }));

        content.addView(pickerRow(getString(R.string.label_screens),
                new String[]{ getString(R.string.screens_all),
                              getString(R.string.screens_primary),
                              getString(R.string.screens_secondary) },
                target,
                i -> { target = i; changed(); }));

        summary = body("");
        summary.setTypeface(Typeface.MONOSPACE);
        content.addView(summary);

        Button run = new Button(this);
        run.setText(R.string.run_now);
        run.setAllCaps(false);
        run.setOnClickListener(v -> launch());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(16);
        run.setLayoutParams(rp);
        content.addView(run);

        status = body("");
        content.addView(status);

        refresh();
    }

    /** Persist, then redraw so the selected button in each row moves. */
    private void changed() {
        save();
        build();
    }

    private void refresh() {
        WashPlan plan = WashPlan.of(panelHeight(), band, cycleMs, cycles);
        summary.setText(Summary.describe(plan, grain));

        List<PanelRef> panels = Displays.panels(this);
        List<Integer> ids = DisplayChoice.pick(panels, target);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.status_panels, ids.size(), panels.size()));
        for (PanelRef p : panels) {
            sb.append('\n').append("  ").append(p.id).append("  ").append(p.name)
              .append(p.builtIn ? "  built-in" : "  external")
              .append(p.state == PanelRef.STATE_ON ? "  on" : "  off");
        }
        status.setText(sb.toString());
    }

    /** The primary panel's real height, used only for the preview figures. */
    private int panelHeight() {
        Display d = getDisplay();
        if (d != null && d.getMode() != null) {
            int ph = d.getMode().getPhysicalHeight();
            if (ph > 0) return ph;
        }
        int h = getResources().getDisplayMetrics().heightPixels;
        return (h > 0) ? h : 1080;
    }

    private void launch() {
        // One implementation of the fan-out, shared with AutoWashActivity, so
        // the Run now button and the idle trigger cannot disagree about which
        // panels get washed.
        int n = WashLauncher.launch(this, band, cycleMs, cycles, grain, target);
        if (n == 0) status.setText(R.string.status_none);
    }

    // ------------------------------------------------------------- widgets --

    private interface Pick { void on(int index); }
    private interface Fmt<T> { String of(T v); }

    private String[] labels(long[] vals, Fmt<Long> f) {
        String[] out = new String[vals.length];
        for (int i = 0; i < vals.length; i++) out[i] = f.of(vals[i]);
        return out;
    }

    private String[] labels(int[] vals, Fmt<Integer> f) {
        String[] out = new String[vals.length];
        for (int i = 0; i < vals.length; i++) out[i] = f.of(vals[i]);
        return out;
    }

    private String[] labels(double[] vals, Fmt<Double> f) {
        String[] out = new String[vals.length];
        for (int i = 0; i < vals.length; i++) out[i] = f.of(vals[i]);
        return out;
    }

    private View pickerRow(String label, String[] options, int selected, Pick pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(getColor(R.color.label));
        t.setTextSize(13f);
        row.addView(t);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            Button b = new Button(this);
            b.setText(options[i]);
            b.setAllCaps(false);
            b.setTextSize(14f);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(10), dp(4), dp(10), dp(4));
            if (i == selected) {
                b.setTypeface(null, Typeface.BOLD);
                b.setTextColor(getColor(R.color.selected));
            } else {
                b.setTextColor(getColor(R.color.unselected));
            }
            b.setOnClickListener(v -> pick.on(index));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            bp.rightMargin = dp(4);
            b.setLayoutParams(bp);
            buttons.addView(b);
        }
        row.addView(buttons);
        return row;
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(22f);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(getColor(R.color.heading));
        t.setGravity(Gravity.START);
        return t;
    }

    private TextView subheading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16f);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(getColor(R.color.heading));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13f);
        t.setTextColor(getColor(R.color.body));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
