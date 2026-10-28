package com.thorfeatures.appprofiles;

import com.thorfeatures.fanctl.FanMode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The per-app fan profile editor, and the only user interface for the feature.
 *
 * All the decisions worth testing live in ProfileTable, AppwatchCommands and
 * AppEntry, which are pure and host-tested. What is left here is Android
 * plumbing: inflate, list, dialog, and the two background loads.
 */
public final class ProfilesActivity extends Activity {

    /** Staged copy of the table, handed to root rather than interpolated. */
    private static final String STAGED = "profiles.staged";

    private final RootClient root = new RootClient(RootClient.EXEC);

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private RowAdapter adapter;

    private ListView list;
    private TextView empty, banner, masterSub;
    private EditText search;
    private Switch master;

    private final Map<String, Drawable> icons = new HashMap<>();
    private ProfileTable table = ProfileTable.parse("");

    /**
     * False when the table could not be read. Saving is then refused: writing
     * a table we failed to read would destroy whatever is in it, including the
     * keys this app does not manage.
     */
    private boolean tableReadable = false;
    private String query = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.main);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        banner = findViewById(R.id.banner);
        search = findViewById(R.id.search);
        master = findViewById(R.id.master);
        masterSub = findViewById(R.id.master_sub);

        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> pick(shown.get(pos)));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                refilter();
            }
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
        });

        master.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) {
                if (!v.isPressed()) return;   // ignore our own programmatic set
                masterSub.setText(on ? R.string.master_sub_on : R.string.master_sub_off);
                new Thread(() -> root.setEnabled(on)).start();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    // ------------------------------------------------------------------ load
    private void load() {
        empty.setVisibility(View.VISIBLE);
        empty.setText(R.string.loading);
        new Thread(() -> {
            boolean haveRoot = root.available();
            boolean haveFanctl = haveRoot && root.fanctlAvailable();
            String raw = haveRoot ? root.readTable() : null;
            String status = haveRoot ? root.status().out : "";
            List<AppEntry> loaded = scanApps();
            runOnUiThread(() -> {
                tableReadable = raw != null;
                table = ProfileTable.parse(raw == null ? "" : raw);
                applyOverrides(loaded);
                all.clear();
                all.addAll(loaded);
                AppEntry.sort(all);
                master.setChecked(AppwatchCommands.parseEnabled(status));
                masterSub.setText(master.isChecked()
                        ? R.string.master_sub_on : R.string.master_sub_off);
                showBanner(haveRoot, haveFanctl, status);
                refilter();
            });
        }).start();
    }

    /** Launchable packages, excluding ourselves. */
    private List<AppEntry> scanApps() {
        PackageManager pm = getPackageManager();
        Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launch, 0);
        Map<String, AppEntry> byPkg = new HashMap<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (ai == null || getPackageName().equals(ai.packageName)) continue;
            if (byPkg.containsKey(ai.packageName)) continue;
            boolean game = ai.category == ApplicationInfo.CATEGORY_GAME;
            String label = String.valueOf(ri.loadLabel(pm));
            byPkg.put(ai.packageName, new AppEntry(ai.packageName, label, game, null));
            if (!icons.containsKey(ai.packageName)) {
                try { icons.put(ai.packageName, ri.loadIcon(pm)); } catch (Exception ignored) {}
            }
        }
        return new ArrayList<>(byPkg.values());
    }

    private void applyOverrides(List<AppEntry> entries) {
        Map<String, FanMode> o = table.fanOverrides();
        for (AppEntry e : entries) e.tier = o.get(e.pkg);
    }

    /**
     * Only the most actionable problem is shown. Stacking four banners for one
     * underlying cause - no root means no module answer and no fanctl either -
     * would be noise.
     */
    private void showBanner(boolean haveRoot, boolean haveFanctl, String status) {
        Integer msg = null;
        if (!haveRoot) msg = R.string.banner_no_root;
        else if (!tableReadable) msg = R.string.read_failed;
        else if (!haveFanctl) msg = R.string.banner_no_fanctl;
        else if (!AppwatchCommands.parseDaemonRunning(status)) msg = R.string.banner_no_module;
        else {
            FanMode base = FanMode.fromString(fieldAfter(status, "fanctl:"));
            if (!base.isActive()) msg = R.string.banner_no_baseline;
        }
        if (msg == null) banner.setVisibility(View.GONE);
        else { banner.setText(msg); banner.setVisibility(View.VISIBLE); }
    }

    private static String fieldAfter(String out, String label) {
        if (out == null) return "";
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith(label)) return t.substring(label.length()).trim();
        }
        return "";
    }

    // ---------------------------------------------------------------- filter
    private void refilter() {
        shown.clear();
        for (AppEntry e : all) {
            if (query.isEmpty()
                    || e.label.toLowerCase(Locale.ROOT).contains(query)
                    || e.pkg.toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(e);
            }
        }
        adapter.notifyDataSetChanged();
        if (shown.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(all.isEmpty() ? R.string.loading : R.string.no_match);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ pick
    private void pick(AppEntry e) {
        FanMode[] tiers = FanMode.tiers();
        String[] labels = new String[tiers.length + 1];
        labels[0] = getString(R.string.tier_default);
        for (int i = 0; i < tiers.length; i++) labels[i + 1] = tiers[i].label();

        int checked = 0;
        if (e.hasOverride()) checked = e.tier.tierIndex() + 1;

        new AlertDialog.Builder(this)
            .setTitle(e.label)
            .setSingleChoiceItems(labels, checked, (d, which) -> {
                d.dismiss();
                FanMode chosen = which == 0 ? null : tiers[which - 1];
                save(e, chosen);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Apply one change: edit the table, stage it, hand it to root. */
    private void save(AppEntry e, FanMode tier) {
        if (!tableReadable) {
            Toast.makeText(this, R.string.read_failed, Toast.LENGTH_LONG).show();
            return;
        }
        FanMode previous = e.tier;
        e.tier = tier;
        AppEntry.sort(all);
        refilter();

        table.setFanTier(e.pkg, tier);
        final String text = table.render();
        new Thread(() -> {
            boolean ok = stageAndWrite(text);
            if (!ok) {
                runOnUiThread(() -> {
                    // Put the row back: the table on disk did not change.
                    e.tier = previous;
                    table.setFanTier(e.pkg, previous);
                    AppEntry.sort(all);
                    refilter();
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private boolean stageAndWrite(String text) {
        File staged = new File(getFilesDir(), STAGED);
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(staged), StandardCharsets.UTF_8)) {
            w.write(text);
        } catch (Exception ex) {
            return false;
        }
        // Readable by root regardless of mode, and removed afterwards so the
        // staged copy never becomes a stale second source of truth.
        boolean ok = root.writeTable(staged.getAbsolutePath());
        staged.delete();
        return ok;
    }

    // --------------------------------------------------------------- adapter
    private final class RowAdapter extends ArrayAdapter<AppEntry> {
        RowAdapter() { super(ProfilesActivity.this, 0, shown); }

        @Override public int getCount() { return shown.size(); }
        @Override public AppEntry getItem(int i) { return shown.get(i); }

        @Override public View getView(int pos, View reuse, ViewGroup parent) {
            View v = reuse;
            if (v == null) {
                v = LayoutInflater.from(getContext()).inflate(R.layout.app_row, parent, false);
            }
            AppEntry e = shown.get(pos);
            ((TextView) v.findViewById(R.id.label)).setText(e.label);
            ((TextView) v.findViewById(R.id.pkg)).setText(e.pkg);
            TextView tier = v.findViewById(R.id.tier);
            if (e.hasOverride()) {
                tier.setText(e.tier.label());
                tier.setBackgroundResource(R.drawable.tier_chip);
            } else {
                tier.setText(R.string.tier_default);
                tier.setBackground(null);
            }
            ImageView icon = v.findViewById(R.id.icon);
            Drawable d = icons.get(e.pkg);
            if (d != null) icon.setImageDrawable(d);
            else icon.setImageResource(R.drawable.ic_appprofiles);
            return v;
        }
    }
}
