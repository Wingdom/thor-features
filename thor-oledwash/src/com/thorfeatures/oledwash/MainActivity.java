package com.thorfeatures.oledwash;

import android.app.ActivityOptions;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * The draw-only control panel.
 *
 * <p>No idle trigger, no automation, nothing that runs by itself. This build
 * exists so the wash can be looked at and the scroll speed judged, because the
 * arithmetic cannot settle that question: sweep speed does not change how much
 * drive each pixel receives per second, only how that drive is parcelled, so
 * the choice is about what reads as deliberate rather than alarming. That is a
 * question for a person holding the device.
 */
public final class MainActivity extends Activity {

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
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void build() {
        content.removeAllViews();

        content.addView(heading(getString(R.string.app_name)));
        content.addView(body(getString(R.string.intro)));

        content.addView(pickerRow(getString(R.string.label_speed),
                labels(Params.CYCLE_MS, v -> String.format(Locale.US, "%.1fs", v / 1000.0)),
                Params.indexOf(Params.CYCLE_MS, cycleMs),
                i -> { cycleMs = Params.CYCLE_MS[i]; refreshAll(); }));

        content.addView(pickerRow(getString(R.string.label_band),
                labels(Params.BAND, v -> String.format(Locale.US, "%.0f%%", v * 100.0)),
                Params.indexOf(Params.BAND, band),
                i -> { band = Params.BAND[i]; refreshAll(); }));

        content.addView(pickerRow(getString(R.string.label_cycles),
                labels(Params.CYCLES, v -> String.valueOf(v)),
                Params.indexOf(Params.CYCLES, cycles),
                i -> { cycles = Params.CYCLES[i]; refreshAll(); }));

        content.addView(pickerRow(getString(R.string.label_grain),
                labels(Params.GRAIN, v -> String.valueOf(v)),
                Params.indexOf(Params.GRAIN, grain),
                i -> { grain = Params.GRAIN[i]; refreshAll(); }));

        content.addView(pickerRow(getString(R.string.label_screens),
                new String[]{ getString(R.string.screens_all),
                              getString(R.string.screens_primary),
                              getString(R.string.screens_secondary) },
                target,
                i -> { target = i; refreshAll(); }));

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

    /** Rebuild so the selected button in each row is redrawn. */
    private void refreshAll() {
        build();
    }

    private void refresh() {
        int h = panelHeight();
        WashPlan plan = WashPlan.of(h, band, cycleMs, cycles);
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
        List<Integer> ids = DisplayChoice.pick(Displays.panels(this), target);
        if (ids.isEmpty()) {
            status.setText(R.string.status_none);
            return;
        }

        WashSession.start();
        for (int id : ids) {
            Intent i = new Intent(this, WashActivity.class);
            i.putExtra(WashActivity.EXTRA_BAND, band);
            i.putExtra(WashActivity.EXTRA_CYCLE_MS, cycleMs);
            i.putExtra(WashActivity.EXTRA_CYCLES, cycles);
            i.putExtra(WashActivity.EXTRA_GRAIN, grain);
            i.putExtra(WashActivity.EXTRA_DIAGNOSTICS, true);
            // MULTIPLE_TASK so the second panel gets its own instance rather
            // than the system reusing the first one and moving it across.
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(id);
            startActivity(i, opts.toBundle());
        }
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
