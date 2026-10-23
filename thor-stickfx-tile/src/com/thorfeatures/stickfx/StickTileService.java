package com.thorfeatures.stickfx;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile for the joystick LED effect layer.
 *
 * Tapping opens a picker with Off / Charge / Breathe, plus a button that opens
 * AynParts' RGB panel -- because colour belongs to AynParts and this module
 * deliberately does not own it.
 *
 * The RGB panel is opened through root `am start` rather than startActivity:
 * RGBLightingSettingsActivity is exported=false, and background activity
 * launches from a service are blocked on modern Android anyway. Root sidesteps
 * both.
 *
 * Deliberately thin: everything worth testing lives in StickMode and
 * StickfxClient, which run on the host with no device.
 *
 * Same two constraints as the fan tile:
 *  - shell calls never run on the main thread (`su` can block for seconds);
 *  - dialogs go through TileService.showDialog(), since
 *    startActivityAndCollapse(Intent) throws on Android 14+ and this is 17.
 */
public class StickTileService extends TileService {

    private final StickfxClient client = new StickfxClient(StickfxClient.EXEC);
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
                final StickMode current = ok ? client.getMode() : StickMode.OFF;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (ok) showPicker(current); else showNoRoot();
                    }
                });
            }
        }, "stickfx-open").start();
    }

    private void showPicker(StickMode current) {
        final StickMode[] choices = StickMode.choices();
        CharSequence[] labels = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) labels[i] = choices[i].label();

        Dialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.picker_title)
                .setSingleChoiceItems(labels, current.choiceIndex(),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dlg, int which) {
                                dlg.dismiss();
                                applyAsync(choices[which]);
                            }
                        })
                .setNeutralButton(R.string.rgb_panel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        dlg.dismiss();
                        openRgbAsync();
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

    private void openRgbAsync() {
        new Thread(new Runnable() {
            @Override public void run() { client.openRgbPanel(); }
        }, "stickfx-rgb").start();
    }

    private void applyAsync(final StickMode target) {
        Tile tile = getQsTile();
        if (tile != null) {           // show progress; the round trip is not instant
            tile.setState(Tile.STATE_UNAVAILABLE);   // transient: TileRender.busy()
            tile.updateTile();
        }
        new Thread(new Runnable() {
            @Override public void run() {
                client.apply(target);
                // Re-read instead of trusting the write, so the tile reflects
                // what stickfx actually reports.
                final StickMode actual = client.getMode();
                main.post(new Runnable() {
                    @Override public void run() { render(actual, true); }
                });
            }
        }, "stickfx-apply").start();
    }

    private void refreshAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = client.available();
                final StickMode mode = ok ? client.getMode() : StickMode.OFF;
                main.post(new Runnable() {
                    @Override public void run() { render(mode, ok); }
                });
            }
        }, "stickfx-refresh").start();
    }

    private void render(StickMode mode, boolean available) {
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
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_stick));
        tile.updateTile();
    }
}
