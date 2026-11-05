package com.thorfeatures.fanctl;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile. Tapping opens a picker with the three tiers, mirroring
 * the stock AYN control rather than cycling blindly.
 *
 * Deliberately thin: everything worth testing lives in FanMode and
 * FanctlClient, which run on the host with no device.
 *
 * Two constraints shape this class:
 *
 *  - Shell calls must never run on the main thread. `su` can block for seconds
 *    and would ANR, so every fanctl call happens on a worker and the UI is
 *    updated back on the main looper.
 *
 *  - The dialog is shown with TileService.showDialog(). The obvious
 *    alternative, startActivityAndCollapse(Intent), throws
 *    UnsupportedOperationException on Android 14+, and this device is 17.
 *
 * A third constraint governs *when* root is asked for, and is the reason
 * onStartListening() renders from ModeStore rather than from fanctl: a request
 * made as the shade opens costs an su spawn and a logged rejection every time
 * and buys nothing. On Magisk 31 it cannot produce a prompt either -- there is
 * no interactive su request any more. See ModeStore and TileRender.
 */
public class FanTileService extends TileService {

    private final FanctlClient client = new FanctlClient(FanctlClient.EXEC);
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        renderCachedAsync();
    }

    @Override
    public void onClick() {
        super.onClick();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = client.available();
                final FanMode current = ok ? client.getMode() : FanMode.STOCK;
                // This is the only place root is requested, so it is also the
                // only place that learns the truth. Record it either way: a
                // refusal clears the cache rather than leaving a label that
                // claims a tier is still applied.
                ModeStore.save(FanTileService.this, TileRender.cacheAfterProbe(ok, current));
                main.post(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            render(current, true);
                            showPicker(current);
                        } else {
                            render(FanMode.STOCK, false);
                            showNoRoot();
                        }
                    }
                });
            }
        }, "fanctl-open").start();
    }

    private void showPicker(FanMode current) {
        final FanMode[] tiers = FanMode.tiers();
        CharSequence[] labels = new CharSequence[tiers.length];
        for (int i = 0; i < tiers.length; i++) labels[i] = tiers[i].label();

        Dialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.picker_title)
                .setSingleChoiceItems(labels, current.tierIndex(),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dlg, int which) {
                                dlg.dismiss();
                                applyAsync(tiers[which]);
                            }
                        })
                .create();
        showDialog(d);
    }

    private void showNoRoot() {
        Dialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.no_root_title)
                .setMessage(R.string.no_root_body)
                .setPositiveButton(R.string.ok, null)
                .create();
        showDialog(d);
    }

    private void applyAsync(final FanMode target) {
        Tile tile = getQsTile();
        if (tile != null) {           // show progress; the round trip is not instant
            tile.setState(Tile.STATE_UNAVAILABLE);   // transient: TileRender.busy()
            tile.updateTile();
        }
        new Thread(new Runnable() {
            @Override public void run() {
                client.apply(target);
                // Re-read instead of trusting the write, so the tile reflects
                // what fanctl actually reports.
                final FanMode actual = client.getMode();
                ModeStore.save(FanTileService.this, actual);
                main.post(new Runnable() {
                    @Override public void run() { render(actual, true); }
                });
            }
        }, "fanctl-apply").start();
    }

    /**
     * Render from the cache. Deliberately names no FanctlClient call.
     *
     * Quick Settings calls onStartListening() every time the shade opens, so
     * an su call here is one process spawn and one logged rejection per shade
     * open -- and on Magisk 31 it can never prompt, so it buys nothing. This
     * path stays root-free and the first su request comes from onClick().
     *
     * The read still happens off the main thread: the first SharedPreferences
     * access is disk I/O, and the tile already renders a frame late today.
     */
    private void renderCachedAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                final FanMode cached = ModeStore.load(FanTileService.this);
                main.post(new Runnable() {
                    @Override public void run() { renderCached(cached); }
                });
            }
        }, "fanctl-cache").start();
    }

    /** @param cached the last mode observed under root, or null if never. */
    private void renderCached(FanMode cached) {
        Tile tile = getQsTile();
        if (tile == null) return;
        int st = TileRender.cachedState(cached);
        tile.setState(st == TileRender.ACTIVE ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(TileRender.needsSetup(cached)
                ? getString(R.string.tile_setup)
                : getString(R.string.tile_label_fmt, cached.label()));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_fan));
        tile.updateTile();
    }

    private void render(FanMode mode, boolean available) {
        Tile tile = getQsTile();
        if (tile == null) return;
        // Not STATE_UNAVAILABLE when root is missing: SystemUI withholds
        // onClick() from an unavailable tile, which would make the explanatory
        // dialog unreachable. See TileRender.
        int st = TileRender.state(available, mode.isActive());
        tile.setState(st == TileRender.ACTIVE ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (!available) {
            tile.setLabel(getString(R.string.tile_unavailable));
        } else {
            tile.setLabel(getString(R.string.tile_label_fmt, mode.label()));
        }
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_fan));
        tile.updateTile();
    }
}
