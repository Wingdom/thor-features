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
 */
public class FanTileService extends TileService {

    private final FanctlClient client = new FanctlClient(FanctlClient.EXEC);
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshAsync();
    }

    @Override
    public void onClick() {
        super.onClick();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = client.available();
                final FanMode current = ok ? client.getMode() : FanMode.STOCK;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (ok) showPicker(current); else showNoRoot();
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
                main.post(new Runnable() {
                    @Override public void run() { render(actual, true); }
                });
            }
        }, "fanctl-apply").start();
    }

    private void refreshAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = client.available();
                final FanMode mode = ok ? client.getMode() : FanMode.STOCK;
                main.post(new Runnable() {
                    @Override public void run() { render(mode, ok); }
                });
            }
        }, "fanctl-refresh").start();
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
